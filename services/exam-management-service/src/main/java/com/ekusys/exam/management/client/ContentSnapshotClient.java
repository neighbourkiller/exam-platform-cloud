package com.ekusys.exam.management.client;
import com.ekusys.exam.common.api.ApiResponse; import com.ekusys.exam.content.api.PaperSnapshotView; import com.ekusys.exam.content.api.PaperSummary;
import org.springframework.cloud.openfeign.FeignClient; import org.springframework.web.bind.annotation.GetMapping; import org.springframework.web.bind.annotation.PathVariable; import org.springframework.web.bind.annotation.PostMapping;
@FeignClient(name="exam-content-service",contextId="managementContentClient")
public interface ContentSnapshotClient {
 @PostMapping("/internal/v1/paper-snapshots/papers/{paperId}") ApiResponse<PaperSnapshotView> create(@PathVariable("paperId") Long paperId);
 @PostMapping("/internal/v1/paper-snapshots/{id}/cache-warm") ApiResponse<Void> warm(@PathVariable("id") Long id);
 @GetMapping("/internal/v1/paper-snapshots/papers/{paperId}") ApiResponse<PaperSummary> summary(@PathVariable("paperId") Long paperId);
 @GetMapping("/internal/v1/paper-snapshots/{id}/delivery") ApiResponse<PaperSnapshotView> delivery(@PathVariable("id") Long id);
}
