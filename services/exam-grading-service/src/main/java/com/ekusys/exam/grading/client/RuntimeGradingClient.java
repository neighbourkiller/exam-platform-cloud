package com.ekusys.exam.grading.client;
import com.ekusys.exam.common.api.ApiResponse; import com.ekusys.exam.runtime.api.GradingSubmissionInput; import org.springframework.cloud.openfeign.FeignClient; import org.springframework.web.bind.annotation.*;
@FeignClient(name="exam-runtime-service",contextId="gradingRuntimeClient") public interface RuntimeGradingClient {@GetMapping("/internal/v1/submissions/{id}/grading-input") ApiResponse<GradingSubmissionInput> input(@PathVariable("id") Long id);}
