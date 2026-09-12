package com.ekusys.exam.runtime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.ekusys.exam.common.outbox.OutboxEventWriter;
import com.ekusys.exam.exam.dto.AnswerPayload;
import com.ekusys.exam.exam.dto.SubmitExamRequest;
import com.ekusys.exam.runtime.messaging.RuntimeOutboxService;
import com.ekusys.exam.runtime.repository.TimeoutTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;

class TimeoutSubmissionV2MySqlTest {
    private static MySQLContainer mysql;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private TimeoutTaskRepository tasks;
    private SubmissionFinalPayloadService finalPayloads;
    private ManualSubmissionService manualSubmissions;

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
        RuntimeOutboxService outbox = new RuntimeOutboxService(
            jdbc, new OutboxEventWriter(jdbc, objectMapper)
        );
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
        CompletableFuture<Map<Long, String>> first = claimTen("claim-a", barrier);
        CompletableFuture<Map<Long, String>> second = claimTen("claim-b", barrier);
        Map<Long, String> firstClaims = first.join();
        Map<Long, String> secondClaims = second.join();

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
            List<TimeoutTaskRepository.TaskCandidate> candidates = tasks.lockClaimable(1);
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
        assertThat(tasks.markDone(expired.getKey(), recovered.get(expired.getKey()))).isEqualTo(1);
    }

    @Test
    void missingTaskReconciliationOnlyReportsAndDoesNotCreateTask() {
        LocalDateTime deadline = jdbc.queryForObject(
            "select timestampadd(minute,5,current_timestamp(3))",
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

        assertThat(tasks.missingTaskCount()).isEqualTo(1L);
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

    private CompletableFuture<Map<Long, String>> claimTen(String token, CyclicBarrier barrier) {
        return CompletableFuture.supplyAsync(() -> transactions.execute(status -> {
            Map<Long, String> claimed = new HashMap<>();
            tasks.lockClaimable(10).forEach(candidate -> {
                assertThat(tasks.markProcessing(candidate.id(), token, 60_000L)).isEqualTo(1);
                claimed.put(candidate.id(), token);
            });
            try {
                barrier.await();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
            return Map.copyOf(claimed);
        }));
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
