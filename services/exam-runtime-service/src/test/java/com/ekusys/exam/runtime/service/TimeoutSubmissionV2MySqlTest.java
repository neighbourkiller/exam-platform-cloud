package com.ekusys.exam.runtime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ekusys.exam.common.outbox.OutboxEventWriter;
import com.ekusys.exam.exam.dto.AnswerPayload;
import com.ekusys.exam.exam.dto.SubmitExamRequest;
import com.ekusys.exam.runtime.config.TimeoutSubmissionProperties;
import com.ekusys.exam.runtime.messaging.RuntimeOutboxService;
import com.ekusys.exam.runtime.repository.TimeoutTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;

class TimeoutSubmissionV2MySqlTest {
    private static final long DEFAULT_CROSS_SHARD_DELAY_MS = 10_000L;

    private static MySQLContainer mysql;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private TimeoutTaskRepository tasks;
    private SubmissionFinalPayloadService finalPayloads;
    private ManualSubmissionService manualSubmissions;
    private RuntimeOutboxService outbox;

    @BeforeAll
    static void startDatabase() {
        jdbcUrl = value("timeout.test.jdbc-url", "TIMEOUT_TEST_JDBC_URL", null);
        username = value("timeout.test.username", "TIMEOUT_TEST_USERNAME", "test");
        password = value("timeout.test.password", "TIMEOUT_TEST_PASSWORD", "test");
        if (jdbcUrl != null) {
            return;
        }
        try {
            mysql = new MySQLContainer("mysql:8.4")
                .withDatabaseName("timeout_test")
                .withUsername(username)
                .withPassword(password);
            mysql.start();
            jdbcUrl = mysql.getJdbcUrl();
            username = mysql.getUsername();
            password = mysql.getPassword();
        } catch (RuntimeException exception) {
            jdbcUrl = null;
        }
    }

    @AfterAll
    static void stopDatabase() {
        if (mysql != null && mysql.isRunning()) {
            mysql.stop();
        }
    }

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(jdbcUrl != null, "Docker or external MySQL is required");
        DataSource dataSource = new DriverManagerDataSource(jdbcUrl, username, password);
        jdbc = new JdbcTemplate(dataSource);
        String databaseName = jdbc.queryForObject("select database()", String.class);
        Assumptions.assumeTrue(
            databaseName != null && databaseName.toLowerCase(Locale.ROOT).contains("test"),
            "Timeout submission integration tests require an isolated test database"
        );
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tasks = new TimeoutTaskRepository(jdbc);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        finalPayloads = new SubmissionFinalPayloadService(jdbc, objectMapper);
        RuntimeOutboxService outboxService = new RuntimeOutboxService(
            jdbc, new OutboxEventWriter(jdbc, objectMapper)
        );
        this.outbox = outboxService;
        SubmissionStatusProjectionService projectionService = mock(SubmissionStatusProjectionService.class);
        manualSubmissions = new ManualSubmissionService(
            tasks, finalPayloads, outbox, projectionService, jdbc, transactions
        );
        createSchema();
    }

    @Test
    void oneHundredConcurrentManualRetriesCreateOnePayloadAndOneLogicalEvent() {
        LocalDateTime deadline = jdbc.queryForObject(
            "select timestampadd(minute,5,current_timestamp(3))",
            LocalDateTime.class
        );
        seedSession(1L, 10L, 20L, 30L, deadline);
        SubmitExamRequest request = request();

        List<CompletableFuture<ManualSubmissionService.ManualSubmissionResult>> calls =
            IntStream.range(0, 100)
                .mapToObj(index -> CompletableFuture.supplyAsync(() -> manualSubmissions.submit(
                    1L, 10L, 20L, 30L, request,
                    finalPayloads.encode(request.getAnswers(), index)
                )))
                .toList();
        CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).join();

        assertThat(calls).allSatisfy(call ->
            assertThat(call.join().status()).isEqualTo("PROCESSING"));
        assertThat(jdbc.queryForObject(
            "select count(*) from submission_final_payload where submission_id=30", Integer.class
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "select count(*) from outbox_event where aggregate_id='30' and event_type='SubmissionAccepted'",
            Integer.class
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "select status from exam_session where id=1", String.class
        )).isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject(
            "select status from submission_timeout_task where id=1", String.class
        )).isEqualTo("DONE");
    }

    @Test
    void twoClaimersUseSkipLockedAndStaleTokenCannotComplete() {
        LocalDateTime dueAt = jdbc.queryForObject(
            "select timestampadd(second,-1,current_timestamp(3))",
            LocalDateTime.class
        );
        for (long id = 1; id <= 20; id++) {
            jdbc.update(
                """
                    insert into submission_timeout_task(
                        id,session_id,exam_id,student_id,due_at,status,attempt_count,created_at,updated_at
                    ) values(?,?,?,?,?,'PENDING',0,current_timestamp(3),current_timestamp(3))
                    """,
                id, id, 10L, 100L + id, dueAt
            );
        }
        CyclicBarrier barrier = new CyclicBarrier(2);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        CompletableFuture<Map<Long, String>> first = claimTen("claim-a", barrier, pool);
        CompletableFuture<Map<Long, String>> second = claimTen("claim-b", barrier, pool);
        Map<Long, String> firstClaims = first.join();
        Map<Long, String> secondClaims = second.join();
        pool.shutdownNow();

        Set<Long> overlap = new HashSet<>(firstClaims.keySet());
        overlap.retainAll(secondClaims.keySet());
        assertThat(overlap).isEmpty();
        assertThat(firstClaims).hasSize(10);
        assertThat(secondClaims).hasSize(10);

        Map.Entry<Long, String> owned = firstClaims.entrySet().iterator().next();
        assertThat(tasks.markDone(owned.getKey(), "stale-token")).isZero();
        assertThat(tasks.markDone(owned.getKey(), owned.getValue())).isEqualTo(1);

        Map.Entry<Long, String> expired = firstClaims.entrySet().stream()
            .filter(entry -> !entry.getKey().equals(owned.getKey()))
            .findFirst()
            .orElseThrow();
        jdbc.update(
            "update submission_timeout_task set lease_until=timestampadd(second,-1,current_timestamp(3)) where id=?",
            expired.getKey()
        );
        assertThat(tasks.markDone(expired.getKey(), expired.getValue())).isZero();

        Map<Long, String> recovered = transactions.execute(status -> {
            List<TimeoutTaskRepository.TaskCandidate> candidates =
                new ArrayList<>(tasks.lockClaimable(0, 1, 1, DEFAULT_CROSS_SHARD_DELAY_MS).candidates());
            assertThat(candidates).singleElement().satisfies(candidate -> {
                assertThat(candidate.id()).isEqualTo(expired.getKey());
                assertThat(candidate.recoveredLease()).isTrue();
            });
            String newToken = "recovered-token";
            assertThat(tasks.markProcessing(expired.getKey(), newToken, 60_000L)).isEqualTo(1);
            return Map.of(expired.getKey(), newToken);
        });
        assertThat(recovered).isNotNull();
        assertThat(tasks.markDone(expired.getKey(), expired.getValue())).isZero();
        assertThat(tasks.renewLease(expired.getKey(), expired.getValue(), 60_000L)).isZero();
        assertThat(tasks.renewLease(expired.getKey(), recovered.get(expired.getKey()), 60_000L))
            .isEqualTo(1);
        assertThat(tasks.markDone(expired.getKey(), recovered.get(expired.getKey()))).isEqualTo(1);
    }

    @Test
    void ownShardPendingClaimedAndForeignPendingGatedByCrossShardDelay() {
        LocalDateTime now = jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class);
        LocalDateTime dueNow = timestampaddSeconds(now, -1);
        seedShardTask(2, "PENDING", dueNow, null, null);
        seedShardTask(1, "PENDING", dueNow, null, null);

        List<Long> immediate = claimShardIds(0, 2, 10, DEFAULT_CROSS_SHARD_DELAY_MS);
        assertThat(immediate).containsExactly(2L);
        transactions.executeWithoutResult(status ->
            assertThat(tasks.markProcessing(2L, "claim-2", 60_000L)).isEqualTo(1));

        LocalDateTime overdue = timestampaddSeconds(now, -11);
        jdbc.update("update submission_timeout_task set due_at=? where id=1", overdue);
        List<Long> afterDelay = claimShardIds(0, 2, 10, DEFAULT_CROSS_SHARD_DELAY_MS);
        assertThat(afterDelay).containsExactly(1L);
    }

    @Test
    void crossShardWaitBoundaryUsesDatabaseTime() {
        LocalDateTime now = jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class);
        seedShardTask(1, "PENDING", timestampaddSeconds(now, -9), null, null);

        assertThat(claimShardIds(0, 2, 10, DEFAULT_CROSS_SHARD_DELAY_MS)).isEmpty();
        assertThat(claimShardIds(0, 2, 10, 8_000L)).containsExactly(1L);

        jdbc.update(
            "update submission_timeout_task set due_at=? where id=1",
            timestampaddSeconds(now, -11)
        );
        assertThat(claimShardIds(0, 2, 10, DEFAULT_CROSS_SHARD_DELAY_MS)).containsExactly(1L);
    }

    @Test
    void expiredLeaseRecoveredGloballyAheadOfPendingAndRespectsLimit() {
        LocalDateTime now = jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class);
        LocalDateTime expiredLease = timestampaddSeconds(now, -30);
        LocalDateTime dueNow = timestampaddSeconds(now, -1);
        seedShardTask(1, "PROCESSING", dueNow, expiredLease, null);
        seedShardTask(4, "PROCESSING", dueNow, expiredLease, null);
        seedShardTask(8, "PROCESSING", dueNow, expiredLease, null);
        seedShardTask(2, "PENDING", dueNow, null, null);

        List<Long> first = claimShardIds(0, 2, 2, DEFAULT_CROSS_SHARD_DELAY_MS);
        assertThat(first).containsExactly(1L, 4L);
        transactions.executeWithoutResult(status -> first.forEach(id ->
            assertThat(tasks.markProcessing(id, "holder-" + id, 60_000L)).isEqualTo(1)));

        List<TimeoutTaskRepository.TaskCandidate> second =
            transactions.execute(status -> tasks.lockClaimable(0, 2, 10, DEFAULT_CROSS_SHARD_DELAY_MS).candidates());
        assertThat(second).isNotNull();
        assertThat(second).extracting(TimeoutTaskRepository.TaskCandidate::id)
            .containsExactly(8L, 2L);
        assertThat(second.get(0).recoveredLease()).isTrue();
        assertThat(second.get(1).recoveredLease()).isFalse();
    }

    @Test
    void validLeaseCannotBePreemptedByGlobalRecovery() {
        LocalDateTime now = jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class);
        LocalDateTime futureLease = timestampaddSeconds(now, 60);
        LocalDateTime dueNow = timestampaddSeconds(now, -1);
        seedShardTask(2, "PROCESSING", dueNow, futureLease, null);
        seedShardTask(1, "PENDING", timestampaddSeconds(now, -30), null, null);

        assertThat(claimShardIds(0, 2, 10, DEFAULT_CROSS_SHARD_DELAY_MS)).containsExactly(1L);
    }

    @Test
    void ownShardBusyStillReservesCrossShardQuota() {
        LocalDateTime now = jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class);
        LocalDateTime dueNow = timestampaddSeconds(now, -1);
        LocalDateTime overdue = timestampaddSeconds(now, -11);
        for (long id : new long[] {2, 4, 6, 8, 10, 12, 14, 16}) {
            seedShardTask(id, "PENDING", dueNow, null, null);
        }
        for (long id : new long[] {1, 3, 5, 7}) {
            seedShardTask(id, "PENDING", overdue, null, null);
        }

        List<Long> claimed = claimShardIds(0, 2, 8, DEFAULT_CROSS_SHARD_DELAY_MS);

        long own = claimed.stream().filter(id -> id % 2 == 0).count();
        long cross = claimed.stream().filter(id -> id % 2 == 1).count();
        assertThat(claimed).hasSize(8);
        assertThat(own).isEqualTo(4);
        assertThat(cross).isEqualTo(4);
        assertThat(new HashSet<>(claimed)).hasSize(8);
    }

    @Test
    void crossShardInsufficientFillsOwnShardWithoutDuplicates() {
        LocalDateTime now = jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class);
        LocalDateTime dueNow = timestampaddSeconds(now, -1);
        LocalDateTime overdue = timestampaddSeconds(now, -11);
        for (long id : new long[] {2, 4, 6, 8, 10, 12, 14, 16, 18, 20}) {
            seedShardTask(id, "PENDING", dueNow, null, null);
        }
        seedShardTask(1, "PENDING", overdue, null, null);
        seedShardTask(3, "PENDING", overdue, null, null);

        List<Long> claimed = claimShardIds(0, 2, 8, DEFAULT_CROSS_SHARD_DELAY_MS);

        long cross = claimed.stream().filter(id -> id % 2 == 1).count();
        assertThat(claimed).hasSize(8);
        assertThat(cross).isEqualTo(2);
        assertThat(new HashSet<>(claimed)).hasSize(8);
    }

    @Test
    void limitOneMultiShardGivesOverdueCrossShardOneChanceThenOwnFill() {
        LocalDateTime now = jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class);
        LocalDateTime dueNow = timestampaddSeconds(now, -1);
        LocalDateTime overdue = timestampaddSeconds(now, -11);
        seedShardTask(2, "PENDING", dueNow, null, null);
        seedShardTask(1, "PENDING", overdue, null, null);

        assertThat(claimShardIds(0, 2, 1, DEFAULT_CROSS_SHARD_DELAY_MS)).containsExactly(1L);
        transactions.executeWithoutResult(status ->
            assertThat(tasks.markProcessing(1L, "claim-1", 60_000L)).isEqualTo(1));

        List<Long> ownAfter = claimShardIds(0, 2, 1, DEFAULT_CROSS_SHARD_DELAY_MS);
        assertThat(ownAfter).containsExactly(2L);
        transactions.executeWithoutResult(status ->
            assertThat(tasks.markProcessing(2L, "claim-2", 60_000L)).isEqualTo(1));

        seedShardTask(4, "PENDING", dueNow, null, null);
        assertThat(claimShardIds(0, 2, 1, DEFAULT_CROSS_SHARD_DELAY_MS)).containsExactly(4L);
    }

    @Test
    void singleShardSkipsCrossShardReservationAndUsesFullQuota() {
        LocalDateTime dueAt = timestampaddSeconds(
            jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class), -1
        );
        for (long id = 1; id <= 8; id++) {
            seedShardTask(id, "PENDING", dueAt, null, null);
        }

        List<Long> firstBatch = claimShardIds(0, 1, 5, DEFAULT_CROSS_SHARD_DELAY_MS);
        assertThat(firstBatch).hasSize(5);
        transactions.executeWithoutResult(status -> firstBatch.forEach(id ->
            assertThat(tasks.markProcessing(id, "single-" + id, 60_000L)).isEqualTo(1)));
        assertThat(claimShardIds(0, 1, 5, DEFAULT_CROSS_SHARD_DELAY_MS)).hasSize(3);
    }

    @Test
    void shardClaimExcludesNotYetDueBackoffActiveLeaseDoneAndFailedTasks() {
        LocalDateTime now = jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class);
        LocalDateTime dueAt = timestampaddSeconds(now, -1);
        LocalDateTime future = timestampaddSeconds(now, 60);
        seedShardTask(2, "PENDING", dueAt, null, null);
        seedShardTask(4, "PENDING", future, null, null);
        seedShardTask(6, "PENDING", dueAt, null, future);
        seedShardTask(8, "PROCESSING", dueAt, future, null);
        seedShardTask(10, "DONE", dueAt, null, null);
        seedShardTask(12, "FAILED", dueAt, null, null);

        assertThat(claimShardIds(0, 2, 10, DEFAULT_CROSS_SHARD_DELAY_MS)).containsExactly(2L);
        assertThat(claimShardIds(1, 2, 10, DEFAULT_CROSS_SHARD_DELAY_MS)).isEmpty();
    }

    @Test
    void shardFilterAppliesBeforeLimitSoForeignLeadersDoNotHideOwnTasks() {
        LocalDateTime now = jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class);
        seedShardTask(1, "PENDING", timestampaddSeconds(now, -9), null, null);
        seedShardTask(3, "PENDING", timestampaddSeconds(now, -8), null, null);
        seedShardTask(2, "PENDING", timestampaddSeconds(now, -7), null, null);
        seedShardTask(4, "PENDING", timestampaddSeconds(now, -5), null, null);

        List<Long> first = claimShardIds(0, 2, 1, DEFAULT_CROSS_SHARD_DELAY_MS);
        assertThat(first).containsExactly(2L);
        transactions.executeWithoutResult(status ->
            assertThat(tasks.markProcessing(2L, "claim-2", 60_000L)).isEqualTo(1));

        assertThat(claimShardIds(0, 2, 10, DEFAULT_CROSS_SHARD_DELAY_MS)).containsExactly(4L);
    }

    @Test
    void retryBackoffGatesClaimUntilAvailableAtIncludingCrossShard() {
        LocalDateTime now = jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class);
        LocalDateTime dueAt = timestampaddSeconds(now, -1);
        LocalDateTime futureRetry = timestampaddSeconds(now, 60);
        seedShardTask(1, "PENDING", dueAt, null, futureRetry);

        assertThat(claimShardIds(0, 2, 10, DEFAULT_CROSS_SHARD_DELAY_MS)).isEmpty();
        assertThat(claimShardIds(1, 2, 10, DEFAULT_CROSS_SHARD_DELAY_MS)).isEmpty();

        jdbc.update(
            "update submission_timeout_task set next_retry_at=? where id=1",
            timestampaddSeconds(now, -11)
        );
        assertThat(claimShardIds(1, 2, 10, DEFAULT_CROSS_SHARD_DELAY_MS)).containsExactly(1L);
    }

    @Test
    void concurrentClaimersOfSameShardDoNotOverlap() {
        LocalDateTime dueAt = timestampaddSeconds(
            jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class), -1
        );
        for (long id : new long[] {1, 4, 7, 10, 13, 16, 19, 22}) {
            seedShardTask(id, "PENDING", dueAt, null, null);
        }
        CyclicBarrier barrier = new CyclicBarrier(2);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        CompletableFuture<Set<Long>> first = claimShardWithToken(1, 3, "claimer-a", barrier, pool);
        CompletableFuture<Set<Long>> second = claimShardWithToken(1, 3, "claimer-b", barrier, pool);
        Set<Long> firstClaims = first.join();
        Set<Long> secondClaims = second.join();
        pool.shutdownNow();

        Set<Long> overlap = new HashSet<>(firstClaims);
        overlap.retainAll(secondClaims);
        assertThat(overlap).isEmpty();
        Set<Long> union = new HashSet<>(firstClaims);
        union.addAll(secondClaims);
        assertThat(union).containsExactlyInAnyOrder(1L, 4L, 7L, 10L, 13L, 16L, 19L, 22L);
    }

    @Test
    void concurrentCrossShardReclaimersDoNotOverlap() {
        LocalDateTime overdue = timestampaddSeconds(
            jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class), -11
        );
        for (long id : new long[] {1, 3, 5, 7, 9, 11, 13, 15}) {
            seedShardTask(id, "PENDING", overdue, null, null);
        }
        CyclicBarrier barrier = new CyclicBarrier(2);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        CompletableFuture<Set<Long>> first = claimShardWithToken(0, 2, "reclaimer-a", barrier, pool);
        CompletableFuture<Set<Long>> second = claimShardWithToken(0, 2, "reclaimer-b", barrier, pool);
        Set<Long> firstClaims = first.join();
        Set<Long> secondClaims = second.join();
        pool.shutdownNow();

        Set<Long> overlap = new HashSet<>(firstClaims);
        overlap.retainAll(secondClaims);
        assertThat(overlap).isEmpty();
        Set<Long> union = new HashSet<>(firstClaims);
        union.addAll(secondClaims);
        assertThat(union).containsExactlyInAnyOrder(1L, 3L, 5L, 7L, 9L, 11L, 13L, 15L);
    }

    @Test
    void lockedCandidateRowIsSkippedAndRestOfShardIsStillClaimed() throws Exception {
        LocalDateTime dueAt = timestampaddSeconds(
            jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class), -1
        );
        seedShardTask(2, "PENDING", dueAt, null, null);
        seedShardTask(4, "PENDING", dueAt, null, null);
        seedShardTask(6, "PENDING", dueAt, null, null);

        DataSource dataSource = jdbc.getDataSource();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (Statement statement = holder.createStatement()) {
                statement.execute("select id from submission_timeout_task where id=2 for update");
            }
            assertThat(claimShardIds(0, 2, 10, DEFAULT_CROSS_SHARD_DELAY_MS)).containsExactly(4L, 6L);
            holder.rollback();
        }
    }

    @Test
    void reshardingTakeoverWithoutTopologyChange() {
        LocalDateTime now = jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class);
        LocalDateTime overdue = timestampaddSeconds(now, -11);
        for (long id = 1; id <= 12; id++) {
            seedShardTask(id, "PENDING", overdue, null, null);
        }

        // 模拟"故障实例"（shard 0 of 4）先领取本片任务并持有有效租约
        Map<Long, String> inflightTokens = new HashMap<>();
        transactions.executeWithoutResult(status -> tasks.lockClaimable(0, 4, 10, 60_000L)
            .candidates().forEach(candidate -> {
                String token = "inflight-" + candidate.id();
                assertThat(tasks.markProcessing(candidate.id(), token, 120_000L)).isEqualTo(1);
                inflightTokens.put(candidate.id(), token);
            }));
        assertThat(inflightTokens.keySet()).containsExactlyInAnyOrder(4L, 8L, 12L);

        // 幸存实例在 shardTotal 仍为 4 时即可跨片补领故障分片尚未领取的任务
        Set<Long> survivorClaims = new HashSet<>();
        for (int shardIndex = 1; shardIndex < 4; shardIndex++) {
            final int shard = shardIndex;
            survivorClaims.addAll(claimShardIds(shard, 4, 10, DEFAULT_CROSS_SHARD_DELAY_MS));
        }
        assertThat(survivorClaims).hasSize(9);
        assertThat(survivorClaims).noneMatch(id -> id % 4 == 0);
        transactions.executeWithoutResult(status -> survivorClaims.forEach(id ->
            assertThat(tasks.markProcessing(id, "survivor-" + id, 60_000L)).isEqualTo(1)));

        // 遗弃在途任务在租约过期后由任意分片全局恢复，旧令牌失效
        jdbc.update(
            "update submission_timeout_task set lease_until=timestampadd(second,-1,current_timestamp(3)) where id in (4,8)"
        );
        List<Long> recovered = claimShardIds(1, 4, 10, DEFAULT_CROSS_SHARD_DELAY_MS);
        assertThat(recovered).containsExactlyInAnyOrder(4L, 8L);
        assertThat(tasks.markDone(4L, inflightTokens.get(4L))).isZero();
        transactions.executeWithoutResult(status ->
            assertThat(tasks.markProcessing(4L, "recovered-token", 60_000L)).isEqualTo(1));
        assertThat(tasks.renewLease(4L, inflightTokens.get(4L), 60_000L)).isZero();
        assertThat(tasks.renewLease(4L, "recovered-token", 60_000L)).isEqualTo(1);
        assertThat(tasks.markDone(4L, "recovered-token")).isEqualTo(1);
    }

    @Test
    void topologyOverlapFourAndThreeAvoidsDoubleValidClaim() {
        LocalDateTime overdue = timestampaddSeconds(
            jdbc.queryForObject("select current_timestamp(3)", LocalDateTime.class), -11
        );
        for (long id = 1; id <= 12; id++) {
            seedShardTask(id, "PENDING", overdue, null, null);
        }

        // 旧拓扑（total=4）实例先领取并持有效租约
        Map<Long, String> tokens = new HashMap<>();
        transactions.executeWithoutResult(status -> tasks.lockClaimable(0, 4, 10, 60_000L)
            .candidates().forEach(candidate -> {
                String token = "old-topology-" + candidate.id();
                assertThat(tasks.markProcessing(candidate.id(), token, 120_000L)).isEqualTo(1);
                tokens.put(candidate.id(), token);
            }));
        assertThat(tokens).hasSize(3);

        // 新拓扑（total=3）同刻领取：有效租约不可抢占，无双重有效领取
        Set<Long> newTopologyClaims = new HashSet<>();
        for (int shardIndex = 0; shardIndex < 3; shardIndex++) {
            newTopologyClaims.addAll(claimShardIds(shardIndex, 3, 10, DEFAULT_CROSS_SHARD_DELAY_MS));
        }
        assertThat(newTopologyClaims).containsExactlyInAnyOrderElementsOf(
            IntStream.rangeClosed(1, 12).filter(id -> id % 4 != 0).asLongStream().boxed().toList()
        );
        assertThat(newTopologyClaims.stream().noneMatch(tokens::containsKey)).isTrue();
        transactions.executeWithoutResult(status -> newTopologyClaims.forEach(id ->
            assertThat(tasks.markProcessing(id, "new-topology-" + id, 60_000L)).isEqualTo(1)));

        // 仅过期旧拓扑的在途任务；新拓扑持有的有效租约不可抢占
        jdbc.update(
            "update submission_timeout_task set lease_until=timestampadd(second,-1,current_timestamp(3)) where id in (4,8,12)"
        );

        Set<Long> recovered = new HashSet<>();
        for (int shardIndex = 0; shardIndex < 3; shardIndex++) {
            recovered.addAll(claimShardIds(shardIndex, 3, 10, DEFAULT_CROSS_SHARD_DELAY_MS));
        }
        assertThat(recovered).containsExactlyInAnyOrder(4L, 8L, 12L);
    }

    @Test
    void missingExpiredTaskIsRepairedByBoundedReconciliation() {
        LocalDateTime deadline = jdbc.queryForObject(
            "select timestampadd(second,-1,current_timestamp(3))",
            LocalDateTime.class
        );
        jdbc.update(
            """
                insert into exam_session(
                    id,exam_id,student_id,status,deadline_time,create_time,update_time
                ) values(1,10,20,'ANSWERING',?,current_timestamp(3),current_timestamp(3))
                """,
            deadline
        );
        jdbc.update(
            """
                insert into submission(
                    id,exam_id,student_id,status,timeout_submit,draft_version,create_time,update_time
                ) values(30,10,20,'IN_PROGRESS',0,0,current_timestamp(3),current_timestamp(3))
                """
        );

        assertThat(tasks.findReconcileCandidates(0, 1, 0L, 100)).hasSize(1);
        TimeoutSubmissionCoordinator coordinator = buildCoordinator(mock(ExamSnapshotService.class));
        assertThat(coordinator.processDue(0, 1)).isZero();

        assertThat(jdbc.queryForObject(
            "select count(*) from submission_timeout_task", Integer.class
        )).isEqualTo(1);
        assertThat(jdbc.queryForMap(
            "select status,submission_id from submission_timeout_task where session_id=1"
        )).containsEntry("status", "PENDING")
            .containsEntry("submission_id", 30L);
    }

    @Test
    void reconciliationDoesNotCreateTaskWhenFinalPayloadAlreadyExists() {
        LocalDateTime deadline = jdbc.queryForObject(
            "select timestampadd(second,-1,current_timestamp(3))",
            LocalDateTime.class
        );
        seedSession(1L, 10L, 20L, 30L, deadline);
        jdbc.update("delete from submission_timeout_task where id=1");
        jdbc.update(
            """
                insert into submission_final_payload(
                    submission_id,source,snapshot_version,codec,payload,payload_sha256,finalized_at,created_at
                ) values(30,'TIMEOUT',1,'GZIP_JSON_V1',?,?,current_timestamp(3),current_timestamp(3))
                """,
            new byte[] {1}, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        );

        assertThat(tasks.findReconcileCandidates(0, 1, 0L, 100)).isEmpty();
        TimeoutSubmissionCoordinator coordinator = buildCoordinator(mock(ExamSnapshotService.class));
        assertThat(coordinator.processDue(0, 1)).isZero();
        assertThat(jdbc.queryForObject(
            "select count(*) from submission_timeout_task", Integer.class
        )).isZero();
    }

    @Test
    void dueTaskCanBeAcknowledgedFromOneDatabaseTimeSnapshot() {
        LocalDateTime deadline = jdbc.queryForObject(
            "select timestampadd(second,-1,current_timestamp(3))",
            LocalDateTime.class
        );
        seedSession(1L, 10L, 20L, 30L, deadline);

        TimeoutTaskRepository.TimeoutHandoffState state = tasks.findHandoffState(1L);

        assertThat(state).isNotNull();
        assertThat(state.isDurablyDue(10L, 20L)).isTrue();
        assertThat(state.sessionStatus()).isEqualTo("ANSWERING");
        assertThat(state.taskStatus()).isEqualTo("PENDING");
    }

    @Test
    void timeoutFinalizationExcludesConcurrentManualSubmission() throws Exception {
        LocalDateTime deadline = jdbc.queryForObject(
            "select timestampadd(second,-1,current_timestamp(3))", LocalDateTime.class
        );
        seedSession(1L, 10L, 20L, 30L, deadline);
        ExamSnapshotService snapshots = mock(ExamSnapshotService.class);
        TimeoutSubmissionCoordinator coordinator = buildCoordinator(snapshots);
        CountDownLatch claimCommitted = new CountDownLatch(1);
        CountDownLatch releaseFinalization = new CountDownLatch(1);
        when(snapshots.loadLatestDraft(eq(10L), eq(20L), any())).thenAnswer(invocation -> {
            claimCommitted.countDown();
            releaseFinalization.await(5, TimeUnit.SECONDS);
            return new SnapshotDraft(Map.of(100L, "A"), 1L, LocalDateTime.now());
        });

        CompletableFuture<Integer> timeoutRun =
            CompletableFuture.supplyAsync(() -> coordinator.processDue(0, 1));
        assertThat(claimCommitted.await(5, TimeUnit.SECONDS)).isTrue();

        SubmitExamRequest request = request();
        ManualSubmissionService.ManualSubmissionResult manualResult = manualSubmissions.submit(
            1L, 10L, 20L, 30L, request, finalPayloads.encode(request.getAnswers(), 1)
        );
        assertThat(manualResult.status()).isEqualTo("SUBMITTING");

        releaseFinalization.countDown();
        assertThat(timeoutRun.get(5, TimeUnit.SECONDS)).isEqualTo(1);

        assertThat(finalPayloadAndEventCounts()).containsEntry("payload", 1)
            .containsEntry("event", 1);
        assertThat(jdbc.queryForObject(
            "select source from submission_final_payload where submission_id=30", String.class
        )).isEqualTo("TIMEOUT");
        assertThat(jdbc.queryForObject(
            "select status from exam_session where id=1", String.class
        )).isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject(
            "select status from submission_timeout_task where id=1", String.class
        )).isEqualTo("DONE");
    }

    @Test
    void manualFinalizationExcludesTimeoutReclaim() {
        LocalDateTime deadline = jdbc.queryForObject(
            "select timestampadd(minute,5,current_timestamp(3))", LocalDateTime.class
        );
        seedSession(1L, 10L, 20L, 30L, deadline);
        jdbc.update(
            "update submission_timeout_task set due_at=timestampadd(second,-1,current_timestamp(3)) where id=1"
        );

        SubmitExamRequest request = request();
        ManualSubmissionService.ManualSubmissionResult manualResult = manualSubmissions.submit(
            1L, 10L, 20L, 30L, request, finalPayloads.encode(request.getAnswers(), 1)
        );
        assertThat(manualResult.status()).isEqualTo("PROCESSING");

        assertThat(buildCoordinator(mock(ExamSnapshotService.class)).processDue(0, 1)).isZero();

        assertThat(finalPayloadAndEventCounts()).containsEntry("payload", 1)
            .containsEntry("event", 1);
        assertThat(jdbc.queryForObject(
            "select status from exam_session where id=1", String.class
        )).isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject(
            "select status from submission_timeout_task where id=1", String.class
        )).isEqualTo("DONE");
    }

    private LocalDateTime timestampaddSeconds(LocalDateTime base, int seconds) {
        return base.plusSeconds(seconds);
    }

    private List<Long> claimShardIds(int shardIndex, int shardTotal, int limit, long crossShardDelayMs) {
        return transactions.execute(status -> tasks
            .lockClaimable(shardIndex, shardTotal, limit, crossShardDelayMs)
            .candidates().stream().map(TimeoutTaskRepository.TaskCandidate::id).toList());
    }

    private CompletableFuture<Set<Long>> claimShardWithToken(int shardIndex, int shardTotal,
                                                             String token, CyclicBarrier barrier,
                                                             java.util.concurrent.ExecutorService pool) {
        return CompletableFuture.supplyAsync(() -> transactions.execute(status -> {
            Set<Long> claimed = new HashSet<>();
            tasks.lockClaimable(shardIndex, shardTotal, 10, DEFAULT_CROSS_SHARD_DELAY_MS)
                .candidates().forEach(candidate -> {
                    assertThat(tasks.markProcessing(candidate.id(), token, 60_000L)).isEqualTo(1);
                    claimed.add(candidate.id());
                });
            try {
                barrier.await(15, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
            return claimed;
        }), pool);
    }

    private TimeoutSubmissionCoordinator buildCoordinator(ExamSnapshotService snapshots) {
        TimeoutSubmissionProperties properties = new TimeoutSubmissionProperties();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(16);
        executor.initialize();
        ThreadPoolTaskScheduler leaseScheduler = new ThreadPoolTaskScheduler();
        leaseScheduler.setPoolSize(1);
        leaseScheduler.initialize();
        return new TimeoutSubmissionCoordinator(
            tasks, properties, new TimeoutSubmissionBackoffPolicy(properties),
            mock(TimeoutSubmissionMetrics.class), snapshots, finalPayloads, outbox,
            mock(SubmissionStatusProjectionService.class), jdbc, transactions, executor, leaseScheduler
        );
    }

    private Map<String, Integer> finalPayloadAndEventCounts() {
        Map<String, Integer> counts = new HashMap<>();
        counts.put("payload", jdbc.queryForObject(
            "select count(*) from submission_final_payload where submission_id=30", Integer.class
        ));
        counts.put("event", jdbc.queryForObject(
            "select count(*) from outbox_event where aggregate_id='30' and event_type='SubmissionAccepted'",
            Integer.class
        ));
        return counts;
    }

    private void seedShardTask(long id, String status, LocalDateTime dueAt,
                               LocalDateTime leaseUntil, LocalDateTime nextRetryAt) {
        jdbc.update(
            """
                insert into submission_timeout_task(
                    id,session_id,exam_id,student_id,due_at,status,claim_token,lease_until,
                    attempt_count,next_retry_at,created_at,updated_at
                ) values(?,?,?,?,?,?,?,?,0,?,current_timestamp(3),current_timestamp(3))
                """,
            id, id, 10L, 1000L + id, dueAt, status,
            leaseUntil == null ? null : "token-" + id, leaseUntil, nextRetryAt
        );
    }

    private CompletableFuture<Map<Long, String>> claimTen(String token, CyclicBarrier barrier,
                                                          java.util.concurrent.ExecutorService pool) {
        return CompletableFuture.supplyAsync(() -> transactions.execute(status -> {
            Map<Long, String> claimed = new HashMap<>();
            tasks.lockClaimable(0, 1, 10, DEFAULT_CROSS_SHARD_DELAY_MS).candidates().forEach(candidate -> {
                assertThat(tasks.markProcessing(candidate.id(), token, 60_000L)).isEqualTo(1);
                claimed.put(candidate.id(), token);
            });
            try {
                barrier.await(15, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
            return Map.copyOf(claimed);
        }), pool);
    }

    private void seedSession(Long sessionId, Long examId, Long studentId,
                             Long submissionId, LocalDateTime deadline) {
        jdbc.update(
            """
                insert into exam_session(
                    id,exam_id,student_id,status,deadline_time,active_client_id,
                    active_client_token,create_time,update_time
                ) values(?,?,?,'ANSWERING',?,'client-1','lease-1',current_timestamp(3),current_timestamp(3))
                """,
            sessionId, examId, studentId, deadline
        );
        jdbc.update(
            """
                insert into submission(
                    id,exam_id,student_id,status,timeout_submit,draft_version,create_time,update_time
                ) values(?,?,?,'IN_PROGRESS',0,0,current_timestamp(3),current_timestamp(3))
                """,
            submissionId, examId, studentId
        );
        tasks.ensureTask(sessionId, examId, studentId, deadline);
    }

    private SubmitExamRequest request() {
        AnswerPayload answer = new AnswerPayload();
        answer.setQuestionId(100L);
        answer.setAnswerText("A");
        SubmitExamRequest request = new SubmitExamRequest();
        request.setAnswers(List.of(answer));
        request.setClientId("client-1");
        request.setLeaseToken("lease-1");
        return request;
    }

    private void createSchema() {
        jdbc.execute("drop table if exists outbox_event");
        jdbc.execute("drop table if exists submission_final_payload");
        jdbc.execute("drop table if exists submission_timeout_task");
        jdbc.execute("drop table if exists submission");
        jdbc.execute("drop table if exists exam_session");
        jdbc.execute("""
            create table exam_session(
                id bigint primary key,exam_id bigint not null,student_id bigint not null,
                status varchar(32) not null,deadline_time datetime(3),end_time datetime(3),
                claim_time datetime(3),active_client_id varchar(128),active_client_token varchar(128),
                active_client_lease_until datetime(3),active_client_last_seen datetime(3),
                create_time datetime(3),update_time datetime(3),
                unique key uk_session(exam_id,student_id)
            )
            """);
        jdbc.execute("""
            create table submission(
                id bigint primary key,exam_id bigint not null,student_id bigint not null,
                status varchar(32) not null,submitted_at datetime(3),timeout_submit tinyint not null,
                draft_version bigint not null,create_time datetime(3),update_time datetime(3),
                unique key uk_submission(exam_id,student_id)
            )
            """);
        jdbc.execute("""
            create table submission_timeout_task(
                id bigint primary key,session_id bigint not null,exam_id bigint not null,
                student_id bigint not null,submission_id bigint null,due_at datetime(3) not null,
                status varchar(16) not null,
                claim_token varchar(36),lease_until datetime(3),attempt_count int not null default 0,
                next_retry_at datetime(3),last_error varchar(1000),created_at datetime(3),
                updated_at datetime(3),completed_at datetime(3),
                available_at datetime(3) generated always as (
                    case
                        when due_at is null then null
                        when next_retry_at is null or next_retry_at < due_at then due_at
                        else next_retry_at
                    end
                ) stored,
                unique key uk_task_session(session_id),
                key idx_due(status,due_at,next_retry_at,id),
                key idx_timeout_task_claim(status,available_at,id),key idx_lease(status,lease_until,id)
            )
            """);
        jdbc.execute("""
            create table submission_final_payload(
                submission_id bigint primary key,source varchar(16) not null,snapshot_version bigint not null,
                codec varchar(32) not null,payload mediumblob not null,payload_sha256 char(64) not null,
                finalized_at datetime(3),created_at datetime(3)
            )
            """);
        jdbc.execute("""
            create table outbox_event(
                id varchar(36) primary key,aggregate_type varchar(64) not null,
                aggregate_id varchar(64) not null,event_type varchar(128) not null,
                payload_json json not null,status varchar(16) not null,created_at datetime(3)
            )
            """);
    }

    private static String value(String property, String environment, String defaultValue) {
        String configured = System.getProperty(property);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(environment);
        }
        return configured == null || configured.isBlank() ? defaultValue : configured;
    }
}
