package com.ekusys.exam.common.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class OutboxPublisherTest {

    @Test
    void ackWithoutReturnMarksPublished() {
        Fixture fixture = fixture();
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(fixture.rabbit).convertAndSend(
            eq("exam.events"), eq("TestEvent"), eq("{}"), any(CorrelationData.class)
        );
        when(fixture.repository.markPublishedBatch(List.of(fixture.row))).thenReturn(1);

        fixture.publisher.publishPending();

        verify(fixture.repository).markPublishedBatch(List.of(fixture.row));
        verify(fixture.repository, never()).markFailedAttempt(eq(fixture.row), any());
    }

    @Test
    void returnedMessageIsRetriedInsteadOfMarkedPublished() {
        Fixture fixture = fixture();
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.setReturned(new ReturnedMessage(
                new Message(new byte[0]), 312, "NO_ROUTE", "exam.events", "TestEvent"
            ));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(fixture.rabbit).convertAndSend(
            eq("exam.events"), eq("TestEvent"), eq("{}"), any(CorrelationData.class)
        );
        when(fixture.repository.markFailedAttempt(eq(fixture.row), any()))
            .thenReturn(new OutboxFailureResult(true, false, 1));

        fixture.publisher.publishPending();

        verify(fixture.repository).markFailedAttempt(eq(fixture.row), any(IllegalStateException.class));
        verify(fixture.repository, never()).markPublishedBatch(any());
    }

    @Test
    void nackAtMaximumAttemptsMovesToFailed() {
        Fixture fixture = fixture();
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(false, "rejected"));
            return null;
        }).when(fixture.rabbit).convertAndSend(
            eq("exam.events"), eq("TestEvent"), eq("{}"), any(CorrelationData.class)
        );
        when(fixture.repository.markFailedAttempt(eq(fixture.row), any()))
            .thenReturn(new OutboxFailureResult(true, true, 12));

        fixture.publisher.publishPending();

        verify(fixture.repository).markFailedAttempt(eq(fixture.row), any(IllegalStateException.class));
    }

    private Fixture fixture() {
        OutboxRepository repository = mock(OutboxRepository.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        OutboxProperties properties = new OutboxProperties();
        Executor directExecutor = Runnable::run;
        OutboxRow row = new OutboxRow("evt-1", "TestEvent", "{}", 0, "lease-1");
        when(repository.claimBatch()).thenReturn(new OutboxClaimBatch(List.of(row), 0, 0));
        OutboxPublisher publisher = new OutboxPublisher(
            repository, rabbit, properties, directExecutor, new SimpleMeterRegistry()
        );
        publisher.requireReturnedMessages();
        return new Fixture(repository, rabbit, publisher, row);
    }

    private record Fixture(
        OutboxRepository repository,
        RabbitTemplate rabbit,
        OutboxPublisher publisher,
        OutboxRow row
    ) {
    }
}
