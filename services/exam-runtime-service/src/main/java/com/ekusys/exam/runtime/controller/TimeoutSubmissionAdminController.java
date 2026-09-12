package com.ekusys.exam.runtime.controller;

import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.common.audit.AuditOperation;
import com.ekusys.exam.runtime.service.TimeoutSubmissionReplayService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/exams/{examId}/timeout-submissions")
@PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
public class TimeoutSubmissionAdminController {
    private final TimeoutSubmissionReplayService replayService;

    public TimeoutSubmissionAdminController(TimeoutSubmissionReplayService replayService) {
        this.replayService = replayService;
    }

    @PostMapping("/{taskId}/replay")
    @AuditOperation(
        action = "TIMEOUT_SUBMISSION_REPLAY",
        targetType = "SUBMISSION_TIMEOUT_TASK",
        targetId = "#taskId",
        detail = "#result == null ? 'examId=' + #examId : 'examId=' + #examId + ',incidentId=' + #result.data.incidentId + ',replayCount=' + #result.data.replayCount"
    )
    public ApiResponse<TimeoutSubmissionReplayService.ReplayResult> replay(
        @PathVariable Long examId, @PathVariable Long taskId
    ) {
        TimeoutSubmissionReplayService.ReplayResult result = replayService.replay(examId, taskId);
        return ApiResponse.ok("超时交卷任务已进入安全重放队列", result);
    }
}
