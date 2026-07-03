package com.ekusys.exam.management.controller;
import com.ekusys.exam.common.api.ApiResponse; import com.ekusys.exam.management.api.RuntimeExamSnapshot; import com.ekusys.exam.management.service.ExamManagementService;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequestMapping("/internal/v1/exams") public class InternalManagementController {private final ExamManagementService service;public InternalManagementController(ExamManagementService service){this.service=service;}
 @GetMapping("/papers/{paperId}/used") public ApiResponse<Boolean> used(@PathVariable Long paperId){return ApiResponse.ok(service.isPaperUsed(paperId));}
 @GetMapping("/{id}/runtime-snapshot") public ApiResponse<RuntimeExamSnapshot> snapshot(@PathVariable Long id){return ApiResponse.ok(service.runtimeSnapshot(id));}
 @GetMapping("/students/{studentId}") public ApiResponse<List<RuntimeExamSnapshot>> student(@PathVariable Long studentId){return ApiResponse.ok(service.studentSnapshots(studentId));}}
