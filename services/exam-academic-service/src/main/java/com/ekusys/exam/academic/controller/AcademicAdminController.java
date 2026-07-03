package com.ekusys.exam.academic.controller;

import com.ekusys.exam.admin.dto.CourseCreateRequest;
import com.ekusys.exam.admin.dto.CourseUpdateRequest;
import com.ekusys.exam.admin.dto.CourseView;
import com.ekusys.exam.admin.dto.TeachingClassCreateRequest;
import com.ekusys.exam.admin.dto.TeachingClassUpdateRequest;
import com.ekusys.exam.admin.dto.TeachingClassView;
import com.ekusys.exam.admin.service.SubjectAdminService;
import com.ekusys.exam.admin.service.TeachingClassAdminService;
import com.ekusys.exam.academic.dto.BulkTeachingClassOperationRequest;
import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.repository.mapper.TeachingClassMapper;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AcademicAdminController {
    private final SubjectAdminService subjectService;
    private final TeachingClassAdminService classService;
    private final TeachingClassMapper classMapper;

    public AcademicAdminController(SubjectAdminService subjectService, TeachingClassAdminService classService,
                                   TeachingClassMapper classMapper) {
        this.subjectService = subjectService; this.classService = classService; this.classMapper = classMapper;
    }

    @GetMapping("/courses") public ApiResponse<List<CourseView>> courses() { return ApiResponse.ok(subjectService.listCourses()); }
    @PostMapping("/courses") public ApiResponse<Long> createCourse(@Valid @RequestBody CourseCreateRequest request) { return ApiResponse.ok(subjectService.createCourse(request)); }
    @PutMapping("/courses/{id}") public ApiResponse<Void> updateCourse(@PathVariable Long id, @Valid @RequestBody CourseUpdateRequest request) { subjectService.updateCourse(id, request); return ApiResponse.ok(null); }
    @GetMapping("/teaching-classes") public ApiResponse<List<TeachingClassView>> classes() { return ApiResponse.ok(classService.listTeachingClasses()); }
    @PostMapping("/teaching-classes") public ApiResponse<Long> createClass(@Valid @RequestBody TeachingClassCreateRequest request) { return ApiResponse.ok(classService.createTeachingClass(request)); }
    @PutMapping("/teaching-classes/{id}") public ApiResponse<Void> updateClass(@PathVariable Long id, @Valid @RequestBody TeachingClassUpdateRequest request) { classService.updateTeachingClass(id, request); return ApiResponse.ok(null); }
    @PostMapping("/teaching-classes/batch") public ApiResponse<Void> operateClasses(@Valid @RequestBody BulkTeachingClassOperationRequest request) {
        for (Long id : request.classIds().stream().distinct().toList()) {
            var value = classMapper.selectById(id);
            if (value == null) throw new BusinessException("教学班不存在: " + id);
            value.setStatus(request.status().trim().toUpperCase()); classMapper.updateById(value);
        }
        return ApiResponse.ok("批量操作成功", null);
    }
}
