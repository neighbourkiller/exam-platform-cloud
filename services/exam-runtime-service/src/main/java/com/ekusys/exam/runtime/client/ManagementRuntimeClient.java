package com.ekusys.exam.runtime.client;
import com.ekusys.exam.common.api.ApiResponse; import com.ekusys.exam.management.api.RuntimeExamSnapshot; import java.util.List;
import org.springframework.cloud.openfeign.FeignClient; import org.springframework.web.bind.annotation.GetMapping; import org.springframework.web.bind.annotation.PathVariable;
@FeignClient(name="exam-management-service",contextId="runtimeManagementClient") public interface ManagementRuntimeClient {
 @GetMapping("/internal/v1/exams/{id}/runtime-snapshot") ApiResponse<RuntimeExamSnapshot> snapshot(@PathVariable("id") Long id);
 @GetMapping("/internal/v1/exams/students/{studentId}") ApiResponse<List<RuntimeExamSnapshot>> student(@PathVariable("studentId") Long studentId); }
