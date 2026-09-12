package com.ekusys.exam.common.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.outbox", name = "enabled", matchIfMissing = true)
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final OutboxProperties properties;
    private final Executor executor;
    private final MeterRegistry meterRegistry;

    public OutboxPublisher(OutboxRepository repository, RabbitTemplate rabbitTemplate,
                           OutboxProperties properties,
                           @Qualifier("outboxPublisherExecutor") Executor executor,
                           MeterRegistry meterRegistry) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.executor = executor;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void requireReturnedMessages() {
        rabbitTemplate.setMandatory(true);
    }

    @Scheduled(
        fixedDelayString = "${app.outbox.publish-delay-ms:1000}",
        scheduler = "outboxTaskScheduler"
    )
    public void publishPending() {
        OutboxClaimBatch batch = repository.claimBatch();
        increment("lease_recovered", batch.recoveredForRetry());
        increment("lease_failed", batch.recoveredAsFailed());
        increment("claimed", batch.rows().size());
        List<CompletableFuture<PublishAttempt>> tasks = batch.rows().stream()
            .map(row -> CompletableFuture.supplyAsync(() -> publish(row), executor))
            .toList();
        List<PublishAttempt> attempts = tasks.stream().map(CompletableFuture::join).toList();
        List<OutboxRow> acknowledged = attempts.stream()
            .filter(PublishAttempt::acknowledged)
            .map(PublishAttempt::row)
            .toList();
        int published = acknowledged.isEmpty() ? 0 : repository.markPublishedBatch(acknowledged);
        increment("published", published);
        int stale = acknowledged.size() - published;
        increment("stale_update", stale);
        if (stale > 0) {
            log.warn("Ignore {} stale Outbox publish results after batch confirmation", stale);
        }
        attempts.stream()
            .filter(attempt -> !attempt.acknowledged())
            .forEach(this::recordFailure);
    }

    @Scheduled(
        fixedDelayString = "${app.outbox.cleanup-delay-ms:3600000}",
        initialDelayString = "${app.outbox.cleanup-delay-ms:3600000}",
        scheduler = "outboxTaskScheduler"
    )
    public void cleanupPublished() {
        int deleted = 0;
        for (int batch = 0; batch < properties.safeCleanupMaxBatches(); batch++) {
            int current = repository.cleanupPublished();
            deleted += current;
            if (current < properties.safeCleanupBatchSize()) {
                break;
            }
        }
        increment("cleanup_deleted", deleted);
    }

    private PublishAttempt publish(OutboxRow row) {
        try {
            CorrelationData correlation = new CorrelationData(row.id());
            rabbitTemplate.convertAndSend(
                properties.getExchange(), row.eventType(), row.payload(), correlation
            );
            CorrelationData.Confirm confirm = correlation.getFuture().get(
                properties.safeConfirmTimeoutMs(), TimeUnit.MILLISECONDS
            );
            if (!confirm.ack()) {
                throw new IllegalStateException("RabbitMQ rejected event: " + confirm.reason());
            }
            if (correlation.getReturned() != null) {
                throw new IllegalStateException("RabbitMQ returned event: " + correlation.getReturned());
            }
            return new PublishAttempt(row, null);
        } catch (Exception exception) {
            return new PublishAttempt(row, exception);
        }
    }

    private void recordFailure(PublishAttempt attempt) {
        OutboxRow row = attempt.row();
        Exception exception = attempt.failure();
        OutboxFailureResult result = repository.markFailedAttempt(row, exception);
        if (!result.updated()) {
            increment("stale_update", 1);
            log.warn("Ignore stale Outbox failure result: eventId={}", row.id());
        } else if (result.failedPermanently()) {
            increment("failed", 1);
            log.error("Outbox event reached max attempts: eventId={}, eventType={}, attempts={}",
                row.id(), row.eventType(), result.failureCount());
        } else {
            increment("retry", 1);
            log.warn("Outbox publish failed, retry scheduled: eventId={}, eventType={}, attempts={}, reason={}",
                row.id(), row.eventType(), result.failureCount(), exception.getMessage());
        }
    }

    private void increment(String outcome, int amount) {
        if (amount > 0) {
            meterRegistry.counter("exam.outbox.events", "outcome", outcome).increment(amount);
        }
    }

    private record PublishAttempt(OutboxRow row, Exception failure) {
        private boolean acknowledged() {
            return failure == null;
        }
    }
}
