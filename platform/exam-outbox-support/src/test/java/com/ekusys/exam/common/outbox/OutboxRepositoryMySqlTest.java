package com.ekusys.exam.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

class OutboxRepositoryMySqlTest {

    private static MySQLContainer mysql;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private JdbcTemplate jdbc;
    private OutboxProperties properties;
    private OutboxRepository firstRepository;
    private OutboxRepository secondRepository;

    @BeforeAll
    static void startDatabase() {
        jdbcUrl = value("outbox.test.jdbc-url", "OUTBOX_TEST_JDBC_URL", null);
        username = value("outbox.test.username", "OUTBOX_TEST_USERNAME", "test");
        password = value("outbox.test.password", "OUTBOX_TEST_PASSWORD", "test");
        if (jdbcUrl != null && !jdbcUrl.isBlank()) {
            return;
        }
        try {
            mysql = new MySQLContainer("mysql:8.4")
                .withDatabaseName("outbox_test")
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
        DriverManagerDataSource dataSource = new DriverManagerDataSource(jdbcUrl, username, password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop table if exists outbox_event");
        jdbc.execute("""
            create table outbox_event(
                id varchar(36) primary key,
                aggregate_type varchar(64) not null,
                aggregate_id varchar(64) not null,
                event_type varchar(128) not null,
                payload_json json not null,
                status varchar(16) not null default 'PENDING',
                retry_count int not null default 0,
                next_retry_time datetime(3),
                lease_token varchar(36),
                lease_until datetime(3),
                last_error varchar(1000),
                failed_at datetime(3),
                created_at datetime(3) not null,
                published_at datetime(3),
                key idx_outbox_pending(status,next_retry_time,created_at,id),
                key idx_outbox_lease(status,lease_until,id),
                key idx_outbox_cleanup(status,published_at,id)
            )
            """);
        properties = new OutboxProperties();
        properties.setBatchSize(1);
        properties.setBackoffJitter(0);
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
        OutboxBackoffPolicy backoff = new OutboxBackoffPolicy(properties, () -> 0.5);
        firstRepository = new OutboxRepository(jdbc, properties, backoff, transactions);
        secondRepository = new OutboxRepository(jdbc, properties, backoff, transactions);
    }

    @Test
    void concurrentPublishersClaimEventOnlyOnce() {
        insert("evt-1", "PENDING", 0, null, null);

        CompletableFuture<OutboxClaimBatch> first = CompletableFuture.supplyAsync(firstRepository::claimBatch);
        CompletableFuture<OutboxClaimBatch> second = CompletableFuture.supplyAsync(secondRepository::claimBatch);
        List<OutboxRow> claimed = CompletableFuture.allOf(first, second)
            .thenApply(ignored -> java.util.stream.Stream.concat(
                first.join().rows().stream(), second.join().rows().stream()
            ).toList())
            .join();

        assertThat(claimed).extracting(OutboxRow::id).containsExactly("evt-1");
        assertThat(jdbc.queryForObject(
            "select status from outbox_event where id='evt-1'", String.class
        )).isEqualTo("SENDING");
    }

    @Test
    void expiredFinalLeaseMovesToFailed() {
        properties.setMaxAttempts(12);
        insert("evt-1", "SENDING", 11, "old-lease", "current_timestamp(3)-interval 1 second");

        OutboxClaimBatch batch = firstRepository.claimBatch();

        assertThat(batch.recoveredAsFailed()).isEqualTo(1);
        assertThat(batch.rows()).isEmpty();
        assertThat(jdbc.queryForObject(
            "select status from outbox_event where id='evt-1'", String.class
        )).isEqualTo("FAILED");
    }

    @Test
    void staleLeaseCannotMarkPublished() {
        insert("evt-1", "SENDING", 0, "current-lease", "current_timestamp(3)+interval 1 minute");

        boolean updated = firstRepository.markPublished(
            new OutboxRow("evt-1", "TestEvent", "{}", 0, "stale-lease")
        );

        assertThat(updated).isFalse();
        assertThat(jdbc.queryForObject(
            "select status from outbox_event where id='evt-1'", String.class
        )).isEqualTo("SENDING");
    }

    @Test
    void acknowledgedRowsAreMarkedPublishedInOneBatchTransaction() {
        insert("evt-1", "SENDING", 0, "lease-1", "current_timestamp(3)+interval 1 minute");
        insert("evt-2", "SENDING", 0, "lease-2", "current_timestamp(3)+interval 1 minute");

        int updated = firstRepository.markPublishedBatch(List.of(
            new OutboxRow("evt-1", "TestEvent", "{}", 0, "lease-1"),
            new OutboxRow("evt-2", "TestEvent", "{}", 0, "stale-lease")
        ));

        assertThat(updated).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "select status from outbox_event where id='evt-1'", String.class
        )).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject(
            "select status from outbox_event where id='evt-2'", String.class
        )).isEqualTo("SENDING");
    }

    @Test
    void cleanupDeletesOnlyExpiredPublishedEvents() {
        properties.setPublishedRetentionMs(1_000);
        insert("old-published", "PUBLISHED", 0, null, null);
        jdbc.update("update outbox_event set published_at=current_timestamp(3)-interval 10 second where id='old-published'");
        insert("recent-published", "PUBLISHED", 0, null, null);
        jdbc.update("update outbox_event set published_at=current_timestamp(3) where id='recent-published'");
        insert("failed", "FAILED", 12, null, null);

        int deleted = firstRepository.cleanupPublished();

        assertThat(deleted).isEqualTo(1);
        assertThat(jdbc.queryForList("select id from outbox_event order by id", String.class))
            .containsExactly("failed", "recent-published");
    }

    private void insert(String id, String status, int retryCount, String leaseToken, String leaseUntilExpression) {
        String leaseUntil = leaseUntilExpression == null ? "null" : leaseUntilExpression;
        jdbc.update(
            "insert into outbox_event(id,aggregate_type,aggregate_id,event_type,payload_json,status,retry_count,"
                + "lease_token,lease_until,created_at) values(?,?,?,?,?,?,?,?," + leaseUntil
                + ",current_timestamp(3))",
            id, "TEST", "aggregate", "TestEvent", "{}", status, retryCount, leaseToken
        );
    }

    private static String value(String property, String environment, String defaultValue) {
        String configured = System.getProperty(property);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(environment);
        }
        return configured == null || configured.isBlank() ? defaultValue : configured;
    }
}
