package com.ekusys.exam.question.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ekusys.exam.common.api.PageResponse;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.SecurityUtils;
import com.ekusys.exam.question.dto.QuestionCreateRequest;
import com.ekusys.exam.question.dto.QuestionImageUploadView;
import com.ekusys.exam.question.dto.QuestionQueryRequest;
import com.ekusys.exam.question.dto.QuestionSubjectOptionView;
import com.ekusys.exam.question.dto.QuestionUpdateRequest;
import com.ekusys.exam.question.dto.QuestionView;
import com.ekusys.exam.repository.entity.PaperQuestion;
import com.ekusys.exam.repository.entity.Question;
import com.ekusys.exam.repository.entity.QuestionAsset;
import com.ekusys.exam.repository.entity.Subject;
import com.ekusys.exam.repository.mapper.PaperQuestionMapper;
import com.ekusys.exam.repository.mapper.QuestionAssetMapper;
import com.ekusys.exam.repository.mapper.QuestionMapper;
import com.ekusys.exam.repository.mapper.SubjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuestionService {

    private final QuestionMapper questionMapper;
    private final SubjectMapper subjectMapper;
    private final QuestionAssetMapper questionAssetMapper;
    private final PaperQuestionMapper paperQuestionMapper;
    private final QuestionAssetUrlResolver assetUrlResolver;

    public QuestionService(QuestionMapper questionMapper,
                           SubjectMapper subjectMapper,
                           QuestionAssetMapper questionAssetMapper,
                           PaperQuestionMapper paperQuestionMapper,
                           QuestionAssetUrlResolver assetUrlResolver) {
        this.questionMapper = questionMapper;
        this.subjectMapper = subjectMapper;
        this.questionAssetMapper = questionAssetMapper;
        this.paperQuestionMapper = paperQuestionMapper;
        this.assetUrlResolver = assetUrlResolver;
    }

    public PageResponse<QuestionView> query(QuestionQueryRequest request) {
        LambdaQueryWrapper<Question> wrapper = new LambdaQueryWrapper<Question>()
            .eq(request.getSubjectId() != null, Question::getSubjectId, request.getSubjectId())
            .eq(request.getType() != null, Question::getType, request.getType() == null ? null : request.getType().name())
            .eq(request.getDifficulty() != null, Question::getDifficulty, request.getDifficulty() == null ? null : request.getDifficulty().name())
            .like(request.getKeyword() != null && !request.getKeyword().isBlank(), Question::getContent, request.getKeyword())
            .orderByDesc(Question::getCreateTime);
        Page<Question> page = questionMapper.selectPage(new Page<>(request.getPageNum(), request.getPageSize()), wrapper);
        Map<Long, String> subjectNameMap = buildSubjectNameMap(page.getRecords());

        return PageResponse.<QuestionView>builder()
            .pageNum(page.getCurrent())
            .pageSize(page.getSize())
            .total(page.getTotal())
            .records(page.getRecords().stream().map(q -> toView(q, subjectNameMap, null)).toList())
            .build();
    }

    public List<QuestionSubjectOptionView> listSubjectOptions() {
        return subjectMapper.selectList(new LambdaQueryWrapper<Subject>().orderByAsc(Subject::getId)).stream()
            .map(subject -> QuestionSubjectOptionView.builder()
                .id(subject.getId())
                .name(subject.getName())
                .build())
            .toList();
    }

    @Transactional
    public Long create(QuestionCreateRequest request) {
        Question question = new Question();
        fillCreate(request, question);
        question.setCreatorId(SecurityUtils.getCurrentUserId());
        questionMapper.insert(question);
        syncAssetsForQuestion(question.getId(), request.getAssetIds());
        return question.getId();
    }

    @Transactional
    public void update(Long id, QuestionUpdateRequest request) {
        Question question = questionMapper.selectById(id);
        if (question == null) {
            throw new BusinessException("题目不存在");
        }
        ensureManagePermission(question);
        fillUpdate(request, question);
        questionMapper.updateById(question);
        syncAssetsForQuestion(id, request.getAssetIds());
    }

    @Transactional
    public void delete(Long id) {
        Question question = questionMapper.selectById(id);
        if (question == null) {
            throw new BusinessException("题目不存在");
        }
        ensureManagePermission(question);
        Long quoteCount = paperQuestionMapper.selectCount(
            new LambdaQueryWrapper<PaperQuestion>().eq(PaperQuestion::getQuestionId, id)
        );
        if (quoteCount != null && quoteCount > 0) {
            throw new BusinessException("题目已被试卷引用，无法删除");
        }
        questionAssetMapper.delete(new LambdaQueryWrapper<QuestionAsset>().eq(QuestionAsset::getQuestionId, id));
        questionMapper.deleteById(id);
    }

    public QuestionView getById(Long id) {
        Question question = questionMapper.selectById(id);
        if (question == null) {
            throw new BusinessException("题目不存在");
        }
        Map<Long, String> subjectNameMap = buildSubjectNameMap(List.of(question));
        return toView(question, subjectNameMap, listAssetsByQuestionId(id));
    }

    private void fillCreate(QuestionCreateRequest request, Question question) {
        question.setSubjectId(request.getSubjectId());
        question.setType(request.getType().name());
        question.setDifficulty(request.getDifficulty().name());
        question.setContent(request.getContent());
        question.setOptionsJson(request.getOptionsJson());
        question.setAnswer(request.getAnswer());
        question.setAnalysis(request.getAnalysis());
        question.setDefaultScore(request.getDefaultScore());
    }

    private void fillUpdate(QuestionUpdateRequest request, Question question) {
        question.setSubjectId(request.getSubjectId());
        question.setType(request.getType().name());
        question.setDifficulty(request.getDifficulty().name());
        question.setContent(request.getContent());
        question.setOptionsJson(request.getOptionsJson());
        question.setAnswer(request.getAnswer());
        question.setAnalysis(request.getAnalysis());
        question.setDefaultScore(request.getDefaultScore());
    }

    private QuestionView toView(Question q,
                                Map<Long, String> subjectNameMap,
                                List<QuestionImageUploadView> assets) {
        return QuestionView.builder()
            .id(toIdString(q.getId()))
            .subjectId(q.getSubjectId())
            .subjectName(subjectNameMap.get(q.getSubjectId()))
            .type(q.getType())
            .difficulty(q.getDifficulty())
            .content(q.getContent())
            .optionsJson(q.getOptionsJson())
            .answer(q.getAnswer())
            .analysis(q.getAnalysis())
            .defaultScore(q.getDefaultScore())
            .creatorId(q.getCreatorId())
            .canManage(canManage(q))
            .assets(assets)
            .build();
    }

    private Map<Long, String> buildSubjectNameMap(List<Question> questions) {
        Set<Long> subjectIds = questions.stream()
            .map(Question::getSubjectId)
            .filter(id -> id != null)
            .collect(Collectors.toSet());
        if (subjectIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return subjectMapper.selectList(new LambdaQueryWrapper<Subject>().in(Subject::getId, subjectIds)).stream()
            .collect(Collectors.toMap(Subject::getId, Subject::getName, (a, b) -> a));
    }

    private void ensureManagePermission(Question question) {
        if (isCurrentUserAdmin()) {
            return;
        }
        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (currentUserId == null || question.getCreatorId() == null || !currentUserId.equals(question.getCreatorId())) {
            throw new BusinessException("无权限操作他人题目");
        }
    }

    private boolean isCurrentUserAdmin() {
        return SecurityUtils.getCurrentRoles().contains("ADMIN");
    }

    private boolean canManage(Question question) {
        if (isCurrentUserAdmin()) {
            return true;
        }
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return currentUserId != null
            && question.getCreatorId() != null
            && currentUserId.equals(question.getCreatorId());
    }

    private List<QuestionImageUploadView> listAssetsByQuestionId(Long questionId) {
        return questionAssetMapper.selectList(
            new LambdaQueryWrapper<QuestionAsset>()
                .eq(QuestionAsset::getQuestionId, questionId)
                .orderByAsc(QuestionAsset::getId)
        ).stream().map(asset -> QuestionImageUploadView.builder()
            .assetId(toIdString(asset.getId()))
            .url(assetUrlResolver.resolve(asset))
            .objectKey(asset.getObjectKey())
            .originalName(asset.getOriginalName())
            .size(asset.getSize())
            .fileType(asset.getFileType())
            .build()).toList();
    }

    private void syncAssetsForQuestion(Long questionId, List<Long> assetIds) {
        List<QuestionAsset> currentAssets = questionAssetMapper.selectList(
            new LambdaQueryWrapper<QuestionAsset>().eq(QuestionAsset::getQuestionId, questionId)
        );
        Set<Long> currentIds = currentAssets.stream()
            .map(QuestionAsset::getId)
            .filter(id -> id != null)
            .collect(Collectors.toSet());

        List<Long> uniqueTargetIds = normalizeAssetIds(assetIds);
        Set<Long> targetIds = new LinkedHashSet<>(uniqueTargetIds);

        if (!targetIds.isEmpty()) {
            List<QuestionAsset> targetAssets = questionAssetMapper.selectList(
                new LambdaQueryWrapper<QuestionAsset>().in(QuestionAsset::getId, targetIds)
            );
            if (targetAssets.size() != targetIds.size()) {
                throw new BusinessException("存在无效的附件ID");
            }
            for (QuestionAsset asset : targetAssets) {
                validateAssetManagePermission(questionId, asset);
            }
        }

        for (Long assetId : targetIds) {
            if (currentIds.contains(assetId)) {
                continue;
            }
            questionAssetMapper.update(
                null,
                new LambdaUpdateWrapper<QuestionAsset>()
                    .eq(QuestionAsset::getId, assetId)
                    .set(QuestionAsset::getQuestionId, questionId)
            );
        }

        for (Long assetId : currentIds) {
            if (targetIds.contains(assetId)) {
                continue;
            }
            questionAssetMapper.update(
                null,
                new LambdaUpdateWrapper<QuestionAsset>()
                    .eq(QuestionAsset::getId, assetId)
                    .set(QuestionAsset::getQuestionId, null)
            );
        }
    }

    private void validateAssetManagePermission(Long questionId, QuestionAsset asset) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        boolean admin = isCurrentUserAdmin();
        if (!admin && (currentUserId == null || !currentUserId.equals(asset.getUploaderId()))) {
            throw new BusinessException("无权绑定他人上传的附件");
        }
        if (asset.getQuestionId() != null && !questionId.equals(asset.getQuestionId())) {
            throw new BusinessException("附件已绑定到其他题目");
        }
    }

    private List<Long> normalizeAssetIds(List<Long> assetIds) {
        if (assetIds == null || assetIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (Long assetId : assetIds) {
            if (assetId == null || ids.contains(assetId)) {
                continue;
            }
            ids.add(assetId);
        }
        return ids;
    }

    private String toIdString(Long id) {
        return id == null ? null : String.valueOf(id);
    }
}
