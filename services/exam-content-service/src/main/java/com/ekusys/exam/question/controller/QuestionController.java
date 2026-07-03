package com.ekusys.exam.question.controller;

import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.common.audit.AuditOperation;
import com.ekusys.exam.common.api.PageResponse;
import com.ekusys.exam.question.dto.QuestionCreateRequest;
import com.ekusys.exam.question.dto.QuestionImageUploadView;
import com.ekusys.exam.question.dto.QuestionQueryRequest;
import com.ekusys.exam.question.dto.QuestionSubjectOptionView;
import com.ekusys.exam.question.dto.QuestionUpdateRequest;
import com.ekusys.exam.question.dto.QuestionView;
import com.ekusys.exam.question.service.QuestionImageService;
import com.ekusys.exam.question.service.QuestionService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/questions")
public class QuestionController {

    private final QuestionService questionService;
    private final QuestionImageService questionImageService;

    public QuestionController(QuestionService questionService, QuestionImageService questionImageService) {
        this.questionService = questionService;
        this.questionImageService = questionImageService;
    }

    @PostMapping("/query")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<PageResponse<QuestionView>> query(@RequestBody QuestionQueryRequest request) {
        return ApiResponse.ok(questionService.query(request));
    }

    @GetMapping("/subjects")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<List<QuestionSubjectOptionView>> listSubjects() {
        return ApiResponse.ok(questionService.listSubjectOptions());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<QuestionView> getById(@PathVariable Long id) {
        return ApiResponse.ok(questionService.getById(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    @AuditOperation(action = "QUESTION_CREATE", targetType = "QUESTION", targetId = "#result.data",
        detail = "'subjectId=' + #request.subjectId + ',type=' + #request.type")
    public ApiResponse<Long> create(@Valid @RequestBody QuestionCreateRequest request) {
        return ApiResponse.ok("创建成功", questionService.create(request));
    }

    @PostMapping(value = "/images/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public ApiResponse<QuestionImageUploadView> uploadImage(@RequestParam("file") MultipartFile file,
                                                            @RequestParam(value = "questionId", required = false) Long questionId) {
        return ApiResponse.ok("上传成功", questionImageService.upload(file, questionId));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    @AuditOperation(action = "QUESTION_UPDATE", targetType = "QUESTION", targetId = "#id",
        detail = "'subjectId=' + #request.subjectId + ',type=' + #request.type")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody QuestionUpdateRequest request) {
        questionService.update(id, request);
        return ApiResponse.ok("更新成功", null);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    @AuditOperation(action = "QUESTION_DELETE", targetType = "QUESTION", targetId = "#id")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        questionService.delete(id);
        return ApiResponse.ok("删除成功", null);
    }
}
