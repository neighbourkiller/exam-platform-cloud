package com.ekusys.exam.reporting.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

class ReportingSubmissionProjectionMySqlTest {
    private static MySQLContainer mysql;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private JdbcTemplate jdbc;
    private ReportingEventConsumer consumer;

    @BeforeAll
    static void startDatabase() {
        try {
            mysql = new MySQLContainer("mysql:8.4")
                .withDatabaseName("reporting_projection_test")
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
        Assumptions.assumeTrue(jdbcUrl != null, "Docker is required for reporting verification");
        DataSource dataSource = new DriverManagerDataSource(jdbcUrl, username, password);
        Flyway flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        consumer = new ReportingEventConsumer(jdbc, new ObjectMapper().findAndRegisterModules());
        jdbc.update(
            """
                insert into rpt_proctoring_student(
                    id,exam_id,student_id,session_status,submission_status,event_count,updated_at
                ) values(1,10,20,'SUBMITTED','PROCESSING',0,current_timestamp(3))
                """
        );
    }

    @Test
    void lateSubmissionAcceptedEnrichesButNeverDowngradesGradedProjection() throws Exception {
        consumer.consume("""
            {
              "eventId":"00000000-0000-0000-0000-000000000001",
              "eventType":"GradeCompleted",
              "data":{
                "submissionId":30,"examId":10,"studentId":20,"status":"GRADED",
                "objectiveScore":80,"subjectiveScore":10,"totalScore":90,"passFlag":true,
                "submittedAt":"2026-09-01T10:00:00","questionResults":[]
              }
            }
            """);
        consumer.consume("""
            {
              "eventId":"00000000-0000-0000-0000-000000000002",
              "eventType":"SubmissionAccepted","version":2,
              "data":{
                "submissionId":30,"examId":10,"studentId":20,"status":"PROCESSING",
                "submittedAt":"2026-09-01T10:00:00","timeoutSubmit":true,
                "submissionSource":"TIMEOUT","runtimeFinalizedAt":"2026-09-01T10:00:01",
                "finalSnapshotVersion":8,
                "payloadSha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
              }
            }
            """);

        assertThat(jdbc.queryForMap(
            "select status,timeout_submit,submission_source,final_snapshot_version from rpt_student_score where submission_id=30"
        )).containsEntry("status", "GRADED")
            .containsEntry("timeout_submit", 1)
            .containsEntry("submission_source", "TIMEOUT")
            .containsEntry("final_snapshot_version", 8L);
        assertThat(jdbc.queryForObject(
            "select submission_status from rpt_proctoring_student where exam_id=10 and student_id=20",
            String.class
        )).isEqualTo("GRADED");
    }

    @Test
    void versionOneSubmissionEventRemainsConsumable() throws Exception {
        consumer.consume("""
            {
              "eventId":"00000000-0000-0000-0000-000000000003",
              "eventType":"SubmissionAccepted","version":1,
              "data":{
                "submissionId":31,"examId":10,"studentId":21,"status":"PROCESSING",
                "submittedAt":"2026-09-01T10:00:00"
              }
            }
            """);

        assertThat(jdbc.queryForObject(
            "select status from rpt_student_score where submission_id=31", String.class
        )).isEqualTo("PROCESSING");
    }
}
