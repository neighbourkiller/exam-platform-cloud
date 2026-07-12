package com.ekusys.exam.common.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.ekusys.exam.common.event.DomainEvent;
import com.ekusys.exam.common.event.EventTypes;
import com.ekusys.exam.common.outbox.OutboxEventWriter;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuditOutboxServiceTest {

    @Test
    void keepsAuditEnvelopeAndProducer() {
        OutboxEventWriter writer = mock(OutboxEventWriter.class);
        AuditOutboxService service = new AuditOutboxService(writer, "exam-iam-service");
        AuditEventData data = new AuditEventData(
            7L, "admin", "ADMIN", "LOGIN", "AUTH", "7",
            "POST", "/api/v1/auth/login", "198.51.100.7", null,
            "SUCCESS", null, LocalDateTime.of(2026, 7, 12, 10, 0)
        );

        service.record(data, "trace-1");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(writer).append(
            any(String.class), eq("AUDIT"), eq("7"),
            eq(EventTypes.AUDIT_OPERATION_RECORDED), payload.capture()
        );
        DomainEvent<?> event = (DomainEvent<?>) payload.getValue();
        assertThat(event.producer()).isEqualTo("exam-iam-service");
        assertThat(event.traceId()).isEqualTo("trace-1");
        assertThat(event.data()).isEqualTo(data);
    }
}
