package com.ekusys.exam.paper.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ekusys.exam.common.api.PageResponse;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.SecurityUtils;
import com.ekusys.exam.paper.dto.AutoGeneratePaperRequest;
import com.ekusys.exam.paper.dto.AutoGenerateRule;
import com.ekusys.exam.paper.dto.ManualCreatePaperRequest;
import com.ekusys.exam.paper.dto.ManualPaperQuestion;
import com.ekusys.exam.paper.dto.PaperDetailView;
import com.ekusys.exam.paper.dto.PaperListItemView;
import com.ekusys.exam.paper.dto.PaperQuestionAssetView;
import com.ekusys.exam.paper.dto.PaperQueryRequest;
import com.ekusys.exam.paper.dto.PaperQuestionView;
import com.ekusys.exam.paper.dto.PaperUpdateRequest;
import com.ekusys.exam.question.service.QuestionAssetUrlResolver;
import com.ekusys.exam.repository.entity.Exam;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaperService {

    private static final String PAPER_REFERENCED_BY_EXAM = "PAPER_REFERENCED_BY_EXAM";

    private final PaperMapper paperMapper;
    private final PaperQuestionMapper paperQuestionMapper;
    private final QuestionMapper questionMapper;
    private final QuestionAssetMapper questionAssetMapper;
    private final SubjectMapper subjectMapper;
    private final ExamMapper examMapper;
    private final QuestionAssetUrlResolver assetUrlResolver;

    public PaperService(PaperMapper paperMapper,
                        PaperQuestionMapper paperQuestionMapper,
                        QuestionMapper questionMapper,
                        QuestionAssetMapper questionAssetMapper,
                        SubjectMapper subjectMapper,
                        ExamMapper examMapper,
                        QuestionAssetUrlResolver assetUrlResolver) {
        this.paperMapper = paperMapper;
        this.paperQuestionMapper = paperQuestionMapper;
        this.questionMapper = questionMapper;
        this.questionAssetMapper = questionAssetMapper;
        this.subjectMapper = subjectMapper;
        this.examMapper = examMapper;
        this.assetUrlResolver = assetUrlResolver;
    }

    @Transactional
    public Long createManual(ManualCreatePaperRequest request) {
        ensureSubjectExists(request.getSubjectId());
        validateManualQuestions(request.getSubjectId(), request.getQuestions());

        Paper paper = buildPaper(request.getName(), request.getSubjectId(), request.getDescription());
        paperMapper.insert(paper);

        int totalScore = insertPaperQuestions(paper.getId(), request.getQuestions());
        paper.setTotalScore(totalScore);
        paperMapper.updateById(paper);
        return paper.getId();
    }

    @Transactional
    public Long autoGenerate(AutoGeneratePaperRequest request) {
        ensureSubjectExists(request.getSubjectId());

        List<ManualPaperQuestion> selected = new ArrayList<>();
        Set<Long> picked = new HashSet<>();
        int sort = 1;

        for (AutoGenerateRule rule : request.getRules()) {
            if (rule.getCount() == null || rule.getCount() < 1) {
                throw new BusinessException("自动组卷规则数量必须大于0");
            }
            if (rule.getScore() == null || rule.getScore() < 1) {
                throw new BusinessException("自动组卷规则分值必须大于0");
            }
            if (rule.getType() == null) {
                throw new BusinessException("自动组卷规则不完整");
            }
            String difficulty = rule.getDifficulty() == null ? null : rule.getDifficulty().name();

            List<Question> pool = questionMapper.selectList(new LambdaQueryWrapper<Question>()
                .eq(Question::getSubjectId, request.getSubjectId())
                .eq(Question::getType, rule.getType().name())
                .eq(difficulty != null, Question::getDifficulty, difficulty)
                .notIn(!picked.isEmpty(), Question::getId, picked)
                .last("order by rand() limit " + rule.getCount()));

            if (pool.size() < rule.getCount()) {
                throw new BusinessException(
                    "题量不足：题型=" + rule.getType().name()
                        + "，难度=" + difficultyLabel(difficulty)
                        + "，需要" + rule.getCount() + "题，实际仅" + pool.size() + "题"
                );
            }

            for (Question question : pool) {
                picked.add(question.getId());
                ManualPaperQuestion item = new ManualPaperQuestion();
                item.setQuestionId(question.getId());
                item.setScore(rule.getScore());
                item.setSortOrder(sort++);
                selected.add(item);
            }
        }

        ManualCreatePaperRequest manual = new ManualCreatePaperRequest();
        manual.setName(request.getName());
        manual.setSubjectId(request.getSubjectId());
        manual.setDescription(request.getDescription());
        manual.setQuestions(selected);
        return createManual(manual);
    }

    public PageResponse<PaperListItemView> query(PaperQueryRequest request) {
        long pageNum = request.getPageNum() <= 0 ? 1 : request.getPageNum();
        long pageSize = request.getPageSize() <= 0 ? 10 : request.getPageSize();
        Long currentUserId = SecurityUtils.getCurrentUserId();
        boolean admin = isCurrentUserAdmin();
        String queryName = request.getName() == null ? null : request.getName().trim();

        LambdaQueryWrapper<Paper> wrapper = new LambdaQueryWrapper<Paper>()
            .eq(request.getSubjectId() != null, Paper::getSubjectId, request.getSubjectId())
            .like(queryName != null && !queryName.isBlank(), Paper::getName, queryName)
            .orderByDesc(Paper::getCreateTime);

        if (admin) {
            wrapper.eq(request.getCreatorId() != null, Paper::getTeacherId, request.getCreatorId());
        } else {
            wrapper.eq(Paper::getTeacherId, currentUserId == null ? -1L : currentUserId);
        }

        Page<Paper> page = paperMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        Map<Long, String> subjectNameMap = buildSubjectNameMap(page.getRecords());

        List<PaperListItemView> records = page.getRecords().stream().map(paper -> PaperListItemView.builder()
            .id(toIdString(paper.getId()))
            .name(paper.getName())
            .subjectId(toIdString(paper.getSubjectId()))
            .subjectName(subjectNameMap.get(paper.getSubjectId()))
            .totalScore(paper.getTotalScore())
            .teacherId(toIdString(paper.getTeacherId()))
            .createTime(paper.getCreateTime())
            .canManage(canManage(paper))
            .build()).toList();

        return PageResponse.<PaperListItemView>builder()
            .pageNum(page.getCurrent())
            .pageSize(page.getSize())
            .total(page.getTotal())
            .records(records)
            .build();
    }

    public PaperDetailView getDetail(Long paperId) {
        Paper paper = ensurePaper(paperId);
        List<PaperQuestion> links = paperQuestionMapper.selectList(
            new LambdaQueryWrapper<PaperQuestion>()
                .eq(PaperQuestion::getPaperId, paperId)
                .orderByAsc(PaperQuestion::getSortOrder)
        );

        List<Long> qIds = links.stream().map(PaperQuestion::getQuestionId).toList();
        Map<Long, Question> questionMap = qIds.isEmpty()
            ? Collections.emptyMap()
            : questionMapper.selectBatchIds(qIds).stream()
                .collect(Collectors.toMap(Question::getId, q -> q, (a, b) -> a, HashMap::new));
        Map<Long, List<PaperQuestionAssetView>> questionAssetMap = buildQuestionAssetMap(qIds);

        List<PaperQuestionView> questions = links.stream().map(link -> {
            Question q = questionMap.get(link.getQuestionId());
            return PaperQuestionView.builder()
                .questionId(toIdString(link.getQuestionId()))
                .type(q == null ? null : q.getType())
                .difficulty(q == null ? null : q.getDifficulty())
                .content(q == null ? null : q.getContent())
                .optionsJson(q == null ? null : q.getOptionsJson())
                .answer(q == null ? null : q.getAnswer())
                .score(link.getScore())
                .sortOrder(link.getSortOrder())
                .assets(questionAssetMap.getOrDefault(link.getQuestionId(), List.of()))
                .build();
        }).toList();

        Subject subject = subjectMapper.selectById(paper.getSubjectId());

        return PaperDetailView.builder()
            .id(toIdString(paper.getId()))
            .name(paper.getName())
            .subjectId(toIdString(paper.getSubjectId()))
            .subjectName(subject == null ? null : subject.getName())
            .description(paper.getDescription())
            .totalScore(paper.getTotalScore())
            .teacherId(toIdString(paper.getTeacherId()))
            .questions(questions)
            .build();
    }

    @Transactional
    public void update(Long paperId, PaperUpdateRequest request) {
        Paper paper = ensurePaper(paperId);
        ensureManagePermission(paper);
        ensureSubjectExists(request.getSubjectId());
        validateManualQuestions(request.getSubjectId(), request.getQuestions());

        paper.setName(request.getName());
        paper.setSubjectId(request.getSubjectId());
        paper.setDescription(request.getDescription());

        paperQuestionMapper.delete(new LambdaQueryWrapper<PaperQuestion>().eq(PaperQuestion::getPaperId, paperId));
        int totalScore = insertPaperQuestions(paperId, request.getQuestions());

        paper.setTotalScore(totalScore);
        paperMapper.updateById(paper);
    }

    @Transactional
    public void delete(Long paperId) {
        Paper paper = ensurePaper(paperId);
        ensureManagePermission(paper);

        Long examRefCount = examMapper.selectCount(new LambdaQueryWrapper<Exam>().eq(Exam::getPaperId, paperId));
        if (examRefCount != null && examRefCount > 0) {
            throw new BusinessException(PAPER_REFERENCED_BY_EXAM, "试卷已被考试引用，不能删除");
        }

        paperQuestionMapper.delete(new LambdaQueryWrapper<PaperQuestion>().eq(PaperQuestion::getPaperId, paperId));
        paperMapper.deleteById(paperId);
    }

    private Paper buildPaper(String name, Long subjectId, String description) {
        Paper paper = new Paper();
        paper.setName(name);
        paper.setSubjectId(subjectId);
        paper.setDescription(description);
        paper.setTotalScore(0);
        paper.setTeacherId(SecurityUtils.getCurrentUserId());
        return paper;
    }

    private Paper ensurePaper(Long paperId) {
        Paper paper = paperMapper.selectById(paperId);
        if (paper == null) {
            throw new BusinessException("试卷不存在");
        }
        return paper;
    }

    private void ensureSubjectExists(Long subjectId) {
        if (subjectId == null || subjectMapper.selectById(subjectId) == null) {
            throw new BusinessException("课程不存在");
        }
    }

    private void validateManualQuestions(Long subjectId, List<ManualPaperQuestion> questions) {
        if (questions == null || questions.isEmpty()) {
            throw new BusinessException("试卷至少需要一题");
        }

        Set<Long> questionIds = new LinkedHashSet<>();
        Set<Integer> sortOrders = new HashSet<>();

        for (ManualPaperQuestion item : questions) {
            if (item.getQuestionId() == null) {
                throw new BusinessException("存在空题目ID");
            }
            if (item.getScore() == null || item.getScore() < 1) {
                throw new BusinessException("题目分值必须大于0");
            }
            if (item.getSortOrder() == null || item.getSortOrder() < 1) {
                throw new BusinessException("题目排序必须大于0");
            }
            if (!questionIds.add(item.getQuestionId())) {
                throw new BusinessException("试卷中存在重复题目");
            }
            if (!sortOrders.add(item.getSortOrder())) {
                throw new BusinessException("试卷中题目排序重复");
            }
        }

        List<Question> dbQuestions = questionMapper.selectBatchIds(questionIds);
        if (dbQuestions.size() != questionIds.size()) {
            throw new BusinessException("存在无效题目");
        }
        Map<Long, Question> questionMap = dbQuestions.stream()
            .collect(Collectors.toMap(Question::getId, q -> q, (a, b) -> a));

        for (ManualPaperQuestion item : questions) {
            Question question = questionMap.get(item.getQuestionId());
            if (question == null) {
                throw new BusinessException("存在无效题目");
            }
            if (!Objects.equals(question.getSubjectId(), subjectId)) {
                throw new BusinessException("题目" + question.getId() + "不属于当前课程");
            }
        }
    }

    private int insertPaperQuestions(Long paperId, List<ManualPaperQuestion> questions) {
        int totalScore = 0;
        for (ManualPaperQuestion item : questions) {
            PaperQuestion pq = new PaperQuestion();
            pq.setPaperId(paperId);
            pq.setQuestionId(item.getQuestionId());
            pq.setScore(item.getScore());
            pq.setSortOrder(item.getSortOrder());
            paperQuestionMapper.insert(pq);
            totalScore += item.getScore();
        }
        return totalScore;
    }

    private Map<Long, String> buildSubjectNameMap(List<Paper> papers) {
        Set<Long> subjectIds = papers.stream()
            .map(Paper::getSubjectId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (subjectIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return subjectMapper.selectList(new LambdaQueryWrapper<Subject>().in(Subject::getId, subjectIds)).stream()
            .collect(Collectors.toMap(Subject::getId, Subject::getName, (a, b) -> a));
    }

    private Map<Long, List<PaperQuestionAssetView>> buildQuestionAssetMap(List<Long> questionIds) {
        if (questionIds == null || questionIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<QuestionAsset> assets = questionAssetMapper.selectList(
            new LambdaQueryWrapper<QuestionAsset>()
                .in(QuestionAsset::getQuestionId, questionIds)
                .orderByAsc(QuestionAsset::getQuestionId, QuestionAsset::getId)
        );
        if (assets.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Long, List<PaperQuestionAssetView>> result = new HashMap<>();
        for (QuestionAsset asset : assets) {
            if (asset.getQuestionId() == null) {
                continue;
            }
            result.computeIfAbsent(asset.getQuestionId(), k -> new ArrayList<>())
                .add(PaperQuestionAssetView.builder()
                    .assetId(toIdString(asset.getId()))
                    .url(assetUrlResolver.resolve(asset))
                    .fileType(asset.getFileType())
                    .originalName(asset.getOriginalName())
                    .size(asset.getSize())
                    .build());
        }
        return result;
    }

    private boolean canManage(Paper paper) {
        if (isCurrentUserAdmin()) {
            return true;
        }
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return currentUserId != null
            && paper.getTeacherId() != null
            && Objects.equals(currentUserId, paper.getTeacherId());
    }

    private void ensureManagePermission(Paper paper) {
        if (!canManage(paper)) {
            throw new BusinessException("无权限操作他人试卷");
        }
    }

    private boolean isCurrentUserAdmin() {
        return SecurityUtils.getCurrentRoles().contains("ADMIN");
    }

    private String difficultyLabel(String difficulty) {
        return difficulty == null ? "不限" : difficulty;
    }

    private String toIdString(Long id) {
        return id == null ? null : String.valueOf(id);
    }
}
