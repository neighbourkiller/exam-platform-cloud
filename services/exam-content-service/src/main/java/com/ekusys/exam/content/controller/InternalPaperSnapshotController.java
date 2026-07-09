package com.ekusys.exam.content.controller;

import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.content.api.PaperSnapshotView;
import com.ekusys.exam.content.api.PaperSummary;
import com.ekusys.exam.content.service.PaperSnapshotService;
import com.ekusys.exam.repository.entity.Paper;
import com.ekusys.exam.repository.mapper.PaperMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/paper-snapshots")
public class InternalPaperSnapshotController {
    private final PaperSnapshotService service;
    private final PaperMapper paperMapper;
    public InternalPaperSnapshotController(PaperSnapshotService service, PaperMapper paperMapper) {
        this.service=service; this.paperMapper=paperMapper;
    }
    @PostMapping("/papers/{paperId}") public ApiResponse<PaperSnapshotView> create(@PathVariable Long paperId) { return ApiResponse.ok(service.create(paperId)); }
    @GetMapping("/{id}/delivery") public ApiResponse<PaperSnapshotView> delivery(@PathVariable Long id) { return ApiResponse.ok(service.get(id,false)); }
    @GetMapping("/{id}/grading") public ApiResponse<PaperSnapshotView> grading(@PathVariable Long id) { return ApiResponse.ok(service.get(id,true)); }
    @GetMapping("/papers/{paperId}") public ApiResponse<PaperSummary> summary(@PathVariable Long paperId) {
        Paper paper = paperMapper.selectById(paperId);
        return ApiResponse.ok(paper == null ? null : new PaperSummary(
            paper.getId(), paper.getName(), paper.getSubjectId(), paper.getTotalScore()
        ));
    }
}
