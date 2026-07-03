package com.ekusys.exam.grading.controller;

import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.common.audit.AuditOperation;
import com.ekusys.exam.grading.dto.PendingAnswerView;
import com.ekusys.exam.grading.dto.PendingQuestionAnswerView;
import com.ekusys.exam.grading.dto.PendingQuestionGroupView;
import com.ekusys.exam.grading.dto.QuestionBatchScoreRequest;
import com.ekusys.exam.grading.dto.SubjectiveScoreRequest;
import com.ekusys.exam.grading.service.GradingService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/grading")
@PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
public class GradingController {

    private final GradingService gradingService;

    public GradingController(GradingService gradingService) {
        this.gradingService = gradingService;
    }

    @GetMapping("/pending")
    public ApiResponse<List<PendingAnswerView>> pending() {
        return ApiResponse.ok(gradingService.pendingAnswers());
    }

    @GetMapping("/pending/questions")
    public ApiResponse<List<PendingQuestionGroupView>> pendingQuestions() {
        return ApiResponse.ok(gradingService.pendingQuestionGroups());
    }

    @GetMapping("/pending/questions/{questionId}/answers")
    public ApiResponse<List<PendingQuestionAnswerView>> pendingQuestionAnswers(@PathVariable Long questionId,
                                                                               @RequestParam Long examId) {
        return ApiResponse.ok(gradingService.pendingQuestionAnswers(questionId, examId));
    }

    @PostMapping("/{submissionId}/subjective-score")
    @AuditOperation(action = "SUBMISSION_SCORE_SUBJECTIVE", targetType = "SUBMISSION", targetId = "#submissionId",
        detail = "#request.scores")
    public ApiResponse<Void> score(@PathVariable Long submissionId, @Valid @RequestBody SubjectiveScoreRequest request) {
        gradingService.scoreSubjective(submissionId, request);
        return ApiResponse.ok("评分成功", null);
    }

    @PostMapping("/pending/questions/{questionId}/score")
    @AuditOperation(action = "QUESTION_BATCH_SCORE_SUBJECTIVE", targetType = "QUESTION", targetId = "#questionId",
        detail = "#request.submissionAnswerIds")
    public ApiResponse<Void> scoreQuestionAnswers(@PathVariable Long questionId,
                                                  @Valid @RequestBody QuestionBatchScoreRequest request) {
        gradingService.scoreQuestionAnswers(questionId, request);
        return ApiResponse.ok("评分成功", null);
    }
}
