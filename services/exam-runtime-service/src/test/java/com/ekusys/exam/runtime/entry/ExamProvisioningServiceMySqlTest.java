package com.ekusys.exam.runtime.entry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.LongStream;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
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

class ExamProvisioningServiceMySqlTest {
    private static MySQLContainer mysql;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private JdbcTemplate jdbc;
    private PaperDeliveryCache paperCache;
    private ExamProvisioningService provisioning;

    @BeforeAll
    static void startDatabase() {
        try {
            mysql = new MySQLContainer("mysql:8.4")
                .withDatabaseName("entry_provisioning_test")
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
        Assumptions.assumeTrue(jdbcUrl != null, "Docker is required for provisioning verification");
        DataSource dataSource = new DriverManagerDataSource(jdbcUrl, username, password);
        Flyway flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(
            new DataSourceTransactionManager(dataSource)
        );
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(30);
        paperCache = mock(PaperDeliveryCache.class);
        provisioning = new ExamProvisioningService(
            jdbc, new RuntimeExamDefinitionRepository(jdbc), paperCache,
            new com.ekusys.exam.runtime.config.ExamEntryProperties(),
            new ExamEntryMetrics(new SimpleMeterRegistry()), transactions
        );
    }

    @Test
    void provisionsInBatchesAndDuplicateDeliveryDoesNotCreateDuplicateRows() {
        List<Long> candidates = LongStream.rangeClosed(1, 1_001).boxed().toList();
        ExamProvisioningCommand command = command(
            "8c08aa38-8aa5-4b5a-af2f-38fd61918582", 11L, candidates
        );

        ExamProvisioningView first = provisioning.provision(command);
        ExamProvisioningView replay = provisioning.provision(command);

        assertThat(first.status()).isEqualTo("READY");
        assertThat(replay.status()).isEqualTo("READY");
        assertThat(jdbc.queryForObject("select count(*) from exam_session", Integer.class)).isEqualTo(1_001);
        assertThat(jdbc.queryForObject("select count(*) from submission", Integer.class)).isEqualTo(1_001);
        assertThat(jdbc.queryForObject("select count(*) from submission_timeout_task", Integer.class)).isEqualTo(1_001);
        assertThat(jdbc.queryForObject("select count(*) from inbox_event", Integer.class)).isEqualTo(1);
    }

    @Test
    void terminationWinsOverLaterPublishedReplay() {
        List<Long> candidates = List.of(101L, 102L);
        provisioning.provision(command(
            "14793954-427d-464e-9e73-3ee8513b1925", 22L, candidates
        ));

        provisioning.terminate("03b6fc03-d7d2-40dc-a9de-924976e45f8b", 1, 22L);
        provisioning.provision(command(
            "6573f57c-0a58-4ea3-8510-f7341dfb5b84", 22L, candidates
        ));

        assertThat(jdbc.queryForObject(
            "select provisioning_status from runtime_exam_definition where exam_id=22",
            String.class
        )).isEqualTo("TERMINATED");
        assertThat(jdbc.queryForObject(
            "select count(*) from exam_session where exam_id=22 and status='CANCELLED'",
            Integer.class
        )).isEqualTo(2);
        assertThat(jdbc.queryForObject(
            "select count(*) from submission where exam_id=22 and status='CANCELLED'",
            Integer.class
        )).isEqualTo(2);
    }

    @Test
    void terminationAcceleratesAnsweringSessionIntoNormalTimeoutPipeline() {
        provisioning.provision(command(
            "9f15455f-091c-472c-bf26-54b720217880", 44L, List.of(101L)
        ));
        jdbc.update(
            """
                update exam_session
                   set status='ANSWERING',start_time=current_timestamp(3),
                       deadline_time=timestampadd(hour,1,current_timestamp(3))
                 where exam_id=44 and student_id=101
                """
        );
        jdbc.update(
            "update submission_timeout_task set status='PENDING',due_at=timestampadd(hour,1,current_timestamp(3)) where exam_id=44"
        );

        provisioning.terminate("8c30f7ee-80e5-4a12-a50f-f835199bbb06", 1, 44L);

        assertThat(jdbc.queryForObject(
            "select status from exam_session where exam_id=44 and student_id=101", String.class
        )).isEqualTo("ANSWERING");
        assertThat(jdbc.queryForObject(
            "select deadline_time<=current_timestamp(3) from exam_session where exam_id=44 and student_id=101",
            Boolean.class
        )).isTrue();
        assertThat(jdbc.queryForObject(
            "select status from submission_timeout_task where exam_id=44 and student_id=101", String.class
        )).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
            "select due_at<=current_timestamp(3) from submission_timeout_task where exam_id=44 and student_id=101",
            Boolean.class
        )).isTrue();
        assertThat(jdbc.queryForObject(
            "select status from submission where exam_id=44 and student_id=101", String.class
        )).isEqualTo("IN_PROGRESS");
    }

    @Test
    void paperPrewarmFailureKeepsExamUnavailableAndDoesNotAcknowledgeInbox() {
        doThrow(new IllegalStateException("content unavailable")).when(paperCache).prewarm(99L);
        ExamProvisioningCommand command = command(
            "9ac3d956-1240-43be-b419-c4f4b87ba556", 33L, List.of(101L)
        );

        assertThatThrownBy(() -> provisioning.provision(command))
            .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject(
            "select provisioning_status from runtime_exam_definition where exam_id=33",
            String.class
        )).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
            "select count(*) from inbox_event where event_id=?",
            Integer.class,
            command.eventId()
        )).isZero();
    }

    private ExamProvisioningCommand command(String eventId, Long examId, List<Long> candidates) {
        LocalDateTime start = LocalDateTime.now().plusMinutes(5);
        return new ExamProvisioningCommand(
            eventId, 2, examId, "万人考试", start, start.plusHours(2), 120, 60,
            99L, 3L, 7L, "STANDARD", null, "PUBLISHED", candidates
        );
    }
}
