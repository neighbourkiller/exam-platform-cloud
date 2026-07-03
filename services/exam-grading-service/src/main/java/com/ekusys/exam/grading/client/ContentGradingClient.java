package com.ekusys.exam.grading.client;
import com.ekusys.exam.common.api.ApiResponse; import com.ekusys.exam.content.api.PaperSnapshotView; import org.springframework.cloud.openfeign.FeignClient; import org.springframework.web.bind.annotation.*;
@FeignClient(name="exam-content-service",contextId="gradingContentClient") public interface ContentGradingClient {@GetMapping("/internal/v1/paper-snapshots/{id}/grading") ApiResponse<PaperSnapshotView> snapshot(@PathVariable("id") Long id);}
