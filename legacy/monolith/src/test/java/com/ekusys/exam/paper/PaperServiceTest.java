package com.ekusys.exam.paper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ekusys.exam.common.enums.Difficulty;
import com.ekusys.exam.common.enums.QuestionType;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.LoginUser;
import com.ekusys.exam.question.service.QuestionAssetUrlResolver;
import com.ekusys.exam.paper.dto.AutoGeneratePaperRequest;
import com.ekusys.exam.paper.dto.AutoGenerateRule;
import com.ekusys.exam.paper.dto.ManualCreatePaperRequest;
import com.ekusys.exam.paper.dto.ManualPaperQuestion;
import com.ekusys.exam.paper.dto.PaperDetailView;
import com.ekusys.exam.paper.dto.PaperQueryRequest;
import com.ekusys.exam.paper.dto.PaperUpdateRequest;
import com.ekusys.exam.paper.service.PaperService;
import com.ekusys.exam.repository.entity.Paper;
import com.ekusys.exam.repository.entity.PaperQuestion;
import com.ekusys.exam.repository.entity.Question;
import com.ekusys.exam.repository.entity.QuestionAsset;
import com.ekusys.exam.repository.entity.Subject;
import com.ekusys.exam.repository.mapper.ExamMapper;
import com.ekusys.exam.repository.mapper.PaperMapper;
import com.ekusys.exam.repository.mapper.PaperQuestionMapper;
import com.ekusys.exam.repository.mapper.QuestionAssetMapper;
import com.ekusys.exam.repository.mapper.QuestionMapper;
import com.ekusys.exam.repository.mapper.SubjectMapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class PaperServiceTest {

    @Mock
    private PaperMapper paperMapper;

    @Mock
    private PaperQuestionMapper paperQuestionMapper;

    @Mock
    private QuestionMapper questionMapper;

    @Mock
    private QuestionAssetMapper questionAssetMapper;

    @Mock
    private SubjectMapper subjectMapper;

    @Mock
    private ExamMapper examMapper;

    private PaperService paperService;

    @BeforeEach
    void setUp() {
        paperService = new PaperService(
            paperMapper,
            paperQuestionMapper,
            questionMapper,
            questionAssetMapper,
            subjectMapper,
            examMapper,
            new QuestionAssetUrlResolver(new com.ekusys.exam.common.config.MinioProperties())
        );

        LoginUser admin = LoginUser.builder()
            .userId(1001L)
            .username("admin")
            .password("x")
            .enabled(true)
            .roles(List.of("ADMIN"))
            .build();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
        SecurityContextHolder.setContext(context);

        lenient().doAnswer(invocation -> {
            Paper paper = invocation.getArgument(0);
            paper.setId(9001L);
            return 1;
        }).when(paperMapper).insert(any(Paper.class));

        lenient().when(subjectMapper.selectById(5001L)).thenReturn(buildSubject(5001L, "Java"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void autoGenerateShouldCreatePaperWhenPoolEnough() {
        AutoGenerateRule rule = new AutoGenerateRule();
        rule.setType(QuestionType.SINGLE);
        rule.setDifficulty(Difficulty.EASY);
        rule.setCount(2);
        rule.setScore(5);

        AutoGeneratePaperRequest request = new AutoGeneratePaperRequest();
        request.setName("自动卷");
        request.setSubjectId(5001L);
        request.setDescription("desc");
        request.setRules(List.of(rule));

        Question q1 = buildQuestion(11L, 5001L);
        Question q2 = buildQuestion(12L, 5001L);

        when(questionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(q1, q2));
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(q1, q2));

        Long paperId = paperService.autoGenerate(request);
        assertEquals(9001L, paperId);
    }

    @Test
    void autoGenerateShouldSupportAnyDifficulty() {
        AutoGenerateRule rule = new AutoGenerateRule();
        rule.setType(QuestionType.SINGLE);
        rule.setDifficulty(null);
        rule.setCount(2);
        rule.setScore(5);

        AutoGeneratePaperRequest request = new AutoGeneratePaperRequest();
        request.setName("自动卷");
        request.setSubjectId(5001L);
        request.setRules(List.of(rule));

        Question q1 = buildQuestion(11L, 5001L);
        Question q2 = buildQuestion(12L, 5001L);

        when(questionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(q1, q2));
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(q1, q2));

        Long paperId = paperService.autoGenerate(request);
        assertEquals(9001L, paperId);
    }

    @Test
    void autoGenerateShouldFailWhenPoolNotEnough() {
        AutoGenerateRule rule = new AutoGenerateRule();
        rule.setType(QuestionType.SINGLE);
        rule.setDifficulty(Difficulty.EASY);
        rule.setCount(3);
        rule.setScore(5);

        AutoGeneratePaperRequest request = new AutoGeneratePaperRequest();
        request.setName("自动卷");
        request.setSubjectId(5001L);
        request.setDescription("desc");
        request.setRules(List.of(rule));

        Question q1 = buildQuestion(11L, 5001L);
        when(questionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(q1));

        assertThrows(BusinessException.class, () -> paperService.autoGenerate(request));
    }

    @Test
    void createManualShouldFailWhenQuestionSubjectMismatch() {
        ManualPaperQuestion item = new ManualPaperQuestion();
        item.setQuestionId(11L);
        item.setScore(5);
        item.setSortOrder(1);

        ManualCreatePaperRequest request = new ManualCreatePaperRequest();
        request.setName("手动卷");
        request.setSubjectId(5001L);
        request.setQuestions(List.of(item));

        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(buildQuestion(11L, 5002L)));

        assertThrows(BusinessException.class, () -> paperService.createManual(request));
    }

    @Test
    void updateShouldRecalculateTotalScore() {
        Paper existing = new Paper();
        existing.setId(9001L);
        existing.setTeacherId(1002L);
        when(paperMapper.selectById(9001L)).thenReturn(existing);

        Question q1 = buildQuestion(11L, 5001L);
        Question q2 = buildQuestion(12L, 5001L);
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(q1, q2));

        ManualPaperQuestion item1 = new ManualPaperQuestion();
        item1.setQuestionId(11L);
        item1.setScore(6);
        item1.setSortOrder(1);

        ManualPaperQuestion item2 = new ManualPaperQuestion();
        item2.setQuestionId(12L);
        item2.setScore(4);
        item2.setSortOrder(2);

        PaperUpdateRequest request = new PaperUpdateRequest();
        request.setName("更新后试卷");
        request.setSubjectId(5001L);
        request.setDescription("new");
        request.setQuestions(List.of(item1, item2));

        paperService.update(9001L, request);

        ArgumentCaptor<Paper> captor = ArgumentCaptor.forClass(Paper.class);
        verify(paperMapper).updateById(captor.capture());
        assertEquals(10, captor.getValue().getTotalScore());
    }

    @Test
    void deleteShouldFailWhenReferencedByExam() {
        Paper existing = new Paper();
        existing.setId(9001L);
        existing.setTeacherId(1002L);
        when(paperMapper.selectById(9001L)).thenReturn(existing);
        when(examMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class, () -> paperService.delete(9001L));
        assertEquals("PAPER_REFERENCED_BY_EXAM", ex.getCode());
    }

    @Test
    void deleteShouldSuccessWhenNotReferenced() {
        Paper existing = new Paper();
        existing.setId(9001L);
        existing.setTeacherId(1002L);
        when(paperMapper.selectById(9001L)).thenReturn(existing);
        when(examMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        paperService.delete(9001L);

        verify(paperQuestionMapper).delete(any(LambdaQueryWrapper.class));
        verify(paperMapper).deleteById(eq(9001L));
    }

    @Test
    void queryShouldSupportNullName() {
        Page<Paper> page = new Page<>(1, 10);
        page.setRecords(List.of());
        page.setTotal(0);
        when(paperMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(page);

        PaperQueryRequest request = new PaperQueryRequest();
        request.setPageNum(1);
        request.setPageSize(10);
        request.setSubjectId(null);
        request.setName(null);

        assertDoesNotThrow(() -> paperService.query(request));
    }

    @Test
    void getDetailShouldIncludeQuestionAssets() {
        Paper paper = new Paper();
        paper.setId(9001L);
        paper.setName("试卷A");
        paper.setSubjectId(5001L);
        paper.setTeacherId(1002L);
        when(paperMapper.selectById(9001L)).thenReturn(paper);
        when(subjectMapper.selectById(5001L)).thenReturn(buildSubject(5001L, "Java"));

        PaperQuestion link = new PaperQuestion();
        link.setPaperId(9001L);
        link.setQuestionId(11L);
        link.setScore(5);
        link.setSortOrder(1);
        when(paperQuestionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(link));

        Question question = buildQuestion(11L, 5001L);
        question.setOptionsJson("[{\"label\":\"A\",\"value\":\"X\"}]");
        when(questionMapper.selectBatchIds(any())).thenReturn(List.of(question));

        QuestionAsset asset = new QuestionAsset();
        asset.setId(7001L);
        asset.setQuestionId(11L);
        asset.setUrl("http://example.com/a.png");
        asset.setFileType("IMAGE");
        when(questionAssetMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(asset));

        PaperDetailView detail = paperService.getDetail(9001L);
        assertEquals(1, detail.getQuestions().size());
        assertEquals(1, detail.getQuestions().get(0).getAssets().size());
        assertEquals("7001", detail.getQuestions().get(0).getAssets().get(0).getAssetId());
        assertEquals("http://example.com/a.png", detail.getQuestions().get(0).getAssets().get(0).getUrl());
    }

    private Question buildQuestion(Long id, Long subjectId) {
        Question question = new Question();
        question.setId(id);
        question.setSubjectId(subjectId);
        question.setType("SINGLE");
        question.setDifficulty("EASY");
        question.setContent("Q" + id);
        question.setAnswer("A");
        return question;
    }

    private Subject buildSubject(Long id, String name) {
        Subject subject = new Subject();
        subject.setId(id);
        subject.setName(name);
        return subject;
    }
}
