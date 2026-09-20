package com.ekusys.exam.question;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ekusys.exam.common.enums.Difficulty;
import com.ekusys.exam.common.enums.QuestionType;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.SecurityUtils;
import com.ekusys.exam.question.dto.QuestionUpdateRequest;
import com.ekusys.exam.question.dto.QuestionView;
import com.ekusys.exam.question.service.QuestionAssetUrlResolver;
import com.ekusys.exam.question.service.QuestionService;
import com.ekusys.exam.repository.entity.Question;
import com.ekusys.exam.repository.entity.QuestionAsset;
import com.ekusys.exam.repository.mapper.PaperQuestionMapper;
import com.ekusys.exam.repository.mapper.QuestionAssetMapper;
import com.ekusys.exam.repository.mapper.QuestionMapper;
import com.ekusys.exam.repository.mapper.SubjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QuestionServiceTest {

    @Mock
    private QuestionMapper questionMapper;

    @Mock
    private SubjectMapper subjectMapper;

    @Mock
    private QuestionAssetMapper questionAssetMapper;

    @Mock
    private PaperQuestionMapper paperQuestionMapper;

    private QuestionService questionService;

    @BeforeEach
    void setUp() {
        questionService = new QuestionService(
            questionMapper,
            subjectMapper,
            questionAssetMapper,
            paperQuestionMapper,
            new QuestionAssetUrlResolver(new com.ekusys.exam.common.config.MinioProperties())
        );
    }

    @Test
    void getByIdShouldReturnCreatorIdAndAssets() {
        Question question = new Question();
        question.setId(1L);
        question.setCreatorId(1001L);
        question.setContent("question-content");
        question.setAnswer("A");
        when(questionMapper.selectById(1L)).thenReturn(question);

        QuestionAsset asset = new QuestionAsset();
        asset.setId(9001L);
        asset.setQuestionId(1L);
        asset.setUrl("http://example.com/question.png");
        asset.setFileType("IMAGE");
        asset.setOriginalName("question.png");
        when(questionAssetMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(asset));

        QuestionView detail;
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentRoles).thenReturn(List.of("TEACHER"));
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(2002L);

            detail = questionService.getById(1L);
        }

        assertEquals("1", detail.getId());
        assertEquals(1001L, detail.getCreatorId());
        assertEquals("question-content", detail.getContent());
        assertEquals("A", detail.getAnswer());
        assertEquals(false, detail.getCanManage());
        assertEquals(1, detail.getAssets().size());
        assertEquals("9001", detail.getAssets().get(0).getAssetId());
        assertEquals("http://example.com/question.png", detail.getAssets().get(0).getUrl());
    }

    @Test
    void updateShouldPersistAndSyncAssetsWhenOwner() {
        Question question = new Question();
        question.setId(1L);
        question.setCreatorId(1001L);
        when(questionMapper.selectById(1L)).thenReturn(question);

        when(questionAssetMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of());

        QuestionUpdateRequest request = new QuestionUpdateRequest();
        request.setSubjectId(5001L);
        request.setType(QuestionType.MULTI);
        request.setDifficulty(Difficulty.MEDIUM);
        request.setContent("updated-content");
        request.setOptionsJson("[{\"label\":\"A\",\"value\":\"x\"}]");
        request.setAnswer("A");
        request.setAnalysis("updated-analysis");
        request.setDefaultScore(8);
        request.setAssetIds(List.of());

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentRoles).thenReturn(List.of("TEACHER"));
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(1001L);

            questionService.update(1L, request);
        }

        assertEquals(5001L, question.getSubjectId());
        assertEquals("MULTI", question.getType());
        assertEquals("MEDIUM", question.getDifficulty());
        assertEquals("updated-content", question.getContent());
        assertEquals("A", question.getAnswer());
        assertEquals("updated-analysis", question.getAnalysis());
        assertEquals(8, question.getDefaultScore());
        verify(questionMapper).updateById(question);
        verify(questionAssetMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void updateShouldFailWhenNotOwnerAndNotAdmin() {
        Question question = new Question();
        question.setId(1L);
        question.setCreatorId(2001L);
        when(questionMapper.selectById(1L)).thenReturn(question);

        QuestionUpdateRequest request = new QuestionUpdateRequest();
        request.setSubjectId(5001L);
        request.setType(QuestionType.SINGLE);
        request.setDifficulty(Difficulty.EASY);
        request.setContent("content");
        request.setAnswer("A");
        request.setDefaultScore(5);

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentRoles).thenReturn(List.of("TEACHER"));
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(1001L);

            assertThrows(BusinessException.class, () -> questionService.update(1L, request));
        }

        verify(questionMapper, never()).updateById(any(Question.class));
    }

    @Test
    void deleteShouldFailWhenQuestionReferencedByPaper() {
        Question question = new Question();
        question.setId(1L);
        question.setCreatorId(1001L);
        when(questionMapper.selectById(1L)).thenReturn(question);
        when(paperQuestionMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentRoles).thenReturn(List.of("TEACHER"));
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(1001L);

            BusinessException exception = assertThrows(BusinessException.class, () -> questionService.delete(1L));
            assertEquals("题目已被试卷引用，无法删除", exception.getMessage());
        }

        verify(questionMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void deleteShouldSucceedWhenQuestionNotReferenced() {
        Question question = new Question();
        question.setId(1L);
        question.setCreatorId(1001L);
        when(questionMapper.selectById(1L)).thenReturn(question);
        when(paperQuestionMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getCurrentRoles).thenReturn(List.of("TEACHER"));
            mocked.when(SecurityUtils::getCurrentUserId).thenReturn(1001L);

            questionService.delete(1L);
        }

        verify(questionAssetMapper).delete(any(LambdaQueryWrapper.class));
        verify(questionMapper).deleteById(eq(1L));
    }
}
