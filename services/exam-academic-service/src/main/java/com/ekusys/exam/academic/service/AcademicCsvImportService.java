package com.ekusys.exam.academic.service;

import com.ekusys.exam.academic.client.IamUserClient;
import com.ekusys.exam.admin.dto.CourseCreateRequest;
import com.ekusys.exam.admin.dto.CourseUpdateRequest;
import com.ekusys.exam.admin.dto.TeachingClassCreateRequest;
import com.ekusys.exam.admin.dto.TeachingClassUpdateRequest;
import com.ekusys.exam.admin.service.SubjectAdminService;
import com.ekusys.exam.admin.service.TeachingClassAdminService;
import com.ekusys.exam.iam.api.UserSummary;
import com.ekusys.exam.importing.BulkImportResultView;
import com.ekusys.exam.importing.BulkImportRowErrorView;
import com.ekusys.exam.importing.CsvImportParser;
import com.ekusys.exam.importing.CsvImportValues;
import com.ekusys.exam.importing.RowImportException;
import com.ekusys.exam.repository.entity.Subject;
import com.ekusys.exam.repository.entity.TeachingClass;
import com.ekusys.exam.repository.mapper.SubjectMapper;
import com.ekusys.exam.repository.mapper.TeachingClassMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AcademicCsvImportService {
    private final CsvImportParser parser;
    private final SubjectAdminService subjectService;
    private final TeachingClassAdminService classService;
    private final SubjectMapper subjectMapper;
    private final TeachingClassMapper classMapper;
    private final IamUserClient iam;

    public AcademicCsvImportService(CsvImportParser parser,
                                    SubjectAdminService subjectService,
                                    TeachingClassAdminService classService,
                                    SubjectMapper subjectMapper,
                                    TeachingClassMapper classMapper,
                                    IamUserClient iam) {
        this.parser = parser;
        this.subjectService = subjectService;
        this.classService = classService;
        this.subjectMapper = subjectMapper;
        this.classMapper = classMapper;
        this.iam = iam;
    }

    public BulkImportResultView importCourses(MultipartFile file, boolean dryRun) {
        CsvImportParser.ParsedCsv csv = parser.parse(file);
        List<BulkImportRowErrorView> errors = new ArrayList<>();
        LinkedHashSet<Long> seenIds = new LinkedHashSet<>();
        LinkedHashSet<String> seenNames = new LinkedHashSet<>();
        int success = 0;
        for (CsvImportParser.CsvRow row : csv.rows()) {
            try {
                CsvImportValues.require(row, "name");
                Long id = CsvImportValues.longValue(row.value("id"), "id");
                String name = row.value("name").trim();
                if (id != null && !seenIds.add(id)) {
                    throw new RowImportException("id", "课程ID在导入文件中重复", row.value("id"));
                }
                if (!seenNames.add(name)) {
                    throw new RowImportException("name", "课程名称在导入文件中重复", name);
                }
                Subject existing = id == null ? null : subjectMapper.selectById(id);
                if (existing == null) {
                    CourseCreateRequest request = new CourseCreateRequest();
                    request.setId(id); request.setName(name);
                    request.setDescription(CsvImportValues.nullable(row.value("description")));
                    if (dryRun) subjectService.validateCreateCourse(request); else subjectService.createCourse(request);
                } else {
                    CourseUpdateRequest request = new CourseUpdateRequest();
                    request.setName(name); request.setDescription(CsvImportValues.nullable(row.value("description")));
                    if (dryRun) subjectService.validateUpdateCourse(id, request); else subjectService.updateCourse(id, request);
                }
                success++;
            } catch (Exception exception) {
                errors.add(error(row, exception));
            }
        }
        return BulkImportResultView.of(csv.rows().size(), success, dryRun, errors);
    }

    public BulkImportResultView importTeachingClasses(MultipartFile file, boolean dryRun) {
        CsvImportParser.ParsedCsv csv = parser.parse(file);
        List<BulkImportRowErrorView> errors = new ArrayList<>();
        LinkedHashSet<Long> seenIds = new LinkedHashSet<>();
        LinkedHashSet<String> seenNames = new LinkedHashSet<>();
        int success = 0;
        for (CsvImportParser.CsvRow row : csv.rows()) {
            try {
                CsvImportValues.require(row, "name", "subjectId", "term");
                Long id = CsvImportValues.longValue(row.value("id"), "id");
                if (id != null && !seenIds.add(id)) {
                    throw new RowImportException("id", "教学班ID在导入文件中重复", row.value("id"));
                }
                String name = row.value("name").trim();
                if (!seenNames.add(name)) {
                    throw new RowImportException("name", "教学班名称在导入文件中重复", name);
                }
                Long teacherId = resolveTeacher(row);
                Long subjectId = CsvImportValues.longValue(row.value("subjectId"), "subjectId");
                TeachingClass existing = id == null ? null : classMapper.selectById(id);
                if (existing == null) {
                    TeachingClassCreateRequest request = new TeachingClassCreateRequest();
                    request.setId(id); request.setName(name); request.setSubjectId(subjectId);
                    request.setTeacherId(teacherId); request.setTerm(row.value("term"));
                    request.setStatus(CsvImportValues.nullable(row.value("status")));
                    request.setCapacity(CsvImportValues.intValue(row.value("capacity"), "capacity"));
                    if (dryRun) classService.validateCreateTeachingClass(request); else classService.createTeachingClass(request);
                } else {
                    TeachingClassUpdateRequest request = new TeachingClassUpdateRequest();
                    request.setName(name); request.setSubjectId(subjectId); request.setTeacherId(teacherId);
                    request.setTerm(row.value("term")); request.setStatus(CsvImportValues.nullable(row.value("status")));
                    request.setCapacity(CsvImportValues.intValue(row.value("capacity"), "capacity"));
                    if (dryRun) classService.validateUpdateTeachingClass(id, request); else classService.updateTeachingClass(id, request);
                }
                success++;
            } catch (Exception exception) {
                errors.add(error(row, exception));
            }
        }
        return BulkImportResultView.of(csv.rows().size(), success, dryRun, errors);
    }

    private Long resolveTeacher(CsvImportParser.CsvRow row) {
        Long teacherId = CsvImportValues.longValue(row.value("teacherId"), "teacherId");
        if (teacherId != null) return teacherId;
        String username = CsvImportValues.nullable(row.value("teacherUsername"));
        if (username == null) {
            throw new RowImportException("teacherId", "teacherId 或 teacherUsername 必填", null);
        }
        UserSummary teacher = iam.byUsername(username).getData();
        if (teacher == null) {
            throw new RowImportException("teacherUsername", "教师用户不存在", username);
        }
        return teacher.id();
    }

    private BulkImportRowErrorView error(CsvImportParser.CsvRow row, Exception exception) {
        if (exception instanceof RowImportException value) {
            return new BulkImportRowErrorView(row.rowNumber(), value.getField(), value.getMessage(), value.getRawValue());
        }
        return new BulkImportRowErrorView(row.rowNumber(), null,
            exception.getMessage() == null ? "处理失败" : exception.getMessage(), null);
    }
}
