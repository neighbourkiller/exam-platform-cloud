package com.ekusys.exam.runtime.messaging;

import com.ekusys.exam.management.api.RuntimeExamMetadata;
import com.ekusys.exam.management.api.RuntimeExamProctoringContext;
import com.ekusys.exam.runtime.client.ManagementRuntimeClient;
import com.ekusys.exam.runtime.entry.ExamProvisioningCommand;
import com.ekusys.exam.runtime.entry.ExamProvisioningService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class RuntimeExamLifecycleConsumer {
    private final ObjectMapper mapper;
    private final ManagementRuntimeClient management;
    private final ExamProvisioningService provisioning;

    public RuntimeExamLifecycleConsumer(ObjectMapper mapper, ManagementRuntimeClient management,
                                        ExamProvisioningService provisioning) {
        this.mapper = mapper;
        this.management = management;
        this.provisioning = provisioning;
    }

    @RabbitListener(
        queues = RuntimeRabbitConfig.LIFECYCLE_QUEUE,
        containerFactory = "runtimeLifecycleListenerContainerFactory"
    )
    public void consume(String payload) throws Exception {
        JsonNode event = mapper.readTree(payload);
        String eventId = requiredText(event, "eventId");
        String eventType = requiredText(event, "eventType");
        int version = event.path("version").asInt(1);
        JsonNode data = event.path("data");
        Long examId = requiredLong(data, "examId");
        switch (eventType) {
            case "ExamPublished" -> provisioning.provision(command(eventId, version, examId, data));
            case "ExamTerminated" -> provisioning.terminate(eventId, version, examId);
            default -> throw new IllegalArgumentException("Runtime 不支持的考试生命周期事件: " + eventType);
        }
    }

    private ExamProvisioningCommand command(String eventId, int version, Long examId, JsonNode data) {
        if (version >= 2) {
            List<Long> candidates = candidateIds(data.path("candidates"));
            int candidateCount = requiredInt(data, "candidateCount");
            if (candidateCount != candidates.size()) {
                throw new IllegalArgumentException(
                    "事件考生数量不一致: declared=" + candidateCount + ", actual=" + candidates.size()
                );
            }
            return new ExamProvisioningCommand(
                eventId, version, examId, requiredText(data, "name"),
                requiredTime(data, "startTime"), requiredTime(data, "endTime"),
                requiredInt(data, "durationMinutes"), requiredInt(data, "passScore"),
                requiredLong(data, "paperSnapshotId"), requiredLong(data, "paperSnapshotVersion"),
                nullableLong(data, "publisherId"), nullableText(data, "proctoringLevel"),
                nullableText(data, "proctoringConfigJson"), requiredText(data, "status"),
                candidates
            );
        }

        RuntimeExamProctoringContext context = management.proctoringContext(examId).getData();
        if (context == null || context.metadata() == null) {
            throw new IllegalStateException("Management 未返回 v1 事件补全数据: examId=" + examId);
        }
        RuntimeExamMetadata metadata = context.metadata();
        return new ExamProvisioningCommand(
            eventId, version, examId, metadata.name(), metadata.startTime(), metadata.endTime(),
            metadata.durationMinutes(), metadata.passScore(), metadata.paperSnapshotId(),
            metadata.paperSnapshotVersion(), metadata.publisherId(), metadata.proctoringLevel(),
            metadata.proctoringConfigJson(), context.status(), context.candidateIds()
        );
    }

    private List<Long> candidateIds(JsonNode candidates) {
        List<Long> result = new ArrayList<>();
        if (candidates != null && candidates.isArray()) {
            for (JsonNode candidate : candidates) {
                result.add(requiredLong(candidate, "studentId"));
            }
        }
        return List.copyOf(result);
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new IllegalArgumentException("事件缺少字段: " + field);
        }
        return value.asText();
    }

    private String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Long requiredLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.canConvertToLong()) {
            throw new IllegalArgumentException("事件缺少字段: " + field);
        }
        return value.longValue();
    }

    private Long nullableLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.longValue();
    }

    private int requiredInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("事件缺少字段: " + field);
        }
        return value.intValue();
    }

    private LocalDateTime requiredTime(JsonNode node, String field) {
        return LocalDateTime.parse(requiredText(node, field));
    }
}
