package com.ekusys.exam.content.client;

import com.ekusys.exam.common.api.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name="exam-management-service", contextId="contentManagementClient")
public interface ManagementUsageClient {
    @GetMapping("/internal/v1/exams/papers/{paperId}/used") ApiResponse<Boolean> isPaperUsed(@PathVariable("paperId") Long paperId);
}
