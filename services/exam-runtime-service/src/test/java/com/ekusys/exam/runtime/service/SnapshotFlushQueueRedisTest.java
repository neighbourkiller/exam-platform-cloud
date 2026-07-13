package com.ekusys.exam.runtime.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

class SnapshotFlushQueueRedisTest {
    private static GenericContainer<?> redisContainer;
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private final AtomicLong now = new AtomicLong(1_000L);
    private SnapshotPropertiesFixture fixture;
    private SnapshotFlushQueue first;
    private SnapshotFlushQueue second;

    @BeforeAll
    static void startRedis() {
        String host = value("snapshot.test.redis.host", "SNAPSHOT_TEST_REDIS_HOST", null);
        int port = Integer.parseInt(value("snapshot.test.redis.port", "SNAPSHOT_TEST_REDIS_PORT", "6379"));
        int database = Integer.parseInt(value("snapshot.test.redis.database", "SNAPSHOT_TEST_REDIS_DATABASE", "0"));
        if (host == null) {
            try {
                redisContainer = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
                redisContainer.start();
                host = redisContainer.getHost();
                port = redisContainer.getMappedPort(6379);
            } catch (RuntimeException exception) {
                host = null;
            }
        }
        if (host == null) {
            return;
        }
        connectionFactory = new LettuceConnectionFactory(host, port);
        connectionFactory.setDatabase(database);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (redisContainer != null) {
            redisContainer.stop();
        }
    }

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(redis != null, "Docker or external Redis is required");
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        fixture = new SnapshotPropertiesFixture();
        first = new SnapshotFlushQueue(redis, fixture.properties, now::get);
        second = new SnapshotFlushQueue(redis, fixture.properties, now::get);
    }

    @Test
    void saveCoalescesDirtyDeadlineAndKeepsLatestVersion() {
        assertThat(first.save(1L, 2L, 100L, payload(100), 3_600_000L)).isEqualTo(100L);
        Double firstDeadline = redis.opsForZSet().score(SnapshotFlushQueue.DIRTY_KEY, "1:2");

        now.set(2_000L);
        assertThat(first.save(1L, 2L, 101L, payload(101), 3_600_000L)).isEqualTo(101L);

        assertThat(redis.opsForValue().get("exam:snapshot-version:1:2")).isEqualTo("101");
        assertThat(redis.opsForValue().get("exam:snapshot:1:2")).contains("101");
        assertThat(redis.opsForZSet().score(SnapshotFlushQueue.DIRTY_KEY, "1:2"))
            .isEqualTo(firstDeadline);
    }

    @Test
    void concurrentInstancesClaimMemberOnlyOnce() {
        first.save(1L, 2L, 100L, payload(100), 3_600_000L);
        now.set(40_000L);

        CompletableFuture<SnapshotFlushClaimBatch> one = CompletableFuture.supplyAsync(first::claimBatch);
        CompletableFuture<SnapshotFlushClaimBatch> two = CompletableFuture.supplyAsync(second::claimBatch);
        List<SnapshotFlushClaim> claims = CompletableFuture.allOf(one, two)
            .thenApply(ignored -> java.util.stream.Stream.concat(
                one.join().claims().stream(), two.join().claims().stream()
            ).toList())
            .join();

        assertThat(claims).extracting(SnapshotFlushClaim::member).containsExactly("1:2");
    }

    @Test
    void expiredLeaseIsRecoveredAndOldTokenCannotAcknowledge() {
        first.save(1L, 2L, 100L, payload(100), 3_600_000L);
        now.set(40_000L);
        SnapshotFlushClaim oldClaim = first.claimBatch().claims().getFirst();

        now.set(40_000L + fixture.properties.safeFlushLeaseMs() + 1L);
        SnapshotFlushClaimBatch recovered = second.claimBatch();
        SnapshotFlushClaim newClaim = recovered.claims().getFirst();

        assertThat(recovered.recoveredLeases()).isEqualTo(1);
        assertThat(first.acknowledge(oldClaim, 100L)).isZero();
        assertThat(second.acknowledge(newClaim, 100L)).isEqualTo(1L);
    }

    @Test
    void oldWorkerCannotDeleteNewerSnapshot() {
        first.save(1L, 2L, 100L, payload(100), 3_600_000L);
        now.set(40_000L);
        SnapshotFlushClaim oldClaim = first.claimBatch().claims().getFirst();

        first.save(1L, 2L, 101L, payload(101), 3_600_000L);

        assertThat(first.acknowledge(oldClaim, 100L)).isEqualTo(2L);
        assertThat(redis.opsForValue().get("exam:snapshot:1:2")).contains("101");
        assertThat(redis.opsForZSet().score(SnapshotFlushQueue.DIRTY_KEY, "1:2")).isNotNull();
    }

    @Test
    void transientFailureExtendsTtlAndSchedulesRetry() {
        first.save(1L, 2L, 100L, payload(100), 60_000L);
        now.set(40_000L);
        SnapshotFlushClaim claim = first.claimBatch().claims().getFirst();

        SnapshotFailureResult result = first.markFailure(
            claim, 100L, new IllegalStateException("mysql offline"),
            SnapshotFailureMode.TRANSIENT, 5_000L
        );

        assertThat(result.updated()).isTrue();
        assertThat(result.quarantined()).isFalse();
        assertThat(redis.getExpire("exam:snapshot:1:2", TimeUnit.HOURS)).isGreaterThan(6L * 24L);
        assertThat(redis.opsForZSet().score(SnapshotFlushQueue.DIRTY_KEY, "1:2"))
            .isEqualTo(45_000D);
    }

    @Test
    void poisonIsQuarantinedAndNewVersionReleasesIt() {
        first.save(1L, 2L, 100L, payload(100), 60_000L);
        now.set(40_000L);
        SnapshotFlushClaim claim = first.claimBatch().claims().getFirst();

        SnapshotFailureResult result = first.markFailure(
            claim, 100L, new IllegalArgumentException("bad payload"),
            SnapshotFailureMode.POISON, 0L
        );

        assertThat(result.quarantined()).isTrue();
        assertThat(first.backlog().failed()).isEqualTo(1);
        first.save(1L, 2L, 101L, payload(101), 60_000L);
        assertThat(first.backlog().failed()).isZero();
    }

    @Test
    void retryableFailureIsQuarantinedAtConfiguredLimit() {
        first.save(1L, 2L, 100L, payload(100), 60_000L);
        now.set(40_000L);
        SnapshotFlushClaim claim = first.claimBatch().claims().getFirst();
        redis.opsForHash().put(
            SnapshotFlushQueue.ATTEMPTS_KEY, "1:2",
            String.valueOf(fixture.properties.safeFlushMaxAttempts() - 1)
        );

        SnapshotFailureResult result = first.markFailure(
            claim, 100L, new IllegalStateException("unexpected"),
            SnapshotFailureMode.RETRYABLE, 5_000L
        );

        assertThat(result.quarantined()).isTrue();
        assertThat(result.attempts()).isEqualTo(fixture.properties.safeFlushMaxAttempts());
    }

    @Test
    void cleanupRemovesOnlyExpiredQuarantinedVersion() {
        first.save(1L, 2L, 100L, payload(100), 60_000L);
        now.set(40_000L);
        SnapshotFlushClaim claim = first.claimBatch().claims().getFirst();
        first.markFailure(
            claim, 100L, new IllegalArgumentException("bad payload"),
            SnapshotFailureMode.POISON, 0L
        );
        now.addAndGet(fixture.properties.safeFlushFailedRetentionMs() + 1L);

        int cleaned = first.cleanupFailed();

        assertThat(cleaned).isEqualTo(1);
        assertThat(redis.opsForValue().get("exam:snapshot:1:2")).isNull();
        assertThat(redis.opsForValue().get("exam:snapshot-version:1:2")).isNull();
        assertThat(first.backlog().failed()).isZero();
    }

    @Test
    void reconciliationIndexesLegacyPayloadWithoutFullMaterialization() {
        redis.opsForValue().set("exam:snapshot:7:8", payload(200));
        redis.opsForValue().set("exam:snapshot-version:7:8", "200");

        int added = first.reconcilePage();

        assertThat(added).isEqualTo(1);
        assertThat(redis.opsForZSet().score(SnapshotFlushQueue.DIRTY_KEY, "7:8")).isNotNull();
    }

    @Test
    void persistedDirtyStateSurvivesRedisRestart() throws Exception {
        Assumptions.assumeTrue(redisContainer != null, "Container-managed Redis is required");
        first.save(1L, 2L, 100L, payload(100), 3_600_000L);
        redis.getConnectionFactory().getConnection().serverCommands().save();

        redisContainer.getDockerClient()
            .restartContainerCmd(redisContainer.getContainerId())
            .exec();
        awaitRedis();
        now.set(40_000L);

        assertThat(first.claimBatch().claims())
            .extracting(SnapshotFlushClaim::member)
            .containsExactly("1:2");
    }

    private String payload(long version) {
        return "{\"snapshotVersion\":" + version + "}";
    }

    private void awaitRedis() throws Exception {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                if ("PONG".equals(redis.getConnectionFactory().getConnection().ping())) {
                    return;
                }
            } catch (RuntimeException exception) {
                lastFailure = exception;
            }
            TimeUnit.MILLISECONDS.sleep(100L);
        }
        throw new IllegalStateException("Redis did not recover after restart", lastFailure);
    }

    private static String value(String property, String environment, String defaultValue) {
        String configured = System.getProperty(property);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(environment);
        }
        return configured == null || configured.isBlank() ? defaultValue : configured;
    }

    private static final class SnapshotPropertiesFixture {
        private final com.ekusys.exam.runtime.config.SnapshotProperties properties =
            new com.ekusys.exam.runtime.config.SnapshotProperties();

        private SnapshotPropertiesFixture() {
            properties.setFlushBatchSize(1);
            properties.setFlushBackoffJitter(0);
        }
    }
}
