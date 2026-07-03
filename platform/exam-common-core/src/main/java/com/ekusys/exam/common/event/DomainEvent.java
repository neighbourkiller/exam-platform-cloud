package com.ekusys.exam.common.event;

import java.time.Instant;
import java.util.UUID;

public record DomainEvent<T>(
    String eventId,
    String eventType,
    int version,
    String aggregateId,
    Instant occurredAt,
    String traceId,
    String producer,
    T data
) {
    public static <T> DomainEvent<T> create(String eventType, String aggregateId, String traceId,
                                             String producer, T data) {
        return new DomainEvent<>(UUID.randomUUID().toString(), eventType, 1, aggregateId,
            Instant.now(), traceId, producer, data);
    }
}
