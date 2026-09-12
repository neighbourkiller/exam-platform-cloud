package com.ekusys.exam.reporting.controller;

import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.grading.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/grade-projection")
@PreAuthorize("hasAuthority('SCOPE_internal')")
public class InternalGradeProjectionController {
    private final JdbcTemplate jdbc;
    public InternalGradeProjectionController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @PostMapping("/progress")
    public ApiResponse<GradeProjectionProgress> progress(@RequestBody GradeProjectionQuery query) {
        if (query.examId() == null || query.revisions() == null || query.revisions().size() > 100) {
            throw new BusinessException("无效的成绩版本查询");
        }
        var ids = query.revisions().stream().filter(r -> jdbc.queryForObject(
            "select count(*) from rpt_student_score where exam_id=? and submission_id=? and grade_revision>=?",
            Integer.class, query.examId(), r.submissionId(), r.gradeRevision()) > 0)
            .map(GradeProjectionQuery.Revision::submissionId).toList();
        return ApiResponse.ok(new GradeProjectionProgress(ids));
    }
}
