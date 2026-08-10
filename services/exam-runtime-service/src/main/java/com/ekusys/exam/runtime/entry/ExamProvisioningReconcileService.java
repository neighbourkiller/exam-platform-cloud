package com.ekusys.exam.runtime.entry;

import com.ekusys.exam.management.api.RuntimeExamMetadata;
import com.ekusys.exam.management.api.RuntimeExamProctoringContext;
import com.ekusys.exam.runtime.client.ManagementRuntimeClient;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ExamProvisioningReconcileService {
    private final ManagementRuntimeClient management;
    private final ExamProvisioningService provisioning;

    public ExamProvisioningReconcileService(ManagementRuntimeClient management,
                                            ExamProvisioningService provisioning) {
        this.management = management;
        this.provisioning = provisioning;
    }

    public ExamProvisioningView reconcile(Long examId) {
        RuntimeExamProctoringContext context = management.proctoringContext(examId).getData();
        if (context == null || context.metadata() == null) {
            throw new IllegalStateException("Management 未返回考试补建数据: " + examId);
        }
        RuntimeExamMetadata metadata = context.metadata();
        String eventId = UUID.nameUUIDFromBytes(
            ("ExamProvisioningReconcile:" + examId + ":" + metadata.paperSnapshotVersion())
                .getBytes(StandardCharsets.UTF_8)
        ).toString();
        if ("TERMINATED".equals(context.status())) {
            return provisioning.terminate(eventId, 2, examId);
        }
        return provisioning.provision(new ExamProvisioningCommand(
            eventId, 2, examId, metadata.name(), metadata.startTime(), metadata.endTime(),
            metadata.durationMinutes(), metadata.passScore(), metadata.paperSnapshotId(),
            metadata.paperSnapshotVersion(), metadata.publisherId(), metadata.proctoringLevel(),
            metadata.proctoringConfigJson(), context.status(), context.candidateIds()
        ));
    }
}
