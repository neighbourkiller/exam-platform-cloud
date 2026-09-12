package com.ekusys.exam.runtime.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.runtime.config.ClientLeaseProperties;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ExamClientLeaseServiceTest {
    private JdbcTemplate jdbc;
    private StringRedisTemplate redis;
    private ExamClientLeaseService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        redis = mock(StringRedisTemplate.class);
        service = new ExamClientLeaseService(jdbc, redis, new ClientLeaseProperties(), null);
    }

    @Test
    void missingClientIdIsRejectedWithRequiredCode() {
        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> service.acquire(10L, 20L, 1L, deadline(), "", null, now())
        );

        assertEquals(ExamClientLeaseService.REQUIRED_CODE, exception.getCode());
    }

    @Test
    void matchingTokenRenewsWithoutRotationOrDatabaseAccess() {
        mockRedisRenewal();

        ExamClientLeaseContext context = service.renew(
            10L, 20L, "client-a", "stable-token", now(), true
        );

        assertEquals("stable-token", context.lease().getLeaseToken());
        assertEquals(30, context.lease().getHeartbeatIntervalSeconds());
        assertEquals(90, context.lease().getLeaseTimeoutSeconds());
        assertEquals(1L, context.sessionId());
        verifyNoInteractions(jdbc);
    }

    @Test
    void concurrentHeartbeatAndSnapshotCanReuseSameStableToken() {
        mockRedisRenewal();

        ExamClientLeaseContext heartbeat = service.renew(
            10L, 20L, "client-a", "stable-token", now(), false
        );
        ExamClientLeaseContext snapshot = service.renew(
            10L, 20L, "client-a", "stable-token", now().plusSeconds(1), true
        );

        assertEquals("stable-token", heartbeat.lease().getLeaseToken());
        assertEquals("stable-token", snapshot.lease().getLeaseToken());
        verifyNoInteractions(jdbc);
    }

    @Test
    void redisRenewalPersistsDatabaseLivenessOnlyWhenRequested() {
        long deadlineEpoch = deadline().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        doReturn(List.of("1", "1", String.valueOf(deadlineEpoch), "90000", "1"))
            .when(redis).execute(
                any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString()
            );
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        service.renew(10L, 20L, "client-a", "stable-token", now(), false);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Object[].class));
        org.junit.jupiter.api.Assertions.assertTrue(
            sql.getValue().contains("active_client_last_seen")
        );
    }

    @Test
    void differentClientIsRejectedWithConflictCode() {
        doReturn(List.of("-1")).when(redis).execute(
            any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString(),
            anyString(), anyString()
        );

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> service.renew(10L, 20L, "client-b", "wrong-token", now(), false)
        );

        assertEquals(ExamClientLeaseService.CONFLICT_CODE, exception.getCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void expiredOrEmptyLeaseCanBeAcquiredWithConfiguredInterval() {
        doReturn(1L).when(redis).execute(
            any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString(),
            anyString(), anyString(), anyString(), anyString()
        );
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        var lease = service.acquire(10L, 20L, 1L, deadline(), "client-a", null, now());

        assertEquals(30, lease.getHeartbeatIntervalSeconds());
        assertEquals(90, lease.getLeaseTimeoutSeconds());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Object[].class));
        org.junit.jupiter.api.Assertions.assertTrue(sql.getValue().contains("active_client_lease_until<=?"));
    }

    @Test
    void newlyAcquiredRedisLeaseIsRemovedWhenTransactionRollsBack() {
        doReturn(1L).when(redis).execute(
            any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString(),
            anyString(), anyString(), anyString(), anyString()
        );
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();
        try {
            var lease = service.acquire(10L, 20L, 1L, deadline(), "client-a", null, now());

            assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
            TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)
            );

            verify(redis).execute(any(RedisScript.class), anyList(), eq(lease.getLeaseToken()));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void submitRequiresCurrentLeaseWithoutRotatingToken() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        service.requireCurrent(1L, "client-a", "stable-token", now());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Object[].class));
        assertFalse(sql.getValue().contains("set active_client_token=?"));
    }

    @Test
    void clearBlockedThreeSecondsDoesNotBlockCallingThread() throws Exception {
        java.util.concurrent.Executor asyncExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.CountDownLatch blockLatch = new java.util.concurrent.CountDownLatch(1);

        org.mockito.Mockito.doAnswer(invocation -> {
            blockLatch.await(3, java.util.concurrent.TimeUnit.SECONDS);
            return 1L;
        }).when(redis).execute(any(RedisScript.class), anyList(), anyString());

        ExamClientLeaseService asyncService = new ExamClientLeaseService(
            jdbc, redis, new ClientLeaseProperties(), asyncExecutor
        );

        long start = System.currentTimeMillis();
        asyncService.clearAfterCommit(10L, 20L, "token");
        long elapsed = System.currentTimeMillis() - start;

        org.assertj.core.api.Assertions.assertThat(elapsed).isLessThan(500);
        blockLatch.countDown();
    }

    @Test
    void clearQueueSaturationDropsGracefullyWithoutThrowing() {
        java.util.concurrent.Executor rejectingExecutor = command -> {
            throw new RuntimeException("Queue saturated");
        };
        ExamClientLeaseService rejectingService = new ExamClientLeaseService(
            jdbc, redis, new ClientLeaseProperties(), rejectingExecutor
        );

        // Must not throw to caller
        rejectingService.clearAfterCommit(10L, 20L, "token");
    }

    private void mockRedisRenewal() {
        long deadlineEpoch = deadline().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        doReturn(List.of("1", "1", String.valueOf(deadlineEpoch), "90000", "0"))
            .when(redis).execute(
                any(RedisScript.class), anyList(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString()
            );
    }

    private LocalDateTime now() {
        return LocalDateTime.of(2026, 7, 9, 10, 0);
    }

    private LocalDateTime deadline() {
        return now().plusHours(1);
    }
}
