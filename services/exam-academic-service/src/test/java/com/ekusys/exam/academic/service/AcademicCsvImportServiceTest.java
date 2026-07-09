package com.ekusys.exam.academic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ekusys.exam.academic.client.IamUserClient;
import com.ekusys.exam.admin.dto.CourseCreateRequest;
import com.ekusys.exam.admin.dto.TeachingClassCreateRequest;
import com.ekusys.exam.admin.service.SubjectAdminService;
import com.ekusys.exam.admin.service.TeachingClassAdminService;
import com.ekusys.exam.common.api.ApiResponse;
import com.ekusys.exam.iam.api.UserSummary;
import com.ekusys.exam.importing.BulkImportResultView;
import com.ekusys.exam.importing.CsvImportParser;
import com.ekusys.exam.repository.mapper.SubjectMapper;
import com.ekusys.exam.repository.mapper.TeachingClassMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class AcademicCsvImportServiceTest {
    private SubjectAdminService subjects;
    private TeachingClassAdminService classes;
    private SubjectMapper subjectMapper;
    private TeachingClassMapper classMapper;
    private IamUserClient iam;
    private AcademicCsvImportService service;

    @BeforeEach
    void setup() {
        subjects = mock(SubjectAdminService.class);
        classes = mock(TeachingClassAdminService.class);
        subjectMapper = mock(SubjectMapper.class);
        classMapper = mock(TeachingClassMapper.class);
        iam = mock(IamUserClient.class);
        service = new AcademicCsvImportService(new CsvImportParser(), subjects, classes, subjectMapper, classMapper, iam);
    }

    @Test
    void courseDryRunUsesValidationWithoutWriting() {
        BulkImportResultView result = service.importCourses(csv("id,name,description\n1,Java,基础\n"), true);

        assertThat(result.successCount()).isEqualTo(1);
        verify(subjects).validateCreateCourse(any(CourseCreateRequest.class));
        verify(subjects, never()).createCourse(any());
    }

    @Test
    void classDryRunResolvesTeacherUsernameAndValidates() {
        when(iam.byUsername("teacher01")).thenReturn(ApiResponse.ok(
            new UserSummary(9L, "teacher01", "李老师", true, List.of("TEACHER"))));
        BulkImportResultView result = service.importTeachingClasses(csv(
            "id,name,subjectId,teacherId,teacherUsername,term,status,capacity\n"
                + "1001,Java一班,1,,teacher01,2026春,ONGOING,60\n"), true);

        assertThat(result.successCount()).isEqualTo(1);
        verify(classes).validateCreateTeachingClass(any(TeachingClassCreateRequest.class));
        verify(classes, never()).createTeachingClass(any());
    }

    private MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "data.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }
}
