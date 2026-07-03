package com.ekusys.exam.teacher.controller;

import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.common.audit.AuditOperation;
import com.ekusys.exam.common.api.PageResponse;
import com.ekusys.exam.teacher.dto.TeacherClassAddStudentsRequest;
import com.ekusys.exam.teacher.dto.TeacherClassStudentCandidateQueryRequest;
import com.ekusys.exam.teacher.dto.TeacherClassStudentView;
import com.ekusys.exam.teacher.dto.TeacherClassView;
import com.ekusys.exam.teacher.service.TeacherClassService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/teacher/classes")
@PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
public class TeacherClassController {

    private final TeacherClassService teacherClassService;

    public TeacherClassController(TeacherClassService teacherClassService) {
        this.teacherClassService = teacherClassService;
    }

    @GetMapping
    public ApiResponse<List<TeacherClassView>> listMyClasses() {
        return ApiResponse.ok(teacherClassService.listMyClasses());
    }

    @GetMapping("/{classId}/students")
    public ApiResponse<List<TeacherClassStudentView>> listClassStudents(@PathVariable Long classId) {
        return ApiResponse.ok(teacherClassService.listClassStudents(classId));
    }

    @PostMapping("/{classId}/students")
    @AuditOperation(action = "TEACHING_CLASS_ADD_STUDENTS", targetType = "TEACHING_CLASS", targetId = "#classId",
        detail = "#request.studentIds")
    public ApiResponse<Void> addStudents(@PathVariable Long classId,
                                         @Valid @RequestBody TeacherClassAddStudentsRequest request) {
        teacherClassService.addStudents(classId, request.getStudentIds());
        return ApiResponse.ok("添加成功", null);
    }

    @DeleteMapping("/{classId}/students/{studentId}")
    @AuditOperation(action = "TEACHING_CLASS_REMOVE_STUDENT", targetType = "TEACHING_CLASS", targetId = "#classId",
        detail = "#studentId")
    public ApiResponse<Void> removeStudent(@PathVariable Long classId, @PathVariable Long studentId) {
        teacherClassService.removeStudent(classId, studentId);
        return ApiResponse.ok("移除成功", null);
    }

    @PostMapping("/{classId}/student-candidates/query")
    public ApiResponse<PageResponse<TeacherClassStudentView>> queryStudentCandidates(
        @PathVariable Long classId,
        @RequestBody TeacherClassStudentCandidateQueryRequest request
    ) {
        return ApiResponse.ok(teacherClassService.queryStudentCandidates(
            classId,
            request.getPageNum(),
            request.getPageSize(),
            request.getKeyword()
        ));
    }
}
