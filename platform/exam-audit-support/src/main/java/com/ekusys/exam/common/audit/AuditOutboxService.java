package com.ekusys.exam.common.audit;

import com.ekusys.exam.common.event.DomainEvent;
import com.ekusys.exam.common.event.EventTypes;
import com.ekusys.exam.common.outbox.OutboxEventWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditOutboxService {
    private final OutboxEventWriter writer;
    private final String producer;

    public AuditOutboxService(OutboxEventWriter writer,
                              @Value("${spring.application.name:unknown-service}") String producer) {
        this.writer = writer;
        this.producer = producer;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditEventData data, String traceId) {
        DomainEvent<AuditEventData> event = DomainEvent.create(
            EventTypes.AUDIT_OPERATION_RECORDED,
            data.targetId() == null || data.targetId().isBlank() ? "audit" : data.targetId(),
            traceId,
            producer,
            data
        );
        writer.append(event.eventId(), "AUDIT", event.aggregateId(), event.eventType(), event);
    }
}
