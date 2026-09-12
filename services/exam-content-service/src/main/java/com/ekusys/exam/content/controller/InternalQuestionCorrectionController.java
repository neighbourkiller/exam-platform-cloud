package com.ekusys.exam.content.controller;

import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.content.api.*;
import com.ekusys.exam.content.service.QuestionCorrectionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/questions")
@PreAuthorize("hasAuthority('SCOPE_internal')")
public class InternalQuestionCorrectionController {
    private final QuestionCorrectionService service;
    public InternalQuestionCorrectionController(QuestionCorrectionService service) { this.service = service; }
    @GetMapping("/{id}/correction-context")
    public ApiResponse<QuestionCorrectionView> view(@PathVariable Long id) { return ApiResponse.ok(service.view(id)); }
    @PostMapping("/{id}/answer-corrections")
    public ApiResponse<QuestionCorrectionResult> correct(@PathVariable Long id, @RequestBody QuestionCorrectionCommand command) {
        return ApiResponse.ok(service.correct(id, command));
    }
}
