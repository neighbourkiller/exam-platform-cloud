package com.ekusys.exam.iam.client;

import com.ekusys.exam.academic.api.AcademicUserProfileCommand;
import com.ekusys.exam.academic.api.AcademicUserSummary;
import com.ekusys.exam.common.api.ApiResponse;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "exam-academic-service", contextId = "iamAcademicProfileClient")
public interface AcademicProfileClient {
    @PostMapping("/internal/v1/academic/users/{id}/profile")
    ApiResponse<Void> synchronize(@PathVariable("id") Long id, @RequestBody AcademicUserProfileCommand command);

    @DeleteMapping("/internal/v1/academic/users/{id}/profile")
    ApiResponse<Void> delete(@PathVariable("id") Long id);

    @PostMapping("/internal/v1/academic/users/summaries")
    ApiResponse<List<AcademicUserSummary>> summaries(@RequestBody List<Long> ids);
}
