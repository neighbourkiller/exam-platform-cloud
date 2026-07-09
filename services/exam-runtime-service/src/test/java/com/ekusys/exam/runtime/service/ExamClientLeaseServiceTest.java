package com.ekusys.exam.runtime.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.exam.dto.ExamClientLeaseView;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class ExamClientLeaseServiceTest {
    private JdbcTemplate jdbc;
    private ExamClientLeaseService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new ExamClientLeaseService(jdbc);
    }

    @Test
    void missingClientIdIsRejectedWithRequiredCode() {
        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> service.acquire(1L, "", null, LocalDateTime.now())
        );

        assertEquals(ExamClientLeaseService.REQUIRED_CODE, exception.getCode());
    }

    @Test
    void activeDifferentClientIsRejectedWithConflictCode() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> service.acquire(1L, "client-b", null, LocalDateTime.of(2026, 7, 9, 10, 0))
        );

        assertEquals(ExamClientLeaseService.CONFLICT_CODE, exception.getCode());
    }

    @Test
    void expiredOrEmptyLeaseCanBeAcquired() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 9, 10, 0);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        ExamClientLeaseView lease = service.acquire(1L, "client-a", null, now);

        assertEquals(now.plusSeconds(90), lease.getLeaseExpiresAt());
        assertEquals(15, lease.getHeartbeatIntervalSeconds());
        assertEquals(90, lease.getLeaseTimeoutSeconds());
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Object[].class));
        org.junit.jupiter.api.Assertions.assertTrue(sql.getValue().contains("active_client_lease_until<=?"));
    }

    @Test
    void matchingTokenRenewsAndRotatesToken() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 9, 10, 0);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        ExamClientLeaseView lease = service.renew(1L, "client-a", "old-token", now);

        assertNotEquals("old-token", lease.getLeaseToken());
        assertEquals(now.plusSeconds(90), lease.getLeaseExpiresAt());
    }

    @Test
    void reusedOldTokenOnlySucceedsOnce() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 9, 10, 0);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1, 0);

        service.renew(1L, "client-a", "old-token", now);
        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> service.renew(1L, "client-a", "old-token", now.plusSeconds(1))
        );

        assertEquals(ExamClientLeaseService.CONFLICT_CODE, exception.getCode());
    }

    @Test
    void submitRequiresCurrentLeaseWithoutRotatingToken() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 9, 10, 0);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        service.requireCurrent(1L, "client-a", "current-token", now);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Object[].class));
        org.junit.jupiter.api.Assertions.assertFalse(sql.getValue().contains("set active_client_token=?"));
    }
}
