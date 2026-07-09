package com.ekusys.exam.academic.controller;

import com.ekusys.exam.academic.dto.BulkTeachingClassOperationRequest;
import com.ekusys.exam.academic.service.AcademicCsvImportService;
import com.ekusys.exam.admin.dto.CourseCreateRequest;
import com.ekusys.exam.admin.dto.CourseUpdateRequest;
import com.ekusys.exam.admin.dto.CourseView;
import com.ekusys.exam.admin.dto.TeachingClassCreateRequest;
import com.ekusys.exam.admin.dto.TeachingClassUpdateRequest;
import com.ekusys.exam.admin.dto.TeachingClassView;
import com.ekusys.exam.admin.service.SubjectAdminService;
import com.ekusys.exam.admin.service.TeachingClassAdminService;
import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.common.audit.AuditOperation;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.importing.BulkImportResultView;
import com.ekusys.exam.repository.mapper.TeachingClassMapper;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AcademicAdminController {
    private final SubjectAdminService subjectService;
    private final TeachingClassAdminService classService;
    private final TeachingClassMapper classMapper;
    private final AcademicCsvImportService importService;

    public AcademicAdminController(SubjectAdminService subjectService,
                                   TeachingClassAdminService classService,
                                   TeachingClassMapper classMapper,
                                   AcademicCsvImportService importService) {
        this.subjectService = subjectService;
        this.classService = classService;
        this.classMapper = classMapper;
        this.importService = importService;
    }

    @GetMapping("/courses")
    public ApiResponse<List<CourseView>> courses() {
        return ApiResponse.ok(subjectService.listCourses());
    }

    @PostMapping("/courses")
    @AuditOperation(action = "COURSE_CREATE", targetType = "COURSE", targetId = "#result.data", detail = "#request.name")
    public ApiResponse<Long> createCourse(@Valid @RequestBody CourseCreateRequest request) {
        return ApiResponse.ok(subjectService.createCourse(request));
    }

    @PutMapping("/courses/{id}")
    @AuditOperation(action = "COURSE_UPDATE", targetType = "COURSE", targetId = "#id", detail = "#request.name")
    public ApiResponse<Void> updateCourse(@PathVariable Long id, @Valid @RequestBody CourseUpdateRequest request) {
        subjectService.updateCourse(id, request);
        return ApiResponse.ok(null);
    }

    @PostMapping(value = "/courses/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @AuditOperation(action = "BULK_COURSE_IMPORT", targetType = "COURSE", targetId = "'bulk'",
        detail = "'dryRun=' + #dryRun + ',success=' + #result.data.successCount + ',failure=' + #result.data.failureCount")
    public ApiResponse<BulkImportResultView> importCourses(@RequestParam("file") MultipartFile file,
                                                            @RequestParam(value = "dryRun", defaultValue = "true") boolean dryRun) {
        return ApiResponse.ok("导入处理完成", importService.importCourses(file, dryRun));
    }

    @GetMapping("/teaching-classes")
    public ApiResponse<List<TeachingClassView>> classes() {
        return ApiResponse.ok(classService.listTeachingClasses());
    }

    @PostMapping("/teaching-classes")
    @AuditOperation(action = "TEACHING_CLASS_CREATE", targetType = "TEACHING_CLASS",
        targetId = "#result.data", detail = "#request.name")
    public ApiResponse<Long> createClass(@Valid @RequestBody TeachingClassCreateRequest request) {
        return ApiResponse.ok(classService.createTeachingClass(request));
    }

    @PutMapping("/teaching-classes/{id}")
    @AuditOperation(action = "TEACHING_CLASS_UPDATE", targetType = "TEACHING_CLASS",
        targetId = "#id", detail = "#request.name")
    public ApiResponse<Void> updateClass(@PathVariable Long id,
                                         @Valid @RequestBody TeachingClassUpdateRequest request) {
        classService.updateTeachingClass(id, request);
        return ApiResponse.ok(null);
    }

    @PostMapping(value = "/teaching-classes/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @AuditOperation(action = "BULK_TEACHING_CLASS_IMPORT", targetType = "TEACHING_CLASS", targetId = "'bulk'",
        detail = "'dryRun=' + #dryRun + ',success=' + #result.data.successCount + ',failure=' + #result.data.failureCount")
    public ApiResponse<BulkImportResultView> importClasses(@RequestParam("file") MultipartFile file,
                                                            @RequestParam(value = "dryRun", defaultValue = "true") boolean dryRun) {
        return ApiResponse.ok("导入处理完成", importService.importTeachingClasses(file, dryRun));
    }

    @PostMapping("/teaching-classes/batch")
    @AuditOperation(action = "BULK_TEACHING_CLASS_OPERATION", targetType = "TEACHING_CLASS",
        targetId = "'bulk'", detail = "#request.status")
    public ApiResponse<Void> operateClasses(@Valid @RequestBody BulkTeachingClassOperationRequest request) {
        for (Long id : request.classIds().stream().distinct().toList()) {
            var value = classMapper.selectById(id);
            if (value == null) throw new BusinessException("教学班不存在: " + id);
            value.setStatus(request.status().trim().toUpperCase());
            classMapper.updateById(value);
        }
        return ApiResponse.ok("批量操作成功", null);
    }
}
