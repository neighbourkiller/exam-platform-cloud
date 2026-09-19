package com.ekusys.exam.runtime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;

import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.outbox.OutboxEventWriter;
import com.ekusys.exam.common.security.SecurityUtils;
import com.ekusys.exam.exam.dto.AnswerPayload;
import com.ekusys.exam.exam.dto.SnapshotRequest;
import com.ekusys.exam.runtime.entry.RuntimeExamDefinitionRepository;
import com.ekusys.exam.runtime.messaging.RuntimeOutboxService;
import com.ekusys.exam.runtime.repository.TimeoutTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;

class SnapshotDraftPayloadServiceMySqlTest {
    private static MySQLContainer mysql;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private SnapshotDraftPayloadService drafts;
    private RuntimeOutboxService outbox;
    private TimeoutSubmissionReplayService replays;
    private TimeoutTaskRepository timeoutTasks;

    @BeforeAll
    static void startDatabase() {
        try {
            mysql = new MySQLContainer("mysql:8.4")
                .withDatabaseName("snapshot_fence_test")
                .withUsername("test")
                .withPassword("test");
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
        if (mysql != null) mysql.stop();
    }

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(jdbcUrl != null, "Docker is required for snapshot fence verification");
        DataSource dataSource = new DriverManagerDataSource(jdbcUrl, username, password);
        Flyway flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(10);
        drafts = new SnapshotDraftPayloadService(
            jdbc, new ObjectMapper().findAndRegisterModules()
        );
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        outbox = new RuntimeOutboxService(jdbc, new OutboxEventWriter(jdbc, objectMapper));
        replays = new TimeoutSubmissionReplayService(
            jdbc, new RuntimeExamDefinitionRepository(jdbc), outbox
        );
        timeoutTasks = new TimeoutTaskRepository(jdbc);
        LocalDateTime deadline = jdbc.queryForObject(
            "select timestampadd(minute,5,current_timestamp(3))", LocalDateTime.class
        );
        jdbc.update(
            """
                insert into exam_session(
                    id,exam_id,student_id,status,deadline_time,active_client_id,
                    active_client_token,create_time,update_time
                ) values(1,10,20,'ANSWERING',?,'client-1','lease-1',current_timestamp(3),current_timestamp(3))
                """,
            deadline
        );
        jdbc.update(
            """
                insert into runtime_exam_definition(
                    exam_id,publisher_id,exam_status,provisioning_status
                ) values(10,99,'PUBLISHED','READY')
                """
        );
        jdbc.update(
            """
                insert into submission(
                    id,exam_id,student_id,status,timeout_submit,draft_version,create_time,update_time
                ) values(30,10,20,'IN_PROGRESS',0,0,current_timestamp(3),current_timestamp(3))
                """
        );
    }

    @Test
    void committedSnapshotWinsFenceAndIsVisibleToFinalizer() throws Exception {
        CountDownLatch snapshotLocked = new CountDownLatch(1);
        CountDownLatch allowSnapshotCommit = new CountDownLatch(1);

        CompletableFuture<SnapshotDraftPayloadService.Acceptance> accepted =
            CompletableFuture.supplyAsync(() -> transactions.execute(status -> {
                jdbc.queryForObject("select id from exam_session where id=1 for update", Long.class);
                snapshotLocked.countDown();
                await(allowSnapshotCommit);
                return drafts.accept(1L, 10L, 20L, request(1L, 0L, "A"));
            }));
        assertThat(snapshotLocked.await(3, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Integer> timeoutClaim = CompletableFuture.supplyAsync(() ->
            transactions.execute(status -> jdbc.update(
                "update exam_session set status='AUTO_SUBMITTING' where id=1 and status='ANSWERING'"
            ))
        );
        allowSnapshotCommit.countDown();

        assertThat(accepted.get(5, TimeUnit.SECONDS).accepted()).isTrue();
        assertThat(timeoutClaim.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        SnapshotDraft latest = drafts.loadLatest(10L, 20L);
        assertThat(latest.version()).isEqualTo(1L);
        assertThat(latest.answers()).containsEntry(100L, "A");
    }

    @Test
    void snapshotArrivingAfterFenceIsRejectedByDatabaseState() {
        jdbc.update("update exam_session set status='AUTO_SUBMITTING' where id=1");

        assertThatThrownBy(() -> transactions.execute(status ->
            drafts.accept(1L, 10L, 20L, request(1L, 0L, "late"))
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode()).isEqualTo(SnapshotDraftPayloadService.SESSION_ENDED_CODE)
        );
        assertThat(jdbc.queryForObject(
            "select count(*) from submission_draft_payload", Integer.class
        )).isZero();
    }

    @Test
    void sameClientSequenceIsIdempotentAndStaleBaseCannotOverwrite() {
        SnapshotDraftPayloadService.Acceptance first = transactions.execute(status ->
            drafts.accept(1L, 10L, 20L, request(7L, 0L, "A"))
        );
        SnapshotDraftPayloadService.Acceptance replay = transactions.execute(status ->
            drafts.accept(1L, 10L, 20L, request(7L, 1L, "A"))
        );
        SnapshotDraftPayloadService.Acceptance stale = transactions.execute(status ->
            drafts.accept(1L, 10L, 20L, request(8L, 0L, "B"))
        );

        assertThat(first.serverRevision()).isEqualTo(1L);
        assertThat(replay.serverRevision()).isEqualTo(1L);
        assertThat(stale.accepted()).isFalse();
        assertThat(stale.serverRevision()).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
            "select accepted_at from submission_draft_payload where submission_id=30", LocalDateTime.class
        )).isEqualTo(first.acceptedAt());
        assertThat(jdbc.queryForObject(
            "select server_revision from submission_draft_payload where submission_id=30", Long.class
        )).isEqualTo(1L);
    }

    @Test
    void lockWaitThatCrossesDeadlineIsRejectedAfterSessionLock() throws Exception {
        LocalDateTime deadline = jdbc.queryForObject(
            "select timestampadd(second,1,current_timestamp(3))", LocalDateTime.class
        );
        jdbc.update("update exam_session set deadline_time=? where id=1", deadline);
        CountDownLatch sessionLocked = new CountDownLatch(1);
        CountDownLatch releaseSession = new CountDownLatch(1);
        CountDownLatch acceptStarted = new CountDownLatch(1);

        CompletableFuture<Void> blocker = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("select id from exam_session where id=1 for update", Long.class);
            sessionLocked.countDown();
            await(releaseSession);
        }));
        assertThat(sessionLocked.await(3, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<SnapshotDraftPayloadService.Acceptance> blockedAccept =
            CompletableFuture.supplyAsync(() -> transactions.execute(status -> {
                acceptStarted.countDown();
                return drafts.accept(1L, 10L, 20L, request(1L, 0L, "after-lock-wait"));
            }));
        assertThat(acceptStarted.await(3, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(1_500L);
        releaseSession.countDown();
        blocker.get(5, TimeUnit.SECONDS);

        assertThatThrownBy(() -> blockedAccept.get(5, TimeUnit.SECONDS))
            .hasCauseInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject(
            "select count(*) from submission_draft_payload", Integer.class
        )).isZero();
    }

    @Test
    void publisherCanReplayOnlyFailedTaskWithoutFinalResult() {
        seedFailedTask();

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getCurrentRoles).thenReturn(List.of("TEACHER"));
            security.when(SecurityUtils::getCurrentUserId).thenReturn(99L);
            replays.replay(10L, 1L);
        }

        assertThat(jdbc.queryForObject(
            "select status from submission_timeout_task where id=1", String.class
        )).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
            "select replay_count from submission_timeout_task where id=1", Integer.class
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "select status from exam_session where id=1", String.class
        )).isEqualTo("AUTO_SUBMITTING");
        assertThatThrownBy(() -> withPublisher(() -> replays.replay(10L, 1L)))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void replayRejectsUnauthorizedTeacherAndExistingLogicalEvent() {
        seedFailedTask();
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getCurrentRoles).thenReturn(List.of("TEACHER"));
            security.when(SecurityUtils::getCurrentUserId).thenReturn(88L);
            assertThatThrownBy(() -> replays.replay(10L, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权限");
        }

        String eventId = outbox.submissionAcceptedEventId(30L);
        jdbc.update(
            """
                insert into outbox_event(
                    id,aggregate_type,aggregate_id,event_type,payload_json,status,created_at
                ) values(?,'SUBMISSION','30','SubmissionAccepted','{}','PENDING',current_timestamp(3))
                """,
            eventId
        );
        assertThatThrownBy(() -> withPublisher(() -> replays.replay(10L, 1L)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("最终结果");
    }

    @Test
    void maximumAttemptsAtomicallyCloseTaskAndSessionWithIncident() {
        jdbc.update("update exam_session set status='AUTO_SUBMITTING' where id=1");
        jdbc.update(
            """
                insert into submission_timeout_task(
                    id,session_id,exam_id,student_id,due_at,status,claim_token,lease_until,
                    attempt_count,created_at,updated_at
                ) values(
                    1,1,10,20,current_timestamp(3),'PROCESSING','token-1',
                    timestampadd(minute,1,current_timestamp(3)),12,current_timestamp(3),current_timestamp(3)
                )
                """
        );

        transactions.executeWithoutResult(status -> {
            assertThat(timeoutTasks.markFailure(
                1L, "token-1", LocalDateTime.now().plusMinutes(1), 12,
                "dependency timeout", "DEPENDENCY_TIMEOUT",
                "00000000-0000-0000-0000-000000000010"
            )).isEqualTo(1);
            assertThat(timeoutTasks.markSessionSubmissionFailed(1L)).isEqualTo(1);
        });

        assertThat(jdbc.queryForMap(
            "select status,failure_code,incident_id,replay_count from submission_timeout_task where id=1"
        )).containsEntry("status", "FAILED")
            .containsEntry("failure_code", "DEPENDENCY_TIMEOUT")
            .containsEntry("incident_id", "00000000-0000-0000-0000-000000000010")
            .containsEntry("replay_count", 0);
        assertThat(jdbc.queryForObject(
            "select status from exam_session where id=1", String.class
        )).isEqualTo("SUBMISSION_FAILED");
        assertThat(jdbc.queryForObject(
            "select active_client_token from exam_session where id=1", String.class
        )).isNull();
    }

    private SnapshotRequest request(long clientSequence, long baseServerRevision, String answerText) {
        AnswerPayload answer = new AnswerPayload();
        answer.setQuestionId(100L);
        answer.setAnswerText(answerText);
        SnapshotRequest request = new SnapshotRequest();
        request.setAnswers(List.of(answer));
        request.setClientId("client-1");
        request.setLeaseToken("lease-1");
        request.setClientSequence(clientSequence);
        request.setBaseServerRevision(baseServerRevision);
        request.setSnapshotVersion(clientSequence);
        request.setClientTimestamp(clientSequence);
        return request;
    }

    private void seedFailedTask() {
        jdbc.update("update exam_session set status='SUBMISSION_FAILED' where id=1");
        jdbc.update(
            """
                insert into submission_timeout_task(
                    id,session_id,exam_id,student_id,due_at,status,attempt_count,
                    failure_code,incident_id,failed_at,created_at,updated_at
                ) values(
                    1,1,10,20,current_timestamp(3),'FAILED',12,
                    'TIMEOUT_SUBMISSION_FAILED','00000000-0000-0000-0000-000000000001',
                    current_timestamp(3),current_timestamp(3),current_timestamp(3)
                )
                """
        );
    }

    private void withPublisher(Runnable action) {
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::getCurrentRoles).thenReturn(List.of("TEACHER"));
            security.when(SecurityUtils::getCurrentUserId).thenReturn(99L);
            action.run();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
