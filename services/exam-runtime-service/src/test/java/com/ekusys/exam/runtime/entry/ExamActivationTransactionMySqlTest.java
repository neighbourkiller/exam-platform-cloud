package com.ekusys.exam.runtime.entry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.ekusys.exam.common.outbox.OutboxEventWriter;
import com.ekusys.exam.runtime.config.ClientLeaseProperties;
import com.ekusys.exam.runtime.messaging.RuntimeOutboxService;
import com.ekusys.exam.runtime.service.ExamClientLeaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;

class ExamActivationTransactionMySqlTest {
    private static MySQLContainer mysql;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private JdbcTemplate jdbc;
    private ExamActivationTransactionService activation;

    @BeforeAll
    static void startDatabase() {
        try {
            mysql = new MySQLContainer("mysql:8.4")
                .withDatabaseName("entry_activation_test")
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
        if (mysql != null) {
            mysql.stop();
        }
    }

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(jdbcUrl != null, "Docker is required for MySQL concurrency verification");
        DataSource dataSource = new DriverManagerDataSource(jdbcUrl, username, password);
        jdbc = new JdbcTemplate(dataSource);
        recreateSchema();
        TransactionTemplate transactions = new TransactionTemplate(
            new DataSourceTransactionManager(dataSource)
        );
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(10);
        RuntimeExamDefinitionRepository definitions = new RuntimeExamDefinitionRepository(jdbc);
        ExamClientLeaseService leases = new ExamClientLeaseService(
            jdbc, mock(StringRedisTemplate.class), new ClientLeaseProperties()
        );
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        RuntimeOutboxService outbox = new RuntimeOutboxService(
            jdbc, new OutboxEventWriter(jdbc, mapper)
        );
        activation = new ExamActivationTransactionService(
            jdbc, definitions, leases, outbox,
            new ExamEntryMetrics(new SimpleMeterRegistry()), transactions
        );
    }

    @Test
    void oneHundredConcurrentRequestsActivateOneSessionAndOneSessionStartedEvent() throws Exception {
        insertPrepared(11L, 101L, 1_001L, 2_001L);
        RuntimeExamDefinition definition = definition(11L);
        var executor = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ExamActivationTransactionService.ActivationResult>> futures = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return activation.activate(definition, 101L, "client-101");
                }));
            }
            start.countDown();
            int newlyStarted = 0;
            for (Future<ExamActivationTransactionService.ActivationResult> future : futures) {
                if (future.get().newlyStarted()) {
                    newlyStarted++;
                }
            }

            assertThat(newlyStarted).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                "select status from exam_session where id=1001", String.class
            )).isEqualTo("ANSWERING");
            assertThat(jdbc.queryForObject(
                "select status from submission_timeout_task where session_id=1001", String.class
            )).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject(
                "select count(*) from outbox_event where event_type='SessionStarted'",
                Integer.class
            )).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from submission", Integer.class)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void differentStudentsActivateWithoutSharingRowLocks() throws Exception {
        int students = 40;
        for (int index = 0; index < students; index++) {
            insertPrepared(22L, 10_000L + index, 20_000L + index, 30_000L + index);
        }
        RuntimeExamDefinition definition = definition(22L);
        var executor = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ExamActivationTransactionService.ActivationResult>> futures = new ArrayList<>();
            for (int index = 0; index < students; index++) {
                long studentId = 10_000L + index;
                futures.add(executor.submit(() -> {
                    start.await();
                    return activation.activate(definition, studentId, "client-" + studentId);
                }));
            }
            start.countDown();
            for (Future<ExamActivationTransactionService.ActivationResult> future : futures) {
                assertThat(future.get().newlyStarted()).isTrue();
            }

            assertThat(jdbc.queryForObject(
                "select count(*) from exam_session where exam_id=22 and status='ANSWERING'",
                Integer.class
            )).isEqualTo(students);
            assertThat(jdbc.queryForObject(
                "select count(*) from outbox_event where event_type='SessionStarted'",
                Integer.class
            )).isEqualTo(students);
        } finally {
            executor.shutdownNow();
        }
    }

    private RuntimeExamDefinition definition(Long examId) {
        LocalDateTime now = LocalDateTime.now();
        return new RuntimeExamDefinition(
            examId, "并发考试", now.minusMinutes(1), now.plusHours(2), 60, 60,
            99L, 1L, 7L, "STANDARD", null, "PUBLISHED", "READY", 100, 100
        );
    }

    private void insertPrepared(Long examId, Long studentId, Long sessionId, Long submissionId) {
        jdbc.update(
            """
                insert into exam_session(id,exam_id,student_id,status,create_time,update_time)
                values(?,?,?,'PREPARED',current_timestamp(3),current_timestamp(3))
                """,
            sessionId, examId, studentId
        );
        jdbc.update(
            """
                insert into submission(id,exam_id,student_id,status,draft_version)
                values(?,?,?,'IN_PROGRESS',0)
                """,
            submissionId, examId, studentId
        );
        jdbc.update(
            """
                insert into submission_timeout_task(id,session_id,exam_id,student_id,status)
                values(?,?,?,?, 'WAITING')
                """,
            sessionId, sessionId, examId, studentId
        );
    }

    private void recreateSchema() {
        jdbc.execute("drop table if exists outbox_event");
        jdbc.execute("drop table if exists submission_timeout_task");
        jdbc.execute("drop table if exists submission");
        jdbc.execute("drop table if exists exam_session");
        jdbc.execute("""
            create table exam_session(
                id bigint primary key,exam_id bigint not null,student_id bigint not null,
                status varchar(32) not null,start_time datetime(3),deadline_time datetime(3),
                active_client_id varchar(128),active_client_token varchar(128),
                active_client_lease_until datetime(3),active_client_last_seen datetime(3),
                claim_time datetime(3),create_time datetime(3),update_time datetime(3),
                unique key uk_exam_student(exam_id,student_id)
            )
            """);
        jdbc.execute("""
            create table submission(
                id bigint primary key,exam_id bigint not null,student_id bigint not null,
                status varchar(32) not null,draft_version bigint not null default 0,
                unique key uk_submission_exam_student(exam_id,student_id)
            )
            """);
        jdbc.execute("""
            create table submission_timeout_task(
                id bigint primary key,session_id bigint not null,exam_id bigint not null,
                student_id bigint not null,due_at datetime(3),status varchar(16) not null,
                next_retry_at datetime(3),last_error varchar(1000),updated_at datetime(3),
                unique key uk_task_session(session_id)
            )
            """);
        jdbc.execute("""
            create table outbox_event(
                id varchar(36) primary key,aggregate_type varchar(64) not null,
                aggregate_id varchar(64) not null,event_type varchar(128) not null,
                payload_json json not null,status varchar(16) not null,retry_count int default 0,
                next_retry_time datetime(3),created_at datetime(3) not null,published_at datetime(3)
            )
            """);
    }
}
