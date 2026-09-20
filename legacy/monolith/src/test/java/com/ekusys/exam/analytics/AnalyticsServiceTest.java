package com.ekusys.exam.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ekusys.exam.analytics.dto.ExamOverviewItem;
import com.ekusys.exam.analytics.dto.StudentScoreItem;
import com.ekusys.exam.analytics.dto.WrongTopicItem;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.SecurityUtils;
import com.ekusys.exam.analytics.service.AnalyticsService;
import com.ekusys.exam.exam.service.ExamPermissionService;
import com.ekusys.exam.repository.entity.Exam;
import com.ekusys.exam.repository.entity.ExamTargetClass;
import com.ekusys.exam.repository.entity.Question;
import com.ekusys.exam.repository.entity.StudentProfile;
import com.ekusys.exam.repository.entity.StudentTeachingClass;
import com.ekusys.exam.repository.entity.Submission;
import com.ekusys.exam.repository.entity.SubmissionAnswer;
import com.ekusys.exam.repository.entity.TeachingClass;
import com.ekusys.exam.repository.entity.User;
import com.ekusys.exam.repository.mapper.ExamMapper;
import com.ekusys.exam.repository.mapper.ExamTargetClassMapper;
import com.ekusys.exam.repository.mapper.PaperMapper;
import com.ekusys.exam.repository.mapper.QuestionMapper;
import com.ekusys.exam.repository.mapper.StudentProfileMapper;
import com.ekusys.exam.repository.mapper.StudentTeachingClassMapper;
import com.ekusys.exam.repository.mapper.SubmissionAnswerMapper;
import com.ekusys.exam.repository.mapper.SubmissionMapper;
import com.ekusys.exam.repository.mapper.TeachingClassMapper;
import com.ekusys.exam.repository.mapper.UserMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    @Mock
    private SubmissionMapper submissionMapper;

    @Mock
    private SubmissionAnswerMapper submissionAnswerMapper;

    @Mock
    private ExamMapper examMapper;

    @Mock
    private ExamTargetClassMapper examTargetClassMapper;

    @Mock
    private PaperMapper paperMapper;

    @Mock
    private StudentProfileMapper studentProfileMapper;

    @Mock
    private StudentTeachingClassMapper studentTeachingClassMapper;

    @Mock
    private TeachingClassMapper teachingClassMapper;

    @Mock
    private QuestionMapper questionMapper;

    @Mock
    private UserMapper userMapper;

    private AnalyticsService analyticsService;

    @BeforeEach
    void setUp() {
        when(userMapper.selectRoleCodes(any())).thenReturn(List.of("TEACHER"));
        analyticsService = new AnalyticsService(
            submissionMapper,
            submissionAnswerMapper,
            examMapper,
            examTargetClassMapper,
            paperMapper,
            studentProfileMapper,
            studentTeachingClassMapper,
            teachingClassMapper,
            questionMapper,
            userMapper,
            new ExamPermissionService(userMapper, examTargetClassMapper, teachingClassMapper)
        );
    }

    @Test
    void overviewShouldAggregateBasicMetrics() {
        Exam exam = new Exam();
        exam.setId(100L);
        exam.setPassScore(60);
        exam.setPublisherId(200L);
        when(examMapper.selectById(100L)).thenReturn(exam);

        when(submissionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            submission(1L, 55),
            submission(2L, 60),
            submission(3L, 90),
            submission(4L, null)
        ));

        try (var mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(200L);
            ExamOverviewItem overview = analyticsService.overview(100L);

            assertEquals(4, overview.getTotalStudents());
            assertEquals(2, overview.getPassCount());
            assertEquals(50.0, overview.getPassRate());
            assertEquals(51.25, overview.getAvgScore());
            assertEquals(90, overview.getMaxScore());
            assertEquals(0, overview.getMinScore());
        }
    }

    @Test
    void overviewShouldReturnEmptyMetricsWhenNoSubmission() {
        Exam exam = new Exam();
        exam.setId(100L);
        exam.setPublisherId(200L);
        when(examMapper.selectById(100L)).thenReturn(exam);
        when(submissionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        ExamOverviewItem overview;
        try (var mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(200L);
            overview = analyticsService.overview(100L);
        }

        assertEquals(0, overview.getTotalStudents());
        assertEquals(0, overview.getPassCount());
        assertEquals(0.0, overview.getPassRate());
        assertEquals(0.0, overview.getAvgScore());
        assertNull(overview.getMaxScore());
        assertNull(overview.getMinScore());
    }

    @Test
    void wrongTopicsShouldSortByWrongRateAndRespectTopN() {
        Exam exam = new Exam();
        exam.setId(100L);
        exam.setPublisherId(200L);
        when(examMapper.selectById(100L)).thenReturn(exam);
        when(submissionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            submission(1L, 60),
            submission(2L, 70)
        ));
        when(submissionAnswerMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            answer(1L, 10L, false),
            answer(2L, 10L, false),
            answer(1L, 20L, false),
            answer(2L, 20L, true),
            answer(1L, 30L, true),
            answer(2L, 30L, true)
        ));
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(
            question(10L, "Q10"),
            question(20L, "Q20"),
            question(30L, "Q30")
        ));

        List<WrongTopicItem> items;
        try (var mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(200L);
            items = analyticsService.wrongTopics(100L, 2);
        }

        assertEquals(2, items.size());
        assertEquals(10L, items.get(0).getQuestionId());
        assertEquals(100.0, items.get(0).getWrongRate());
        assertEquals(20L, items.get(1).getQuestionId());
        assertEquals(50.0, items.get(1).getWrongRate());
    }

    @Test
    void wrongTopicsShouldClampTopNToMinimum() {
        Exam exam = new Exam();
        exam.setId(100L);
        exam.setPublisherId(200L);
        when(examMapper.selectById(100L)).thenReturn(exam);
        when(submissionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            submission(1L, 60),
            submission(2L, 70)
        ));
        when(submissionAnswerMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            answer(1L, 10L, false),
            answer(2L, 10L, false),
            answer(1L, 20L, false),
            answer(2L, 20L, true)
        ));
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(
            question(10L, "Q10"),
            question(20L, "Q20")
        ));

        List<WrongTopicItem> items;
        try (var mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(200L);
            items = analyticsService.wrongTopics(100L, 0);
        }

        assertEquals(1, items.size());
        assertEquals(10L, items.get(0).getQuestionId());
    }

    @Test
    void overviewShouldRejectUnauthorizedTeacher() {
        Exam exam = new Exam();
        exam.setId(100L);
        exam.setPublisherId(999L);
        when(examMapper.selectById(100L)).thenReturn(exam);
        when(examTargetClassMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        try (var mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(200L);
            assertThrows(BusinessException.class, () -> analyticsService.overview(100L));
        }
    }

    @Test
    void studentScoresShouldListTargetClassStudentsWithSubmissionScores() {
        Exam exam = new Exam();
        exam.setId(100L);
        exam.setPublisherId(200L);
        when(examMapper.selectById(100L)).thenReturn(exam);
        when(examTargetClassMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(targetClass(100L, 11L)));
        when(teachingClassMapper.selectBatchIds(any())).thenReturn(List.of(teachingClass(11L, "数据库原理-1班")));
        when(studentTeachingClassMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            enrollment(2001L, 11L),
            enrollment(2002L, 11L)
        ));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(
            user(2001L, "S001", "张三"),
            user(2002L, "S002", "李四")
        ));
        when(studentProfileMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            profile(2001L, "S001"),
            profile(2002L, "S002")
        ));
        Submission graded = submission(1L, 88);
        graded.setStudentId(2001L);
        graded.setStatus("GRADED");
        graded.setObjectiveScore(48);
        graded.setSubjectiveScore(40);
        graded.setPassFlag(true);
        when(submissionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(graded));

        List<StudentScoreItem> items;
        try (var mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(200L);
            items = analyticsService.studentScores(100L);
        }

        assertEquals(2, items.size());
        assertEquals(2001L, items.get(0).getStudentId());
        assertEquals("S001", items.get(0).getStudentNo());
        assertEquals("张三", items.get(0).getStudentName());
        assertEquals(List.of("数据库原理-1班"), items.get(0).getClassNames());
        assertEquals("GRADED", items.get(0).getSubmissionStatus());
        assertEquals(88, items.get(0).getTotalScore());
        assertEquals(true, items.get(0).getSubmitted());
        assertEquals(2002L, items.get(1).getStudentId());
        assertNull(items.get(1).getSubmissionId());
        assertEquals(false, items.get(1).getSubmitted());
    }

    private Submission submission(Long id, Integer totalScore) {
        Submission submission = new Submission();
        submission.setId(id);
        submission.setExamId(100L);
        submission.setStudentId(2000L + id);
        submission.setTotalScore(totalScore);
        return submission;
    }

    private SubmissionAnswer answer(Long submissionId, Long questionId, Boolean objectiveCorrect) {
        SubmissionAnswer answer = new SubmissionAnswer();
        answer.setSubmissionId(submissionId);
        answer.setQuestionId(questionId);
        answer.setObjectiveCorrect(objectiveCorrect);
        return answer;
    }

    private Question question(Long id, String content) {
        Question question = new Question();
        question.setId(id);
        question.setContent(content);
        return question;
    }

    private ExamTargetClass targetClass(Long examId, Long classId) {
        ExamTargetClass targetClass = new ExamTargetClass();
        targetClass.setExamId(examId);
        targetClass.setClassId(classId);
        return targetClass;
    }

    private TeachingClass teachingClass(Long id, String name) {
        TeachingClass teachingClass = new TeachingClass();
        teachingClass.setId(id);
        teachingClass.setName(name);
        return teachingClass;
    }

    private StudentTeachingClass enrollment(Long studentId, Long classId) {
        StudentTeachingClass enrollment = new StudentTeachingClass();
        enrollment.setStudentId(studentId);
        enrollment.setTeachingClassId(classId);
        enrollment.setEnrollStatus("ACTIVE");
        return enrollment;
    }

    private User user(Long id, String username, String realName) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setRealName(realName);
        return user;
    }

    private StudentProfile profile(Long userId, String studentNo) {
        StudentProfile profile = new StudentProfile();
        profile.setUserId(userId);
        profile.setStudentNo(studentNo);
        return profile;
    }
}
