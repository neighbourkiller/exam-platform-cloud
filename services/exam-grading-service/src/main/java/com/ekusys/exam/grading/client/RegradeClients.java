package com.ekusys.exam.grading.client;

import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.content.api.*;
import com.ekusys.exam.management.api.ExamRegradeContext;
import com.ekusys.exam.runtime.api.SubmittedPage;
import com.ekusys.exam.grading.api.*;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

public final class RegradeClients {
    private RegradeClients() {}
    @FeignClient(name="exam-management-service", contextId="regradeManagement")
    public interface Management {
        @GetMapping("/internal/v1/exams/{id}/regrade-context")
        ApiResponse<ExamRegradeContext> context(@PathVariable("id") Long id);
    }
    @FeignClient(name="exam-runtime-service", contextId="regradeRuntime")
    public interface Runtime {
        @GetMapping("/internal/v1/exams/{id}/submitted-page")
        ApiResponse<SubmittedPage> page(@PathVariable("id") Long id, @RequestParam("after") long after,
                                       @RequestParam(value="upperBound", required=false) Long upperBound);
    }
    @FeignClient(name="exam-content-service", contextId="regradeContent")
    public interface Content {
        @GetMapping("/internal/v1/questions/{id}/correction-context")
        ApiResponse<QuestionCorrectionView> context(@PathVariable("id") Long id);
        @PostMapping("/internal/v1/questions/{id}/answer-corrections")
        ApiResponse<QuestionCorrectionResult> correct(@PathVariable("id") Long id, @RequestBody QuestionCorrectionCommand command);
    }
    @FeignClient(name="exam-reporting-service", contextId="regradeReporting")
    public interface Reporting {
        @PostMapping("/internal/v1/grade-projection/progress")
        ApiResponse<GradeProjectionProgress> progress(@RequestBody GradeProjectionQuery query);
    }
}
