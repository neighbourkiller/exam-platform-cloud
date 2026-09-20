package com.ekusys.exam.paper.controller;

import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.common.audit.AuditOperation;
import com.ekusys.exam.common.api.PageResponse;
import com.ekusys.exam.paper.dto.AutoGeneratePaperRequest;
import com.ekusys.exam.paper.dto.ManualCreatePaperRequest;
import com.ekusys.exam.paper.dto.PaperDetailView;
import com.ekusys.exam.paper.dto.PaperListItemView;
import com.ekusys.exam.paper.dto.PaperQueryRequest;
import com.ekusys.exam.paper.dto.PaperUpdateRequest;
import com.ekusys.exam.paper.service.PaperService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/papers")
@PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
public class PaperController {

    private final PaperService paperService;

    public PaperController(PaperService paperService) {
        this.paperService = paperService;
    }

    @PostMapping("/manual")
    @AuditOperation(action = "PAPER_CREATE_MANUAL", targetType = "PAPER", targetId = "#result.data", detail = "#request.name")
    public ApiResponse<Long> manualCreate(@Valid @RequestBody ManualCreatePaperRequest request) {
        return ApiResponse.ok("组卷成功", paperService.createManual(request));
    }

    @PostMapping("/auto-generate")
    @AuditOperation(action = "PAPER_CREATE_AUTO", targetType = "PAPER", targetId = "#result.data", detail = "#request.name")
    public ApiResponse<Long> autoCreate(@Valid @RequestBody AutoGeneratePaperRequest request) {
        return ApiResponse.ok("组卷成功", paperService.autoGenerate(request));
    }

    @PostMapping("/query")
    public ApiResponse<PageResponse<PaperListItemView>> query(@RequestBody PaperQueryRequest request) {
        return ApiResponse.ok(paperService.query(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<PaperDetailView> getDetail(@PathVariable Long id) {
        return ApiResponse.ok(paperService.getDetail(id));
    }

    @PutMapping("/{id}")
    @AuditOperation(action = "PAPER_UPDATE", targetType = "PAPER", targetId = "#id", detail = "#request.name")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody PaperUpdateRequest request) {
        paperService.update(id, request);
        return ApiResponse.ok("更新成功", null);
    }

    @DeleteMapping("/{id}")
    @AuditOperation(action = "PAPER_DELETE", targetType = "PAPER", targetId = "#id")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        paperService.delete(id);
        return ApiResponse.ok("删除成功", null);
    }
}
