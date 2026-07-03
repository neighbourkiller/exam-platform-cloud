package com.ekusys.exam.reporting.controller;

import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.exam.dto.StudentExamResultView;
import com.ekusys.exam.reporting.service.StudentResultService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/exams")
public class StudentResultController {
    private final StudentResultService studentResultService;

    public StudentResultController(StudentResultService studentResultService) {
        this.studentResultService = studentResultService;
    }

    @GetMapping("/student/results")
    @PreAuthorize("hasRole('STUDENT')")
    public ApiResponse<List<StudentExamResultView>> studentResults() {
        return ApiResponse.ok(studentResultService.listCurrentStudentResults());
    }
}
