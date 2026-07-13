package com.ekusys.exam.runtime.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ekusys.exam.exam.dto.AnswerPayload;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.mysql.MySQLContainer;

class SnapshotPersistenceServiceMySqlTest {
    private static MySQLContainer mysql;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private SnapshotPersistenceService first;
    private SnapshotPersistenceService second;

    @BeforeAll
    static void startDatabase() {
        jdbcUrl = value("snapshot.test.jdbc-url", "SNAPSHOT_TEST_JDBC_URL", null);
        username = value("snapshot.test.username", "SNAPSHOT_TEST_USERNAME", "test");
        password = value("snapshot.test.password", "SNAPSHOT_TEST_PASSWORD", "test");
        if (jdbcUrl != null) {
            return;
        }
        try {
            mysql = new MySQLContainer("mysql:8.4")
                .withDatabaseName("snapshot_test")
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
        if (mysql != null) {
            mysql.stop();
        }
    }

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(jdbcUrl != null, "Docker or external MySQL is required");
        DataSource dataSource = new DriverManagerDataSource(jdbcUrl, username, password);
        context = new AnnotationConfigApplicationContext();
        context.register(TransactionConfig.class);
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource));
        context.registerBean(
            PlatformTransactionManager.class,
            () -> new DataSourceTransactionManager(dataSource)
        );
        context.registerBean(SnapshotPersistenceService.class);
        context.refresh();
        jdbc = context.getBean(JdbcTemplate.class);
        first = context.getBean(SnapshotPersistenceService.class);
        second = context.getBean(SnapshotPersistenceService.class);
        createSchema();
    }

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void concurrentInstancesKeepHighestDraftVersionAndItsAnswers() {
        jdbc.update("insert into submission(id,exam_id,student_id,status,draft_version) values(1,10,20,'IN_PROGRESS',0)");

        CompletableFuture<Long> older = CompletableFuture.supplyAsync(
            () -> first.persistDraft(10L, 20L, List.of(answer("old")), 100L)
        );
        CompletableFuture<Long> newer = CompletableFuture.supplyAsync(
            () -> second.persistDraft(10L, 20L, List.of(answer("new")), 101L)
        );
        CompletableFuture.allOf(older, newer).join();

        assertThat(jdbc.queryForObject(
            "select draft_version from submission where id=1", Long.class
        )).isEqualTo(101L);
        assertThat(jdbc.queryForList(
            "select answer_text from submission_answer where submission_id=1", String.class
        )).containsExactly("new");
    }

    private void createSchema() {
        jdbc.execute("drop table if exists submission_answer");
        jdbc.execute("drop table if exists submission");
        jdbc.execute("""
            create table submission(
                id bigint primary key,
                exam_id bigint not null,
                student_id bigint not null,
                status varchar(32) not null,
                draft_version bigint not null default 0,
                update_time datetime(3),
                unique key uk_submission_exam_student(exam_id,student_id)
            )
            """);
        jdbc.execute("""
            create table submission_answer(
                id bigint primary key,
                submission_id bigint not null,
                question_id bigint not null,
                answer_text text,
                final_answer tinyint not null default 0,
                source varchar(32),
                create_time datetime(3),
                update_time datetime(3),
                unique key uk_submission_answer(submission_id,question_id)
            )
            """);
    }

    private AnswerPayload answer(String text) {
        AnswerPayload answer = new AnswerPayload();
        answer.setQuestionId(11L);
        answer.setAnswerText(text);
        return answer;
    }

    private static String value(String property, String environment, String defaultValue) {
        String configured = System.getProperty(property);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(environment);
        }
        return configured == null || configured.isBlank() ? defaultValue : configured;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionConfig {
    }
}
