package com.ekusys.exam.exam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ekusys.exam.common.enums.SessionStatus;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.SecurityUtils;
import com.ekusys.exam.exam.dto.ProctoringDispositionRequest;
import com.ekusys.exam.exam.dto.ProctoringDispositionView;
import com.ekusys.exam.exam.dto.ProctoringOverviewView;
import com.ekusys.exam.exam.dto.ProctoringStudentView;
import com.ekusys.exam.exam.dto.ProctoringStudentTimelineView;
import com.ekusys.exam.exam.service.ExamPermissionService;
import com.ekusys.exam.exam.service.ExamProctoringPolicyService;
import com.ekusys.exam.exam.service.ExamProctoringService;
import com.ekusys.exam.repository.entity.AntiCheatEvent;
import com.ekusys.exam.repository.entity.Exam;
import com.ekusys.exam.repository.entity.ExamSession;
import com.ekusys.exam.repository.entity.ExamTargetClass;
import com.ekusys.exam.repository.entity.ProctoringDisposition;
import com.ekusys.exam.repository.entity.StudentTeachingClass;
import com.ekusys.exam.repository.entity.Submission;
import com.ekusys.exam.repository.entity.TeachingClass;
import com.ekusys.exam.repository.entity.User;
import com.ekusys.exam.repository.mapper.AntiCheatEventMapper;
import com.ekusys.exam.repository.mapper.ExamMapper;
import com.ekusys.exam.repository.mapper.ExamSessionMapper;
import com.ekusys.exam.repository.mapper.ExamTargetClassMapper;
import com.ekusys.exam.repository.mapper.ProctoringDispositionMapper;
import com.ekusys.exam.repository.mapper.StudentTeachingClassMapper;
import com.ekusys.exam.repository.mapper.SubmissionMapper;
import com.ekusys.exam.repository.mapper.TeachingClassMapper;
import com.ekusys.exam.repository.mapper.UserMapper;
import java.time.LocalDateTime;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
class ExamProctoringServiceTest {

    @Mock
    private ExamMapper examMapper;

    @Mock
    private ExamTargetClassMapper examTargetClassMapper;

    @Mock
    private StudentTeachingClassMapper studentTeachingClassMapper;

    @Mock
    private TeachingClassMapper teachingClassMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private ExamSessionMapper examSessionMapper;

    @Mock
    private SubmissionMapper submissionMapper;

    @Mock
    private AntiCheatEventMapper antiCheatEventMapper;

    @Mock
    private ProctoringDispositionMapper proctoringDispositionMapper;

    private ExamProctoringService examProctoringService;

    @BeforeEach
    void setUp() {
        examProctoringService = new ExamProctoringService(
            examMapper,
            examTargetClassMapper,
            studentTeachingClassMapper,
            teachingClassMapper,
            userMapper,
            examSessionMapper,
            submissionMapper,
            antiCheatEventMapper,
            proctoringDispositionMapper,
            new ExamPermissionService(userMapper, examTargetClassMapper, teachingClassMapper),
            new ExamProctoringPolicyService(new ObjectMapper())
        );
    }

    @Test
    void overviewShouldAggregateRiskLevelsAndSnapshotAlerts() {
        Exam exam = buildExam();
        when(examMapper.selectById(1L)).thenReturn(exam);
        when(userMapper.selectRoleCodes(200L)).thenReturn(List.of("TEACHER"));
        when(examTargetClassMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(buildTargetClass(1L, 11L)));
        when(teachingClassMapper.selectBatchIds(any())).thenReturn(List.of(buildTeachingClass(11L, "一班")));
        when(studentTeachingClassMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            buildEnrollment(1001L, 11L),
            buildEnrollment(1002L, 11L)
        ));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(
            buildUser(1001L, "alice", "Alice"),
            buildUser(1002L, "bob", "Bob")
        ));
        when(examSessionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            buildSession(1001L, SessionStatus.ANSWERING.name(), LocalDateTime.now().minusMinutes(5), null, LocalDateTime.now().minusSeconds(60)),
            buildSession(1002L, SessionStatus.SUBMITTED.name(), LocalDateTime.now().minusMinutes(8), LocalDateTime.now().minusMinutes(1), LocalDateTime.now().minusMinutes(1))
        ));
        when(submissionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            buildSubmission(1002L, LocalDateTime.now().minusMinutes(1))
        ));
        when(antiCheatEventMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            buildEvent(1L, 1001L, "WINDOW_BLUR", LocalDateTime.now().minusMinutes(4), 6_000L),
            buildEvent(1L, 1001L, "TAB_HIDDEN", LocalDateTime.now().minusMinutes(3), 35_000L),
            buildEvent(1L, 1001L, "COPY_ATTEMPT", LocalDateTime.now().minusMinutes(2), 0L),
            buildEvent(1L, 1002L, "FULLSCREEN_EXIT", LocalDateTime.now().minusMinutes(1), 0L)
        ));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(200L);

            ProctoringOverviewView overview = examProctoringService.getOverview(1L);

            assertNotNull(overview);
            assertEquals(2, overview.getTotalStudents());
            assertEquals(1, overview.getAnsweringStudents());
            assertEquals(1, overview.getHighRiskCount());
            assertEquals(1, overview.getLowRiskCount());
            assertEquals(0, overview.getMediumRiskCount());
            assertEquals(1, overview.getSnapshotAlertCount());
            assertEquals(2, overview.getPendingReviewDispositionCount());
            assertEquals(0, overview.getConfirmedDispositionCount());
            assertEquals(0, overview.getFalsePositiveDispositionCount());
            assertEquals(0, overview.getClosedDispositionCount());
            assertFalse(overview.getRecentEvents().isEmpty());
            assertEquals("FULLSCREEN_EXIT", overview.getRecentEvents().get(0).getEventType());
        }
    }

    @Test
    void timelineShouldReturnStudentRiskSummaryAndEvents() {
        Exam exam = buildExam();
        when(examMapper.selectById(1L)).thenReturn(exam);
        when(userMapper.selectRoleCodes(200L)).thenReturn(List.of("TEACHER"));
        when(examTargetClassMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(buildTargetClass(1L, 11L)));
        when(teachingClassMapper.selectBatchIds(any())).thenReturn(List.of(buildTeachingClass(11L, "一班")));
        when(studentTeachingClassMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(buildEnrollment(1001L, 11L)));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(buildUser(1001L, "alice", "Alice")));
        when(examSessionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            buildSession(1001L, SessionStatus.ANSWERING.name(), LocalDateTime.now().minusMinutes(5), null, LocalDateTime.now().minusSeconds(80))
        ));
        when(submissionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(antiCheatEventMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            buildEvent(1L, 1001L, "WINDOW_BLUR", LocalDateTime.now().minusMinutes(4), 31_000L),
            buildEvent(1L, 1001L, "WINDOW_BLUR", LocalDateTime.now().minusMinutes(3), 7_000L),
            buildEvent(1L, 1001L, "WINDOW_BLUR", LocalDateTime.now().minusMinutes(2), 0L),
            buildEvent(1L, 1001L, "PASTE_ATTEMPT", LocalDateTime.now().minusMinutes(1), 0L)
        ));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(200L);

            ProctoringStudentTimelineView timeline = examProctoringService.getStudentTimeline(1L, 1001L);

            assertNotNull(timeline);
            assertEquals("Alice", timeline.getStudentName());
            assertEquals("HIGH", timeline.getRiskLevel());
            assertTrue(timeline.getSnapshotAlert());
            assertTrue(timeline.getLongOffscreen());
            assertEquals(4, timeline.getEventCount());
            assertEquals(2, timeline.getEventTypeStats().size());
            assertEquals("PASTE_ATTEMPT", timeline.getEvents().get(0).getEventType());
            assertEquals("PENDING_REVIEW", timeline.getDisposition().getStatus());
        }
    }

    @Test
    void timelineShouldReturnEvidenceJsonAndHighRiskForEvidenceEvents() {
        Exam exam = buildExam();
        when(examMapper.selectById(1L)).thenReturn(exam);
        when(userMapper.selectRoleCodes(200L)).thenReturn(List.of("TEACHER"));
        when(examTargetClassMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(buildTargetClass(1L, 11L)));
        when(teachingClassMapper.selectBatchIds(any())).thenReturn(List.of(buildTeachingClass(11L, "一班")));
        when(studentTeachingClassMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(buildEnrollment(1001L, 11L)));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(buildUser(1001L, "alice", "Alice")));
        when(examSessionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            buildSession(1001L, SessionStatus.ANSWERING.name(), LocalDateTime.now().minusMinutes(5), null, LocalDateTime.now())
        ));
        when(submissionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        AntiCheatEvent evidenceEvent = buildEvent(1L, 1001L, "SCREEN_SHARE_ENDED", LocalDateTime.now().minusMinutes(1), 0L);
        evidenceEvent.setEvidenceJson("[{\"source\":\"SCREEN\",\"url\":\"http://localhost/evidence.jpg\"}]");
        when(antiCheatEventMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(evidenceEvent));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(200L);

            ProctoringStudentTimelineView timeline = examProctoringService.getStudentTimeline(1L, 1001L);

            assertEquals("HIGH", timeline.getRiskLevel());
            assertEquals(evidenceEvent.getEvidenceJson(), timeline.getEvents().get(0).getEvidenceJson());
        }
    }

    @Test
    void timelineShouldTreatNavigationLeaveAttemptAsHighRisk() {
        Exam exam = buildExam();
        when(examMapper.selectById(1L)).thenReturn(exam);
        when(userMapper.selectRoleCodes(200L)).thenReturn(List.of("TEACHER"));
        when(examTargetClassMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(buildTargetClass(1L, 11L)));
        when(teachingClassMapper.selectBatchIds(any())).thenReturn(List.of(buildTeachingClass(11L, "一班")));
        when(studentTeachingClassMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(buildEnrollment(1001L, 11L)));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(buildUser(1001L, "alice", "Alice")));
        when(examSessionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            buildSession(1001L, SessionStatus.ANSWERING.name(), LocalDateTime.now().minusMinutes(5), null, LocalDateTime.now())
        ));
        when(submissionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(antiCheatEventMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            buildEvent(1L, 1001L, "NAVIGATION_LEAVE_ATTEMPT", LocalDateTime.now().minusMinutes(1), 0L)
        ));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(200L);

            ProctoringStudentTimelineView timeline = examProctoringService.getStudentTimeline(1L, 1001L);

            assertEquals("HIGH", timeline.getRiskLevel());
            assertEquals("NAVIGATION_LEAVE_ATTEMPT", timeline.getLatestEventType());
        }
    }

    @Test
    void timelineShouldTreatLongInactivityAsMediumRisk() {
        Exam exam = buildExam();
        when(examMapper.selectById(1L)).thenReturn(exam);
        when(userMapper.selectRoleCodes(200L)).thenReturn(List.of("TEACHER"));
        when(examTargetClassMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(buildTargetClass(1L, 11L)));
        when(teachingClassMapper.selectBatchIds(any())).thenReturn(List.of(buildTeachingClass(11L, "一班")));
        when(studentTeachingClassMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(buildEnrollment(1001L, 11L)));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(buildUser(1001L, "alice", "Alice")));
        when(examSessionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            buildSession(1001L, SessionStatus.ANSWERING.name(), LocalDateTime.now().minusMinutes(5), null, LocalDateTime.now())
        ));
        when(submissionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(antiCheatEventMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            buildEvent(1L, 1001L, "LONG_INACTIVITY", LocalDateTime.now().minusMinutes(1), 190_000L)
        ));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(200L);

            ProctoringStudentTimelineView timeline = examProctoringService.getStudentTimeline(1L, 1001L);

            assertEquals("MEDIUM", timeline.getRiskLevel());
            assertEquals("LONG_INACTIVITY", timeline.getLatestEventType());
        }
    }

    @Test
    void listStudentsShouldReturnDispositionInfo() {
        Exam exam = buildExam();
        when(examMapper.selectById(1L)).thenReturn(exam);
        when(userMapper.selectRoleCodes(200L)).thenReturn(List.of("TEACHER"));
        when(examTargetClassMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(buildTargetClass(1L, 11L)));
        when(teachingClassMapper.selectBatchIds(any())).thenReturn(List.of(buildTeachingClass(11L, "一班")));
        when(studentTeachingClassMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(buildEnrollment(1001L, 11L)));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(buildUser(1001L, "alice", "Alice")));
        when(examSessionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(submissionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(antiCheatEventMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(proctoringDispositionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            disposition(1L, 1001L, "CONFIRMED", "已人工确认")
        ));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(200L);

            List<ProctoringStudentView> result = examProctoringService.listStudents(1L);

            assertEquals(1, result.size());
            assertEquals("CONFIRMED", result.get(0).getDisposition().getStatus());
            assertEquals("已人工确认", result.get(0).getDisposition().getRemark());
        }
    }

    @Test
    void overviewShouldCountDispositionStatuses() {
        Exam exam = buildExam();
        when(examMapper.selectById(1L)).thenReturn(exam);
        when(userMapper.selectRoleCodes(200L)).thenReturn(List.of("TEACHER"));
        when(examTargetClassMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(buildTargetClass(1L, 11L)));
        when(teachingClassMapper.selectBatchIds(any())).thenReturn(List.of(buildTeachingClass(11L, "一班")));
        when(studentTeachingClassMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            buildEnrollment(1001L, 11L),
            buildEnrollment(1002L, 11L),
            buildEnrollment(1003L, 11L),
            buildEnrollment(1004L, 11L)
        ));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(
            buildUser(1001L, "alice", "Alice"),
            buildUser(1002L, "bob", "Bob"),
            buildUser(1003L, "carl", "Carl"),
            buildUser(1004L, "dina", "Dina")
        ));
        when(examSessionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(submissionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(antiCheatEventMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(proctoringDispositionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            disposition(1L, 1001L, "CONFIRMED", null),
            disposition(1L, 1002L, "FALSE_POSITIVE", null),
            disposition(1L, 1003L, "CLOSED", null)
        ));

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(200L);

            ProctoringOverviewView overview = examProctoringService.getOverview(1L);

            assertEquals(1, overview.getPendingReviewDispositionCount());
            assertEquals(1, overview.getConfirmedDispositionCount());
            assertEquals(1, overview.getFalsePositiveDispositionCount());
            assertEquals(1, overview.getClosedDispositionCount());
        }
    }

    @Test
    void teacherShouldAccessProctoringForOwnClassEvenWhenAdminPublishedExam() {
        Exam exam = buildExam();
        exam.setPublisherId(999L);
        when(examMapper.selectById(1L)).thenReturn(exam);
        when(userMapper.selectRoleCodes(200L)).thenReturn(List.of("TEACHER"));
        when(examTargetClassMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(buildTargetClass(1L, 11L)));
        when(teachingClassMapper.selectBatchIds(any())).thenReturn(List.of(buildTeachingClass(11L, "一班", 200L)));
        when(studentTeachingClassMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(buildEnrollment(1001L, 11L)));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(buildUser(1001L, "alice", "Alice")));
        when(examSessionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(submissionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(antiCheatEventMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(200L);

            ProctoringOverviewView overview = examProctoringService.getOverview(1L);

            assertNotNull(overview);
            assertEquals(1, overview.getTotalStudents());
        }
    }

    @Test
    void updateStudentDispositionShouldInsertNewRecord() {
        stubOneStudentProctoringContext();
        when(proctoringDispositionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        ProctoringDispositionRequest request = dispositionRequest("CONFIRMED", "  已电话核查  ");

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(200L);
            mocked.when(SecurityUtils::getCurrentUsername).thenReturn("teacher1");

            ProctoringDispositionView result = examProctoringService.updateStudentDisposition(1L, 1001L, request);

            ArgumentCaptor<ProctoringDisposition> captor = ArgumentCaptor.forClass(ProctoringDisposition.class);
            verify(proctoringDispositionMapper).insert(captor.capture());
            assertEquals(1L, captor.getValue().getExamId());
            assertEquals(1001L, captor.getValue().getStudentId());
            assertEquals("CONFIRMED", captor.getValue().getStatus());
            assertEquals("已电话核查", captor.getValue().getRemark());
            assertEquals(200L, captor.getValue().getHandledBy());
            assertNotNull(captor.getValue().getHandledAt());
            assertEquals("CONFIRMED", result.getStatus());
            assertEquals("teacher1", result.getHandledByName());
        }
    }

    @Test
    void updateStudentDispositionShouldOverwriteExistingRecord() {
        stubOneStudentProctoringContext();
        ProctoringDisposition existing = disposition(1L, 1001L, "PENDING_REVIEW", null);
        existing.setId(88L);
        when(proctoringDispositionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        ProctoringDispositionRequest request = dispositionRequest("FALSE_POSITIVE", "网络波动误报");

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(200L);
            mocked.when(SecurityUtils::getCurrentUsername).thenReturn("teacher1");

            examProctoringService.updateStudentDisposition(1L, 1001L, request);

            ArgumentCaptor<ProctoringDisposition> captor = ArgumentCaptor.forClass(ProctoringDisposition.class);
            verify(proctoringDispositionMapper).updateById(captor.capture());
            assertEquals(88L, captor.getValue().getId());
            assertEquals("FALSE_POSITIVE", captor.getValue().getStatus());
            assertEquals("网络波动误报", captor.getValue().getRemark());
        }
    }

    @Test
    void updateStudentDispositionShouldRetryAsUpdateWhenConcurrentInsertWins() {
        stubOneStudentProctoringContext();
        when(proctoringDispositionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(proctoringDispositionMapper.insert(any(ProctoringDisposition.class))).thenThrow(new DuplicateKeyException("duplicate"));

        ProctoringDispositionRequest request = dispositionRequest("CLOSED", "并发保存兜底");

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(200L);
            mocked.when(SecurityUtils::getCurrentUsername).thenReturn("teacher1");

            ProctoringDispositionView result = examProctoringService.updateStudentDisposition(1L, 1001L, request);

            ArgumentCaptor<ProctoringDisposition> captor = ArgumentCaptor.forClass(ProctoringDisposition.class);
            verify(proctoringDispositionMapper).update(captor.capture(), any());
            assertEquals("CLOSED", captor.getValue().getStatus());
            assertEquals("并发保存兜底", captor.getValue().getRemark());
            assertEquals(200L, captor.getValue().getHandledBy());
            assertEquals("CLOSED", result.getStatus());
        }
    }

    @Test
    void updateStudentDispositionShouldRejectInvalidStatus() {
        stubOneStudentProctoringContext();

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(200L);

            assertThrows(BusinessException.class,
                () -> examProctoringService.updateStudentDisposition(1L, 1001L, dispositionRequest("INVALID", null)));
        }
    }

    @Test
    void updateStudentDispositionShouldRejectNonTargetStudent() {
        Exam exam = buildExam();
        when(examMapper.selectById(1L)).thenReturn(exam);
        when(userMapper.selectRoleCodes(200L)).thenReturn(List.of("TEACHER"));
        when(examTargetClassMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(buildTargetClass(1L, 11L)));
        when(studentTeachingClassMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(200L);

            assertThrows(BusinessException.class,
                () -> examProctoringService.updateStudentDisposition(1L, 1001L, dispositionRequest("CLOSED", null)));
        }
    }

    @Test
    void updateStudentDispositionShouldRejectTooLongRemark() {
        stubOneStudentProctoringContext();

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(200L);

            assertThrows(BusinessException.class,
                () -> examProctoringService.updateStudentDisposition(1L, 1001L, dispositionRequest("CLOSED", "x".repeat(501))));
        }
    }

    private Exam buildExam() {
        Exam exam = new Exam();
        exam.setId(1L);
        exam.setName("Java阶段测验");
        exam.setStatus("ONGOING");
        exam.setPublisherId(200L);
        exam.setStartTime(LocalDateTime.now().minusMinutes(10));
        exam.setEndTime(LocalDateTime.now().plusMinutes(50));
        return exam;
    }

    private void stubOneStudentProctoringContext() {
        Exam exam = buildExam();
        when(examMapper.selectById(1L)).thenReturn(exam);
        when(userMapper.selectRoleCodes(200L)).thenReturn(List.of("TEACHER"));
        when(examTargetClassMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(buildTargetClass(1L, 11L)));
        when(studentTeachingClassMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
    }

    private ExamTargetClass buildTargetClass(Long examId, Long classId) {
        ExamTargetClass targetClass = new ExamTargetClass();
        targetClass.setExamId(examId);
        targetClass.setClassId(classId);
        return targetClass;
    }

    private TeachingClass buildTeachingClass(Long id, String name) {
        return buildTeachingClass(id, name, null);
    }

    private TeachingClass buildTeachingClass(Long id, String name, Long teacherId) {
        TeachingClass teachingClass = new TeachingClass();
        teachingClass.setId(id);
        teachingClass.setName(name);
        teachingClass.setTeacherId(teacherId);
        return teachingClass;
    }

    private StudentTeachingClass buildEnrollment(Long studentId, Long classId) {
        StudentTeachingClass relation = new StudentTeachingClass();
        relation.setStudentId(studentId);
        relation.setTeachingClassId(classId);
        relation.setEnrollStatus("ACTIVE");
        return relation;
    }

    private User buildUser(Long id, String username, String realName) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setRealName(realName);
        return user;
    }

    private ExamSession buildSession(Long studentId,
                                     String status,
                                     LocalDateTime startTime,
                                     LocalDateTime endTime,
                                     LocalDateTime lastSnapshotTime) {
        ExamSession session = new ExamSession();
        session.setId(studentId);
        session.setExamId(1L);
        session.setStudentId(studentId);
        session.setStatus(status);
        session.setStartTime(startTime);
        session.setEndTime(endTime);
        session.setLastSnapshotTime(lastSnapshotTime);
        session.setCreateTime(startTime);
        session.setUpdateTime(lastSnapshotTime);
        return session;
    }

    private Submission buildSubmission(Long studentId, LocalDateTime submittedAt) {
        Submission submission = new Submission();
        submission.setId(studentId);
        submission.setExamId(1L);
        submission.setStudentId(studentId);
        submission.setSubmittedAt(submittedAt);
        submission.setUpdateTime(submittedAt);
        return submission;
    }

    private AntiCheatEvent buildEvent(Long examId,
                                      Long studentId,
                                      String type,
                                      LocalDateTime eventTime,
                                      Long durationMs) {
        AntiCheatEvent event = new AntiCheatEvent();
        event.setId(System.nanoTime());
        event.setExamId(examId);
        event.setStudentId(studentId);
        event.setEventType(type);
        event.setEventTime(eventTime);
        event.setDurationMs(durationMs);
        return event;
    }

    private ProctoringDisposition disposition(Long examId, Long studentId, String status, String remark) {
        ProctoringDisposition disposition = new ProctoringDisposition();
        disposition.setExamId(examId);
        disposition.setStudentId(studentId);
        disposition.setStatus(status);
        disposition.setRemark(remark);
        disposition.setHandledAt(LocalDateTime.now());
        return disposition;
    }

    private ProctoringDispositionRequest dispositionRequest(String status, String remark) {
        ProctoringDispositionRequest request = new ProctoringDispositionRequest();
        request.setStatus(status);
        request.setRemark(remark);
        return request;
    }
}
