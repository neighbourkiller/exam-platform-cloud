package com.ekusys.exam.exam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ekusys.exam.common.config.AppSnapshotProperties;
import com.ekusys.exam.common.config.MinioProperties;
import com.ekusys.exam.common.security.SecurityUtils;
import com.ekusys.exam.exam.dto.StartExamResponse;
import com.ekusys.exam.exam.service.ExamAccessService;
import com.ekusys.exam.exam.service.ExamAntiCheatWriteService;
import com.ekusys.exam.exam.service.ExamAutoSubmitService;
import com.ekusys.exam.exam.service.ExamPermissionService;
import com.ekusys.exam.exam.service.ExamProctoringPolicyService;
import com.ekusys.exam.exam.service.ExamQuestionAssembler;
import com.ekusys.exam.exam.service.ExamSessionService;
import com.ekusys.exam.exam.service.ExamService;
import com.ekusys.exam.exam.service.ExamSnapshotService;
import com.ekusys.exam.exam.service.ExamStartService;
import com.ekusys.exam.exam.service.ExamStatusService;
import com.ekusys.exam.exam.service.ExamStudentQueryService;
import com.ekusys.exam.exam.service.ExamSubmissionService;
import com.ekusys.exam.question.service.QuestionAssetUrlResolver;
import com.ekusys.exam.exam.service.ExamTeacherQueryService;
import com.ekusys.exam.repository.entity.Exam;
import com.ekusys.exam.repository.entity.ExamSession;
import com.ekusys.exam.repository.entity.PaperQuestion;
import com.ekusys.exam.repository.entity.Question;
import com.ekusys.exam.repository.entity.QuestionAsset;
import com.ekusys.exam.repository.entity.StudentTeachingClass;
import com.ekusys.exam.repository.mapper.AntiCheatEventMapper;
import com.ekusys.exam.repository.mapper.ExamMapper;
import com.ekusys.exam.repository.mapper.ExamSessionMapper;
import com.ekusys.exam.repository.mapper.ExamTargetClassMapper;
import com.ekusys.exam.repository.mapper.PaperMapper;
import com.ekusys.exam.repository.mapper.PaperQuestionMapper;
import com.ekusys.exam.repository.mapper.QuestionAssetMapper;
import com.ekusys.exam.repository.mapper.QuestionMapper;
import com.ekusys.exam.repository.mapper.StudentTeachingClassMapper;
import com.ekusys.exam.repository.mapper.SubjectMapper;
import com.ekusys.exam.repository.mapper.SubmissionAnswerMapper;
import com.ekusys.exam.repository.mapper.SubmissionMapper;
import com.ekusys.exam.repository.mapper.TeachingClassMapper;
import com.ekusys.exam.repository.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class ExamServiceTest {

    @Mock
    private ExamMapper examMapper;

    @Mock
    private PaperMapper paperMapper;

    @Mock
    private ExamTargetClassMapper examTargetClassMapper;

    @Mock
    private StudentTeachingClassMapper studentTeachingClassMapper;

    @Mock
    private TeachingClassMapper teachingClassMapper;

    @Mock
    private SubjectMapper subjectMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private ExamSessionMapper examSessionMapper;

    @Mock
    private SubmissionMapper submissionMapper;

    @Mock
    private SubmissionAnswerMapper submissionAnswerMapper;

    @Mock
    private PaperQuestionMapper paperQuestionMapper;

    @Mock
    private QuestionMapper questionMapper;

    @Mock
    private QuestionAssetMapper questionAssetMapper;

    @Mock
    private AntiCheatEventMapper antiCheatEventMapper;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private ExamService examService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        ExamProctoringPolicyService proctoringPolicyService = new ExamProctoringPolicyService(objectMapper);
        ExamAccessService accessService = new ExamAccessService(
            examMapper,
            examTargetClassMapper,
            studentTeachingClassMapper,
            userMapper
        );
        ExamStatusService statusService = new ExamStatusService(examMapper);
        ExamSessionService sessionService = new ExamSessionService(examSessionMapper, submissionMapper);
        AppSnapshotProperties snapshotProperties = new AppSnapshotProperties();
        ExamSnapshotService snapshotService = new ExamSnapshotService(
            accessService,
            sessionService,
            snapshotProperties,
            submissionMapper,
            submissionAnswerMapper,
            redisTemplate,
            objectMapper
        );
        ExamQuestionAssembler questionAssembler = new ExamQuestionAssembler(
            paperQuestionMapper,
            questionMapper,
            questionAssetMapper,
            snapshotService,
            new QuestionAssetUrlResolver(new MinioProperties())
        );
        examService = new ExamService(
            new com.ekusys.exam.exam.service.ExamLifecycleService(
                paperMapper,
                examMapper,
                examTargetClassMapper,
                teachingClassMapper,
                accessService,
                proctoringPolicyService
            ),
            new ExamTeacherQueryService(
                examMapper,
                teachingClassMapper,
                subjectMapper,
                userMapper,
                accessService,
                statusService,
                new ExamPermissionService(userMapper, examTargetClassMapper, teachingClassMapper),
                proctoringPolicyService
            ),
            new ExamStudentQueryService(
                examMapper,
                examTargetClassMapper,
                submissionMapper,
                paperMapper,
                subjectMapper,
                accessService,
                statusService,
                proctoringPolicyService
            ),
            new ExamStartService(
                accessService,
                statusService,
                sessionService,
                questionAssembler,
                snapshotService,
                proctoringPolicyService
            ),
            snapshotService,
            new ExamAntiCheatWriteService(accessService, antiCheatEventMapper, proctoringPolicyService),
            org.mockito.Mockito.mock(ExamSubmissionService.class),
            org.mockito.Mockito.mock(ExamAutoSubmitService.class)
        );
    }

    @Test
    void startExamShouldIncludeQuestionAssets() {
        Exam exam = new Exam();
        exam.setId(3001L);
        exam.setPaperId(7001L);
        exam.setName("Java 期末");
        exam.setStatus("ONGOING");
        exam.setDurationMinutes(60);
        exam.setStartTime(LocalDateTime.now().minusMinutes(20));
        exam.setEndTime(LocalDateTime.now().plusMinutes(40));
        when(examMapper.selectById(3001L)).thenReturn(exam);

        StudentTeachingClass relation = new StudentTeachingClass();
        relation.setTeachingClassId(9001L);
        when(studentTeachingClassMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(relation));
        when(examTargetClassMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        when(examSessionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(submissionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        PaperQuestion paperQuestion = new PaperQuestion();
        paperQuestion.setQuestionId(11L);
        paperQuestion.setScore(5);
        paperQuestion.setSortOrder(1);
        when(paperQuestionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(paperQuestion));

        Question question = new Question();
        question.setId(11L);
        question.setType("JUDGE");
        question.setContent("Java 是面向对象语言。");
        question.setOptionsJson("[{\"label\":\"A\",\"value\":\"true\"},{\"label\":\"B\",\"value\":\"false\"}]");
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(question));

        QuestionAsset asset = new QuestionAsset();
        asset.setId(8101L);
        asset.setQuestionId(11L);
        asset.setFileType("IMAGE");
        asset.setUrl("http://example.com/question-11.png");
        when(questionAssetMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(asset));

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("exam:snapshot:3001:1001")).thenReturn(null);

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(1001L);

            StartExamResponse response = examService.startExam(3001L);

            assertNotNull(response);
            assertEquals(1, response.getQuestions().size());
            assertEquals(1, response.getQuestions().get(0).getAssets().size());
            assertEquals("8101", response.getQuestions().get(0).getAssets().get(0).getAssetId());
            assertEquals("http://example.com/question-11.png", response.getQuestions().get(0).getAssets().get(0).getUrl());
            assertNotNull(response.getDeadlineTime());
            assertEquals(exam.getEndTime().withNano(0), response.getDeadlineTime().withNano(0));
        }
    }

    @Test
    void startExamShouldExposeResumeMetadata() {
        Exam exam = new Exam();
        exam.setId(3002L);
        exam.setPaperId(7002L);
        exam.setName("数据库期中");
        exam.setStatus("ONGOING");
        exam.setDurationMinutes(90);
        exam.setStartTime(LocalDateTime.now().minusMinutes(30));
        exam.setEndTime(LocalDateTime.now().plusMinutes(60));
        when(examMapper.selectById(3002L)).thenReturn(exam);

        StudentTeachingClass relation = new StudentTeachingClass();
        relation.setTeachingClassId(9002L);
        when(studentTeachingClassMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(relation));
        when(examTargetClassMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        ExamSession session = new ExamSession();
        session.setId(12L);
        session.setExamId(3002L);
        session.setStudentId(1002L);
        session.setStatus("ANSWERING");
        session.setStartTime(LocalDateTime.now().minusMinutes(20));
        session.setDeadlineTime(LocalDateTime.now().plusMinutes(40));
        session.setLastSnapshotTime(LocalDateTime.of(2026, 4, 7, 11, 20, 0));
        when(examSessionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(session);

        when(submissionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(paperQuestionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("exam:snapshot:3002:1002")).thenReturn(null);

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(1002L);

            StartExamResponse response = examService.startExam(3002L);

            assertNotNull(response);
            assertEquals(Boolean.TRUE, response.getResumed());
            assertEquals(session.getLastSnapshotTime(), response.getDraftUpdatedAt());
        }
    }
}
