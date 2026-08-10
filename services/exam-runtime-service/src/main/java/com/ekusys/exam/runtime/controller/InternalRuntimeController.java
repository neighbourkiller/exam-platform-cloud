package com.ekusys.exam.runtime.controller;

import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.runtime.api.GradingSubmissionInput;
import com.ekusys.exam.runtime.entry.ExamProvisioningReconcileService;
import com.ekusys.exam.runtime.entry.ExamProvisioningView;
import com.ekusys.exam.runtime.service.ExamRuntimeService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1")
@PreAuthorize("hasAuthority('SCOPE_internal')")
public class InternalRuntimeController {
    private final ExamRuntimeService service;
    private final ExamProvisioningReconcileService reconciliation;

    public InternalRuntimeController(ExamRuntimeService service,
                                     ExamProvisioningReconcileService reconciliation) {
        this.service = service;
        this.reconciliation = reconciliation;
    }

    @GetMapping("/submissions/{id}/grading-input")
    public ApiResponse<GradingSubmissionInput> input(@PathVariable Long id) {
        return ApiResponse.ok(service.gradingInput(id));
    }

    @PostMapping("/exam-provisioning/{examId}/reconcile")
    public ApiResponse<ExamProvisioningView> reconcile(@PathVariable Long examId) {
        return ApiResponse.ok("考试 Runtime 数据已核对", reconciliation.reconcile(examId));
    }
}
