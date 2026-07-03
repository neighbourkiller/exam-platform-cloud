package com.ekusys.exam.content.client;

import com.ekusys.exam.academic.api.SubjectSummary;
import com.ekusys.exam.common.api.ApiResponse;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name="exam-academic-service", contextId="contentAcademicClient")
public interface AcademicContentClient {
    @GetMapping("/internal/v1/academic/subjects") ApiResponse<List<SubjectSummary>> subjects();
    @GetMapping("/internal/v1/academic/subjects/{id}") ApiResponse<SubjectSummary> subject(@PathVariable("id") Long id);
}
