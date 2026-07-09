package com.ekusys.exam.management.client;

import com.ekusys.exam.academic.api.ClassRosterView;
import com.ekusys.exam.academic.api.SubjectSummary;
import com.ekusys.exam.academic.api.TeachingClassBatchRequest;
import com.ekusys.exam.academic.api.TeachingClassSummary;
import com.ekusys.exam.common.api.ApiResponse;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "exam-academic-service", contextId = "managementAcademicClient")
public interface AcademicRosterClient {
    @GetMapping("/internal/v1/academic/classes/{id}/roster")
    ApiResponse<ClassRosterView> roster(@PathVariable("id") Long id);

    @GetMapping("/internal/v1/academic/subjects/{id}")
    ApiResponse<SubjectSummary> subject(@PathVariable("id") Long id);

    @PostMapping("/internal/v1/academic/classes/summaries")
    ApiResponse<List<TeachingClassSummary>> classSummaries(@RequestBody TeachingClassBatchRequest request);
}
