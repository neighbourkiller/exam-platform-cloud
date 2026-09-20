package com.ekusys.exam.exam.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ekusys.exam.exam.dto.StudentExamQuestionView;
import com.ekusys.exam.question.dto.QuestionImageUploadView;
import com.ekusys.exam.question.service.QuestionAssetUrlResolver;
import com.ekusys.exam.repository.entity.PaperQuestion;
import com.ekusys.exam.repository.entity.Question;
import com.ekusys.exam.repository.entity.QuestionAsset;
import com.ekusys.exam.repository.mapper.PaperQuestionMapper;
import com.ekusys.exam.repository.mapper.QuestionAssetMapper;
import com.ekusys.exam.repository.mapper.QuestionMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class ExamQuestionAssembler {

    private final PaperQuestionMapper paperQuestionMapper;
    private final QuestionMapper questionMapper;
    private final QuestionAssetMapper questionAssetMapper;
    private final ExamSnapshotService examSnapshotService;
    private final QuestionAssetUrlResolver assetUrlResolver;

    public ExamQuestionAssembler(PaperQuestionMapper paperQuestionMapper,
                                 QuestionMapper questionMapper,
                                 QuestionAssetMapper questionAssetMapper,
                                 ExamSnapshotService examSnapshotService,
                                 QuestionAssetUrlResolver assetUrlResolver) {
        this.paperQuestionMapper = paperQuestionMapper;
        this.questionMapper = questionMapper;
        this.questionAssetMapper = questionAssetMapper;
        this.examSnapshotService = examSnapshotService;
        this.assetUrlResolver = assetUrlResolver;
    }

    public List<StudentExamQuestionView> assembleQuestions(Long paperId, Long examId, Long studentId) {
        List<PaperQuestion> paperQuestions = paperQuestionMapper.selectList(
            new LambdaQueryWrapper<PaperQuestion>()
                .eq(PaperQuestion::getPaperId, paperId)
                .orderByAsc(PaperQuestion::getSortOrder)
        );
        List<Long> questionIds = paperQuestions.stream().map(PaperQuestion::getQuestionId).toList();
        Map<Long, Question> questionMap = questionMapper.selectBatchIds(questionIds).stream()
            .collect(Collectors.toMap(Question::getId, item -> item));
        Map<Long, List<QuestionImageUploadView>> questionAssetMap = listQuestionAssets(questionIds);
        Map<Long, String> snapshotAnswers = examSnapshotService.loadSnapshotAnswerMap(examId, studentId);

        return paperQuestions.stream().map(link -> {
            Question question = questionMap.get(link.getQuestionId());
            return StudentExamQuestionView.builder()
                .questionId(link.getQuestionId())
                .type(question == null ? null : question.getType())
                .content(question == null ? null : question.getContent())
                .optionsJson(question == null ? null : question.getOptionsJson())
                .score(link.getScore())
                .sortOrder(link.getSortOrder())
                .currentAnswer(snapshotAnswers.get(link.getQuestionId()))
                .assets(questionAssetMap.getOrDefault(link.getQuestionId(), List.of()))
                .build();
        }).toList();
    }

    private Map<Long, List<QuestionImageUploadView>> listQuestionAssets(List<Long> questionIds) {
        List<Long> targetQuestionIds = questionIds.stream()
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (targetQuestionIds.isEmpty()) {
            return Map.of();
        }

        return questionAssetMapper.selectList(
            new LambdaQueryWrapper<QuestionAsset>()
                .in(QuestionAsset::getQuestionId, targetQuestionIds)
                .orderByAsc(QuestionAsset::getQuestionId)
                .orderByAsc(QuestionAsset::getId)
        ).stream().collect(Collectors.groupingBy(
            QuestionAsset::getQuestionId,
            Collectors.mapping(asset -> QuestionImageUploadView.builder()
                .assetId(String.valueOf(asset.getId()))
                .url(assetUrlResolver.resolve(asset))
                .objectKey(asset.getObjectKey())
                .originalName(asset.getOriginalName())
                .size(asset.getSize())
                .fileType(asset.getFileType())
                .build(), Collectors.toList())
        ));
    }
}
