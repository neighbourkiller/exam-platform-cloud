package com.ekusys.exam.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ekusys.exam.admin.dto.BulkImportResultView;
import com.ekusys.exam.admin.dto.BulkUserOperationRequest;
import com.ekusys.exam.admin.dto.UserCreateRequest;
import com.ekusys.exam.admin.service.AdminBulkService;
import com.ekusys.exam.admin.service.AdminCsvImportService;
import com.ekusys.exam.admin.service.RoleAdminService;
import com.ekusys.exam.admin.service.SubjectAdminService;
import com.ekusys.exam.admin.service.TeachingClassAdminService;
import com.ekusys.exam.admin.service.UserAdminService;
import com.ekusys.exam.admin.service.UserProfileSyncService;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.exam.service.ExamService;
import com.ekusys.exam.repository.entity.Paper;
import com.ekusys.exam.repository.entity.Role;
import com.ekusys.exam.repository.entity.Subject;
import com.ekusys.exam.repository.entity.TeachingClass;
import com.ekusys.exam.repository.mapper.PaperMapper;
import com.ekusys.exam.repository.mapper.RoleMapper;
import com.ekusys.exam.repository.mapper.SubjectMapper;
import com.ekusys.exam.repository.mapper.TeachingClassMapper;
import com.ekusys.exam.repository.mapper.UserMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class AdminBulkServiceTest {

    @Mock
    private UserAdminService userAdminService;
    @Mock
    private RoleAdminService roleAdminService;
    @Mock
    private UserProfileSyncService userProfileSyncService;
    @Mock
    private TeachingClassAdminService teachingClassAdminService;
    @Mock
    private SubjectAdminService subjectAdminService;
    @Mock
    private ExamService examService;
    @Mock
    private UserMapper userMapper;
    @Mock
    private PaperMapper paperMapper;
    @Mock
    private RoleMapper roleMapper;
    @Mock
    private SubjectMapper subjectMapper;
    @Mock
    private TeachingClassMapper teachingClassMapper;

    private AdminBulkService adminBulkService;

    @BeforeEach
    void setUp() {
        adminBulkService = new AdminBulkService(
            new AdminCsvImportService(),
            userAdminService,
            roleAdminService,
            userProfileSyncService,
            teachingClassAdminService,
            subjectAdminService,
            examService,
            userMapper,
            paperMapper,
            roleMapper,
            subjectMapper,
            teachingClassMapper
        );
    }

    @Test
    void dryRunUserImportShouldUseCreateValidation() {
        Role role = new Role();
        role.setId(3L);
        role.setCode("STUDENT");
        when(roleMapper.selectOne(any())).thenReturn(role);
        org.mockito.Mockito.doThrow(new BusinessException("请填写密码或配置 APP_DEFAULT_PASSWORD"))
            .when(userAdminService).validateCreateUser(any());

        MockMultipartFile file = csvFile("students.csv", "studentNo,realName\nS001,张三\n");

        BulkImportResultView result = adminBulkService.importUsers(file, "STUDENT", true);

        assertEquals(1, result.getFailureCount());
        assertEquals("请填写密码或配置 APP_DEFAULT_PASSWORD", result.getErrors().getFirst().getMessage());
        verify(userAdminService).validateCreateUser(any());
        verify(userAdminService, never()).createUser(any());
    }

    @Test
    void studentImportShouldUseStudentNoAsUsernameAndPassEnrollmentYear() {
        Role role = new Role();
        role.setId(3L);
        role.setCode("STUDENT");
        when(roleMapper.selectOne(any())).thenReturn(role);
        MockMultipartFile file = csvFile(
            "students.csv",
            "studentNo,realName,password,enrollmentYear,teachingClassIds\n"
                + "S001,张三,123456,2026,\"3301,3302\"\n"
        );

        BulkImportResultView result = adminBulkService.importUsers(file, "STUDENT", true);

        assertEquals(1, result.getSuccessCount());
        ArgumentCaptor<UserCreateRequest> requestCaptor = ArgumentCaptor.forClass(UserCreateRequest.class);
        verify(userAdminService).validateCreateUser(requestCaptor.capture());
        UserCreateRequest request = requestCaptor.getValue();
        assertEquals("S001", request.getStudentNo());
        assertEquals("S001", request.getUsername());
        assertEquals("2026", request.getEnrollmentYear());
        assertEquals(List.of(3301L, 3302L), request.getTeachingClassIds());
    }

    @Test
    void dryRunExamImportShouldRejectPaperClassSubjectMismatch() {
        Paper paper = new Paper();
        paper.setId(10L);
        paper.setSubjectId(5001L);
        when(paperMapper.selectById(10L)).thenReturn(paper);
        TeachingClass teachingClass = new TeachingClass();
        teachingClass.setId(3301L);
        teachingClass.setSubjectId(5002L);
        when(teachingClassMapper.selectBatchIds(List.of(3301L))).thenReturn(List.of(teachingClass));
        MockMultipartFile file = csvFile(
            "exams.csv",
            "name,paperId,startTime,endTime,durationMinutes,passScore,targetClassIds\n"
                + "Java期中,10,2026-05-01 09:00:00,2026-05-01 11:00:00,120,60,3301\n"
        );

        BulkImportResultView result = adminBulkService.importExamSchedules(file, true);

        assertEquals(1, result.getFailureCount());
        assertEquals("目标教学班课程与试卷课程不一致", result.getErrors().getFirst().getMessage());
        verify(examService, never()).createExam(any());
    }

    @Test
    void dryRunCourseImportShouldValidateCreateAndUpdateRows() {
        Subject existing = new Subject();
        existing.setId(5001L);
        when(subjectMapper.selectById(5001L)).thenReturn(existing);
        MockMultipartFile file = csvFile(
            "courses.csv",
            "id,name,description\n"
                + "5001,Java程序设计,更新描述\n"
                + "5002,Spring框架,新增课程\n"
        );

        BulkImportResultView result = adminBulkService.importCourses(file, true);

        assertEquals(2, result.getSuccessCount());
        verify(subjectAdminService).validateUpdateCourse(any(), any());
        verify(subjectAdminService).validateCreateCourse(any());
        verify(subjectAdminService, never()).updateCourse(any(), any());
        verify(subjectAdminService, never()).createCourse(any());
    }

    @Test
    void courseImportShouldRejectDuplicateNameInFile() {
        MockMultipartFile file = csvFile(
            "courses.csv",
            "id,name,description\n"
                + "5001,Java程序设计,基础\n"
                + "5002,Java程序设计,重复\n"
        );

        BulkImportResultView result = adminBulkService.importCourses(file, true);

        assertEquals(1, result.getFailureCount());
        assertEquals("课程名称在导入文件中重复", result.getErrors().getFirst().getMessage());
    }

    @Test
    void operateUsersResetPasswordShouldRejectBlankPassword() {
        BulkUserOperationRequest request = new BulkUserOperationRequest();
        request.setUserIds(List.of(1001L));
        request.setAction("RESET_PASSWORD");
        request.setPassword(" ");

        BusinessException ex = assertThrows(BusinessException.class, () -> adminBulkService.operateUsers(request));

        assertEquals("密码不能为空", ex.getMessage());
        verify(userAdminService, never()).resetPassword(any(), any());
    }

    private MockMultipartFile csvFile(String filename, String content) {
        return new MockMultipartFile("file", filename, "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }
}
