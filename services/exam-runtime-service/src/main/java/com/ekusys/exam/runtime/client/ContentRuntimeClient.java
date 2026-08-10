package com.ekusys.exam.runtime.client;

import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.content.api.PaperSnapshotView;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "exam-content-service", contextId = "runtimeContentClient")
public interface ContentRuntimeClient {
    @GetMapping("/internal/v1/paper-snapshots/{id}/delivery")
    ApiResponse<PaperSnapshotView> delivery(@PathVariable("id") Long snapshotId);
}
