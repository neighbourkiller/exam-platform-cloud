package com.ekusys.exam.runtime.client;

import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.iam.api.UserBatchRequest;
import com.ekusys.exam.iam.api.UserSummary;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "exam-iam-service", contextId = "runtimeIamClient")
public interface IamRuntimeClient {
    @PostMapping("/internal/v1/users/batch")
    ApiResponse<List<UserSummary>> batch(@RequestBody UserBatchRequest request);
}
