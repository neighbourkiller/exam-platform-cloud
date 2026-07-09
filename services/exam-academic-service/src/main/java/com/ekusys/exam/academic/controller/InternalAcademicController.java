package com.ekusys.exam.academic.controller;

import com.ekusys.exam.academic.api.ClassRosterView;
import com.ekusys.exam.academic.api.SubjectSummary;
import com.ekusys.exam.academic.api.AcademicUserProfileCommand;
import com.ekusys.exam.academic.api.AcademicUserSummary;
import com.ekusys.exam.academic.api.TeachingClassBatchRequest;
import com.ekusys.exam.academic.api.TeachingClassSummary;
import com.ekusys.exam.academic.service.AcademicUserProfileService;
import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.repository.entity.StudentTeachingClass;
import com.ekusys.exam.repository.entity.Subject;
import com.ekusys.exam.repository.entity.TeachingClass;
import com.ekusys.exam.repository.mapper.StudentTeachingClassMapper;
import com.ekusys.exam.repository.mapper.SubjectMapper;
import com.ekusys.exam.repository.mapper.TeachingClassMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequestMapping("/internal/v1/academic")
public class InternalAcademicController {
    private final SubjectMapper subjectMapper; private final TeachingClassMapper classMapper;
    private final StudentTeachingClassMapper relationMapper;
    private final AcademicUserProfileService profileService;
    public InternalAcademicController(SubjectMapper subjectMapper, TeachingClassMapper classMapper, StudentTeachingClassMapper relationMapper,
                                      AcademicUserProfileService profileService) {
        this.subjectMapper=subjectMapper; this.classMapper=classMapper; this.relationMapper=relationMapper; this.profileService=profileService;
    }
    @GetMapping("/subjects") public ApiResponse<List<SubjectSummary>> subjects() {
        return ApiResponse.ok(subjectMapper.selectList(new LambdaQueryWrapper<Subject>().orderByAsc(Subject::getId)).stream()
            .map(s -> new SubjectSummary(s.getId(),s.getName(),s.getDescription())).toList());
    }
    @GetMapping("/subjects/{id}") public ApiResponse<SubjectSummary> subject(@PathVariable Long id) {
        Subject s=subjectMapper.selectById(id); return ApiResponse.ok(s==null?null:new SubjectSummary(s.getId(),s.getName(),s.getDescription()));
    }
    @GetMapping("/classes/{id}/roster") public ApiResponse<ClassRosterView> roster(@PathVariable Long id) {
        TeachingClass c=classMapper.selectById(id); if(c==null) return ApiResponse.ok(null);
        List<Long> students=relationMapper.selectList(new LambdaQueryWrapper<StudentTeachingClass>()
            .eq(StudentTeachingClass::getTeachingClassId,id).eq(StudentTeachingClass::getEnrollStatus,"ACTIVE"))
            .stream().map(StudentTeachingClass::getStudentId).toList();
        return ApiResponse.ok(new ClassRosterView(id,c.getName(),c.getSubjectId(),c.getTeacherId(),students,0));
    }
    @PostMapping("/classes/summaries") public ApiResponse<List<TeachingClassSummary>> classSummaries(
        @RequestBody TeachingClassBatchRequest request) {
        List<Long> ids = request == null || request.classIds() == null
            ? List.of() : request.classIds().stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return ApiResponse.ok(List.of());
        List<TeachingClass> classes = classMapper.selectBatchIds(ids);
        List<Long> subjectIds = classes.stream().map(TeachingClass::getSubjectId)
            .filter(java.util.Objects::nonNull).distinct().toList();
        java.util.Map<Long, Subject> subjects = subjectIds.isEmpty() ? java.util.Map.of()
            : subjectMapper.selectBatchIds(subjectIds).stream()
                .collect(java.util.stream.Collectors.toMap(Subject::getId, item -> item));
        return ApiResponse.ok(classes.stream().map(item -> new TeachingClassSummary(
            item.getId(), item.getName(), item.getSubjectId(),
            subjects.get(item.getSubjectId()) == null ? null : subjects.get(item.getSubjectId()).getName(),
            item.getTeacherId(), item.getTerm(), item.getStatus(), item.getCapacity()
        )).toList());
    }
    @PostMapping("/users/{id}/profile") public ApiResponse<Void> synchronizeProfile(@PathVariable Long id,
                                                                                     @RequestBody AcademicUserProfileCommand command) {
        profileService.synchronize(id, command); return ApiResponse.ok(null);
    }
    @PostMapping("/users/profile/validate") public ApiResponse<Void> validateProfile(
        @RequestBody AcademicUserProfileCommand command) {
        profileService.validate(null, command); return ApiResponse.ok(null);
    }
    @DeleteMapping("/users/{id}/profile") public ApiResponse<Void> deleteProfile(@PathVariable Long id) {
        profileService.delete(id); return ApiResponse.ok(null);
    }
    @PostMapping("/users/summaries") public ApiResponse<List<AcademicUserSummary>> userSummaries(@RequestBody List<Long> ids) {
        return ApiResponse.ok(profileService.summaries(ids));
    }
}
