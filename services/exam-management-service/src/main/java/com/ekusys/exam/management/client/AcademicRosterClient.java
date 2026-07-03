package com.ekusys.exam.management.client;

import com.ekusys.exam.academic.api.ClassRosterView;
import com.ekusys.exam.academic.api.SubjectSummary;
import com.ekusys.exam.common.api.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "exam-academic-service", contextId = "managementAcademicClient")
public interface AcademicRosterClient {
    @GetMapping("/internal/v1/academic/classes/{id}/roster")
    ApiResponse<ClassRosterView> roster(@PathVariable("id") Long id);

    @GetMapping("/internal/v1/academic/subjects/{id}")
    ApiResponse<SubjectSummary> subject(@PathVariable("id") Long id);
}
