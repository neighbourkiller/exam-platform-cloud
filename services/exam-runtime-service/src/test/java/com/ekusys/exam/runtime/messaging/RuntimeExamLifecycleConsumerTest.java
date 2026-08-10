package com.ekusys.exam.runtime.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.management.api.RuntimeExamMetadata;
import com.ekusys.exam.management.api.RuntimeExamProctoringContext;
import com.ekusys.exam.runtime.client.ManagementRuntimeClient;
import com.ekusys.exam.runtime.entry.ExamProvisioningCommand;
import com.ekusys.exam.runtime.entry.ExamProvisioningService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RuntimeExamLifecycleConsumerTest {
    @Test
    void v2PublishedEventUsesPayloadWithoutCallingManagement() throws Exception {
        ManagementRuntimeClient management = mock(ManagementRuntimeClient.class);
        ExamProvisioningService provisioning = mock(ExamProvisioningService.class);
        RuntimeExamLifecycleConsumer consumer = new RuntimeExamLifecycleConsumer(
            new ObjectMapper(), management, provisioning
        );
        String payload = """
            {
              "eventId":"11f2f73b-e9ac-4c3f-9130-aa7bb5b169ec",
              "eventType":"ExamPublished",
              "version":2,
              "data":{
                "examId":11,
                "name":"Java 期末考试",
                "startTime":"2026-08-09T10:00:00",
                "endTime":"2026-08-09T12:00:00",
                "durationMinutes":120,
                "passScore":60,
                "paperSnapshotId":21,
                "paperSnapshotVersion":3,
                "publisherId":7,
                "proctoringLevel":"STANDARD",
                "status":"PUBLISHED",
                "candidateCount":2,
                "candidates":[{"studentId":101},{"studentId":102}]
              }
            }
            """;

        consumer.consume(payload);

        ArgumentCaptor<ExamProvisioningCommand> command =
            ArgumentCaptor.forClass(ExamProvisioningCommand.class);
        verify(provisioning).provision(command.capture());
        verify(management, never()).proctoringContext(any());
        assertThat(command.getValue().paperSnapshotId()).isEqualTo(21L);
        assertThat(command.getValue().paperSnapshotVersion()).isEqualTo(3L);
        assertThat(command.getValue().candidateIds()).containsExactly(101L, 102L);
    }

    @Test
    void v1PublishedEventUsesManagementContextToCompleteMetadata() throws Exception {
        ManagementRuntimeClient management = mock(ManagementRuntimeClient.class);
        ExamProvisioningService provisioning = mock(ExamProvisioningService.class);
        RuntimeExamMetadata metadata = new RuntimeExamMetadata(
            11L, "Java 期末考试",
            LocalDateTime.of(2026, 8, 9, 10, 0),
            LocalDateTime.of(2026, 8, 9, 12, 0),
            120, 60, 21L, 3L, 7L, "STANDARD", null
        );
        when(management.proctoringContext(11L)).thenReturn(ApiResponse.ok(
            new RuntimeExamProctoringContext(metadata, "PUBLISHED", List.of(101L, 102L))
        ));
        RuntimeExamLifecycleConsumer consumer = new RuntimeExamLifecycleConsumer(
            new ObjectMapper(), management, provisioning
        );

        consumer.consume("""
            {
              "eventId":"90c9eeb8-e393-4cc8-aa9b-426062fd02c5",
              "eventType":"ExamPublished",
              "version":1,
              "data":{"examId":11,"name":"Java 期末考试"}
            }
            """);

        ArgumentCaptor<ExamProvisioningCommand> command =
            ArgumentCaptor.forClass(ExamProvisioningCommand.class);
        verify(provisioning).provision(command.capture());
        assertThat(command.getValue().paperSnapshotId()).isEqualTo(21L);
        assertThat(command.getValue().candidateIds()).containsExactly(101L, 102L);
    }

    @Test
    void malformedV2EventIsRetriedInsteadOfCallingManagementFallback() {
        ManagementRuntimeClient management = mock(ManagementRuntimeClient.class);
        ExamProvisioningService provisioning = mock(ExamProvisioningService.class);
        RuntimeExamLifecycleConsumer consumer = new RuntimeExamLifecycleConsumer(
            new ObjectMapper(), management, provisioning
        );

        assertThatThrownBy(() -> consumer.consume("""
            {
              "eventId":"8ef093a8-90de-45ce-9350-9c7cdbac7601",
              "eventType":"ExamPublished",
              "version":2,
              "data":{"examId":11,"name":"字段不完整的 v2 事件"}
            }
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("candidateCount");

        verify(management, never()).proctoringContext(any());
        verify(provisioning, never()).provision(any());
    }

    @Test
    void terminatedEventAlwaysUsesTerminationPath() throws Exception {
        ManagementRuntimeClient management = mock(ManagementRuntimeClient.class);
        ExamProvisioningService provisioning = mock(ExamProvisioningService.class);
        RuntimeExamLifecycleConsumer consumer = new RuntimeExamLifecycleConsumer(
            new ObjectMapper(), management, provisioning
        );

        consumer.consume("""
            {
              "eventId":"d249bd97-7693-45ad-831b-e5847f6c5a36",
              "eventType":"ExamTerminated",
              "version":1,
              "data":{"examId":11}
            }
            """);

        verify(provisioning).terminate("d249bd97-7693-45ad-831b-e5847f6c5a36", 1, 11L);
        verify(provisioning, never()).provision(any());
    }
}
