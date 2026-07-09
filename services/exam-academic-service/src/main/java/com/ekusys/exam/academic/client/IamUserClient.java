package com.ekusys.exam.academic.client;

import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.iam.api.UserBatchRequest;
import com.ekusys.exam.iam.api.UserSummary;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "exam-iam-service", contextId = "academicIamUserClient")
public interface IamUserClient {
    @GetMapping("/internal/v1/users/{id}")
    ApiResponse<UserSummary> get(@PathVariable("id") Long id);

    @GetMapping("/internal/v1/users/by-username")
    ApiResponse<UserSummary> byUsername(@RequestParam("username") String username);

    @PostMapping("/internal/v1/users/batch")
    ApiResponse<List<UserSummary>> batch(@RequestBody UserBatchRequest request);

    @GetMapping("/internal/v1/users/by-role")
    ApiResponse<List<UserSummary>> byRole(@RequestParam("role") String role);
}
