package com.ekusys.exam.grading.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ekusys.exam.common.enums.SubmissionStatus;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.SecurityUtils;
import com.ekusys.exam.exam.service.ExamPermissionService;
import com.ekusys.exam.grading.dto.PendingAnswerView;
import com.ekusys.exam.grading.dto.PendingQuestionAnswerView;
import com.ekusys.exam.grading.dto.PendingQuestionGroupView;
import com.ekusys.exam.grading.dto.QuestionBatchScoreRequest;
import com.ekusys.exam.grading.dto.SubjectiveScoreItem;
import com.ekusys.exam.grading.dto.SubjectiveScoreRequest;
import com.ekusys.exam.repository.entity.Exam;
import com.ekusys.exam.repository.entity.PaperQuestion;
import com.ekusys.exam.repository.entity.Question;
import com.ekusys.exam.repository.entity.Submission;
import com.ekusys.exam.repository.entity.SubmissionAnswer;
import com.ekusys.exam.repository.entity.SubjectiveGrade;
import com.ekusys.exam.repository.entity.User;
import com.ekusys.exam.repository.mapper.ExamMapper;
import com.ekusys.exam.repository.mapper.PaperQuestionMapper;
import com.ekusys.exam.repository.mapper.QuestionMapper;
import com.ekusys.exam.repository.mapper.SubmissionAnswerMapper;
import com.ekusys.exam.repository.mapper.SubmissionMapper;
import com.ekusys.exam.repository.mapper.SubjectiveGradeMapper;
import com.ekusys.exam.repository.mapper.UserMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GradingService {

    private final SubmissionMapper submissionMapper;
    private final SubmissionAnswerMapper submissionAnswerMapper;
    private final PaperQuestionMapper paperQuestionMapper;
    private final QuestionMapper questionMapper;
    private final SubjectiveGradeMapper subjectiveGradeMapper;
    private final ExamMapper examMapper;
    private final UserMapper userMapper;
    private final ExamPermissionService examPermissionService;

    public GradingService(SubmissionMapper submissionMapper,
                          SubmissionAnswerMapper submissionAnswerMapper,
                          PaperQuestionMapper paperQuestionMapper,
                          QuestionMapper questionMapper,
                          SubjectiveGradeMapper subjectiveGradeMapper,
                          ExamMapper examMapper,
                          UserMapper userMapper,
                          ExamPermissionService examPermissionService) {
        this.submissionMapper = submissionMapper;
        this.submissionAnswerMapper = submissionAnswerMapper;
        this.paperQuestionMapper = paperQuestionMapper;
        this.questionMapper = questionMapper;
        this.subjectiveGradeMapper = subjectiveGradeMapper;
        this.examMapper = examMapper;
        this.userMapper = userMapper;
        this.examPermissionService = examPermissionService;
    }

    public List<PendingAnswerView> pendingAnswers() {
        GradingContext context = loadGradingContext();
        if (context.submissions().isEmpty()) {
            return List.of();
        }

        Set<Long> studentIds = context.submissions().stream().map(Submission::getStudentId).collect(Collectors.toSet());
        Map<Long, User> userMap = studentIds.isEmpty()
            ? Map.of()
            : userMapper.selectBatchIds(studentIds).stream().collect(Collectors.toMap(User::getId, user -> user, (a, b) -> a));

        List<SubmissionAnswer> answers = loadAnswersBySubmissionIds(context.submissionIds());
        Map<Long, List<SubmissionAnswer>> answersBySubmission = answers.stream()
            .collect(Collectors.groupingBy(SubmissionAnswer::getSubmissionId));

        Map<Long, Question> questionMap = loadQuestionMap(answers);

        List<PendingAnswerView> result = new ArrayList<>();
        for (Submission submission : context.submissions()) {
            List<SubmissionAnswer> submissionAnswers = answersBySubmission.getOrDefault(submission.getId(), List.of());
            Exam exam = context.examMap().get(submission.getExamId());
            User student = userMap.get(submission.getStudentId());
            for (SubmissionAnswer answer : submissionAnswers) {
                Question question = questionMap.get(answer.getQuestionId());
                if (question == null || !"SHORT".equals(question.getType()) || answer.getSubjectiveScore() != null) {
                    continue;
                }
                result.add(PendingAnswerView.builder()
                    .submissionId(submission.getId())
                    .submissionAnswerId(answer.getId())
                    .examId(submission.getExamId())
                    .examName(exam == null ? "" : exam.getName())
                    .studentId(submission.getStudentId())
                    .studentName(student == null ? "" : student.getRealName())
                    .questionId(answer.getQuestionId())
                    .questionContent(question.getContent())
                    .answerText(answer.getAnswerText())
                    .build());
            }
        }
        return result;
    }

    public List<PendingQuestionGroupView> pendingQuestionGroups() {
        GradingContext context = loadGradingContext();
        if (context.submissions().isEmpty()) {
            return List.of();
        }

        List<SubmissionAnswer> answers = context.answersBySubmission().values().stream()
            .flatMap(List::stream)
            .toList();
        Map<Long, Question> questionMap = loadQuestionMap(answers);
        Map<String, PaperQuestion> paperQuestionMap = loadPaperQuestionMap(context.examMap().values());
        Map<QuestionGroupKey, PendingQuestionGroupView> groups = new LinkedHashMap<>();

        for (Submission submission : context.submissions()) {
            for (SubmissionAnswer answer : context.answersBySubmission().getOrDefault(submission.getId(), List.of())) {
                Question question = questionMap.get(answer.getQuestionId());
                if (!isPendingShortAnswer(answer, question)) {
                    continue;
                }
                Exam exam = context.examMap().get(submission.getExamId());
                if (exam == null) {
                    continue;
                }
                String paperQuestionKey = paperQuestionKey(exam.getPaperId(), question.getId());
                PaperQuestion paperQuestion = paperQuestionMap.get(paperQuestionKey);
                QuestionGroupKey groupKey = new QuestionGroupKey(exam.getId(), question.getId());
                PendingQuestionGroupView existing = groups.get(groupKey);
                if (existing == null) {
                    groups.put(groupKey, PendingQuestionGroupView.builder()
                        .examId(exam.getId())
                        .examName(exam.getName())
                        .questionId(question.getId())
                        .questionContent(question.getContent())
                        .referenceAnswer(question.getAnswer())
                        .analysis(question.getAnalysis())
                        .defaultScore(resolveScoreLimit(question, paperQuestion))
                        .sortOrder(paperQuestion == null ? null : paperQuestion.getSortOrder())
                        .pendingCount(1)
                        .build());
                } else {
                    existing.setPendingCount(existing.getPendingCount() + 1);
                }
            }
        }

        return groups.values().stream()
            .sorted((left, right) -> {
                LocalDateTime leftStart = resolveExamStartTime(context.examMap().get(left.getExamId()));
                LocalDateTime rightStart = resolveExamStartTime(context.examMap().get(right.getExamId()));
                int examCompare = rightStart.compareTo(leftStart);
                if (examCompare != 0) {
                    return examCompare;
                }
                int sortCompare = Integer.compare(
                    left.getSortOrder() == null ? Integer.MAX_VALUE : left.getSortOrder(),
                    right.getSortOrder() == null ? Integer.MAX_VALUE : right.getSortOrder()
                );
                if (sortCompare != 0) {
                    return sortCompare;
                }
                return Long.compare(
                    left.getQuestionId() == null ? Long.MAX_VALUE : left.getQuestionId(),
                    right.getQuestionId() == null ? Long.MAX_VALUE : right.getQuestionId()
                );
            })
            .toList();
    }

    public List<PendingQuestionAnswerView> pendingQuestionAnswers(Long questionId, Long examId) {
        Exam exam = examMapper.selectById(examId);
        examPermissionService.ensureCanManageExam(exam, "无权限批阅该考试");

        Question question = questionMapper.selectById(questionId);
        ensureShortQuestion(question);

        List<Submission> submissions = submissionMapper.selectList(
            new LambdaQueryWrapper<Submission>()
                .eq(Submission::getExamId, examId)
                .eq(Submission::getStatus, SubmissionStatus.SUBMITTED.name())
        );
        if (submissions.isEmpty()) {
            return List.of();
        }

        Map<Long, Submission> submissionMap = submissions.stream()
            .collect(Collectors.toMap(Submission::getId, item -> item, (a, b) -> a));
        List<Long> submissionIds = submissions.stream().map(Submission::getId).toList();
        List<SubmissionAnswer> answers = submissionAnswerMapper.selectList(
            new LambdaQueryWrapper<SubmissionAnswer>()
                .in(SubmissionAnswer::getSubmissionId, submissionIds)
                .eq(SubmissionAnswer::getQuestionId, questionId)
                .isNull(SubmissionAnswer::getSubjectiveScore)
        );
        if (answers.isEmpty()) {
            return List.of();
        }

        Set<Long> studentIds = submissions.stream().map(Submission::getStudentId).collect(Collectors.toSet());
        Map<Long, User> userMap = studentIds.isEmpty()
            ? Map.of()
            : userMapper.selectBatchIds(studentIds).stream().collect(Collectors.toMap(User::getId, item -> item, (a, b) -> a));

        return answers.stream()
            .map(answer -> {
                Submission submission = submissionMap.get(answer.getSubmissionId());
                User student = submission == null ? null : userMap.get(submission.getStudentId());
                return PendingQuestionAnswerView.builder()
                    .submissionId(answer.getSubmissionId())
                    .submissionAnswerId(answer.getId())
                    .studentId(submission == null ? null : submission.getStudentId())
                    .studentName(student == null ? "" : student.getRealName())
                    .answerText(answer.getAnswerText())
                    .submittedAt(submission == null ? null : submission.getSubmittedAt())
                    .build();
            })
            .sorted((left, right) -> {
                LocalDateTime leftSubmittedAt = left.getSubmittedAt() == null ? LocalDateTime.MAX : left.getSubmittedAt();
                LocalDateTime rightSubmittedAt = right.getSubmittedAt() == null ? LocalDateTime.MAX : right.getSubmittedAt();
                int submittedAtCompare = leftSubmittedAt.compareTo(rightSubmittedAt);
                if (submittedAtCompare != 0) {
                    return submittedAtCompare;
                }
                return Long.compare(
                    left.getSubmissionId() == null ? Long.MAX_VALUE : left.getSubmissionId(),
                    right.getSubmissionId() == null ? Long.MAX_VALUE : right.getSubmissionId()
                );
            })
            .toList();
    }

    @Transactional
    public void scoreSubjective(Long submissionId, SubjectiveScoreRequest request) {
        Submission submission = submissionMapper.selectById(submissionId);
        if (submission == null) {
            throw new BusinessException("提交记录不存在");
        }
        Exam exam = examMapper.selectById(submission.getExamId());
        examPermissionService.ensureCanManageExam(exam, "无权限批阅该考试");

        List<Long> scoreAnswerIds = request.getScores().stream().map(SubjectiveScoreItem::getSubmissionAnswerId).toList();
        Map<Long, SubmissionAnswer> answerMap = submissionAnswerMapper.selectBatchIds(scoreAnswerIds).stream()
            .collect(Collectors.toMap(SubmissionAnswer::getId, answer -> answer, (a, b) -> a));
        Set<Long> questionIds = answerMap.values().stream()
            .map(SubmissionAnswer::getQuestionId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(HashSet::new));
        Map<Long, Question> questionMap = questionIds.isEmpty()
            ? Map.of()
            : questionMapper.selectBatchIds(questionIds).stream().collect(Collectors.toMap(Question::getId, question -> question, (a, b) -> a));
        Map<Long, PaperQuestion> paperQuestionMap = loadPaperQuestionMap(exam, questionIds);

        for (SubjectiveScoreItem score : request.getScores()) {
            SubmissionAnswer answer = answerMap.get(score.getSubmissionAnswerId());
            if (answer == null || !submissionId.equals(answer.getSubmissionId())) {
                throw new BusinessException("主观题记录不存在");
            }
            Question question = questionMap.get(answer.getQuestionId());
            ensureShortQuestion(question);
            validateScore(score.getScore(), question, paperQuestionMap.get(answer.getQuestionId()));

            answer.setSubjectiveScore(score.getScore());
            submissionAnswerMapper.updateById(answer);

            SubjectiveGrade grade = new SubjectiveGrade();
            grade.setSubmissionAnswerId(answer.getId());
            grade.setTeacherId(SecurityUtils.getCurrentUserId());
            grade.setScore(score.getScore());
            grade.setComment(score.getComment());
            grade.setGradedAt(LocalDateTime.now());
            subjectiveGradeMapper.insert(grade);
        }

        recalculateSubmissionScores(submission, exam);
    }

    @Transactional
    public void scoreQuestionAnswers(Long questionId, QuestionBatchScoreRequest request) {
        Exam exam = examMapper.selectById(request.getExamId());
        examPermissionService.ensureCanManageExam(exam, "无权限批阅该考试");

        Question question = questionMapper.selectById(questionId);
        ensureShortQuestion(question);
        PaperQuestion paperQuestion = loadPaperQuestionMap(exam, Set.of(questionId)).get(questionId);
        validateScore(request.getScore(), question, paperQuestion);

        List<SubmissionAnswer> answers = submissionAnswerMapper.selectBatchIds(request.getSubmissionAnswerIds());
        if (answers.size() != request.getSubmissionAnswerIds().size()) {
            throw new BusinessException("存在无效的主观题记录");
        }

        Set<Long> submissionIds = answers.stream()
            .map(SubmissionAnswer::getSubmissionId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(HashSet::new));
        Map<Long, Submission> submissionMap = submissionIds.isEmpty()
            ? Map.of()
            : submissionMapper.selectBatchIds(submissionIds).stream()
                .collect(Collectors.toMap(Submission::getId, item -> item, (a, b) -> a));

        Long teacherId = SecurityUtils.getCurrentUserId();
        LocalDateTime now = LocalDateTime.now();
        for (SubmissionAnswer answer : answers) {
            Submission submission = submissionMap.get(answer.getSubmissionId());
            if (submission == null
                || !Objects.equals(submission.getExamId(), request.getExamId())
                || !SubmissionStatus.SUBMITTED.name().equals(submission.getStatus())) {
                throw new BusinessException("主观题记录不属于当前待批考试");
            }
            if (!Objects.equals(answer.getQuestionId(), questionId)) {
                throw new BusinessException("请选择同一题目的答案进行批量评分");
            }
            if (answer.getSubjectiveScore() != null) {
                throw new BusinessException("选中的答案中存在已批记录，请刷新后重试");
            }

            answer.setSubjectiveScore(request.getScore());
            submissionAnswerMapper.updateById(answer);

            SubjectiveGrade grade = new SubjectiveGrade();
            grade.setSubmissionAnswerId(answer.getId());
            grade.setTeacherId(teacherId);
            grade.setScore(request.getScore());
            grade.setComment(request.getComment());
            grade.setGradedAt(now);
            subjectiveGradeMapper.insert(grade);
        }

        for (Long submissionId : submissionIds) {
            Submission submission = submissionMap.get(submissionId);
            if (submission != null) {
                recalculateSubmissionScores(submission, exam);
            }
        }
    }

    private GradingContext loadGradingContext() {
        List<Submission> submissions = submissionMapper.selectList(
            new LambdaQueryWrapper<Submission>().eq(Submission::getStatus, SubmissionStatus.SUBMITTED.name())
        );
        if (submissions.isEmpty()) {
            return new GradingContext(List.of(), Map.of(), List.of(), Map.of());
        }

        Set<Long> examIds = submissions.stream().map(Submission::getExamId).collect(Collectors.toSet());
        Map<Long, Exam> examMap = examIds.isEmpty()
            ? Map.of()
            : examMapper.selectBatchIds(examIds).stream().collect(Collectors.toMap(Exam::getId, exam -> exam, (a, b) -> a));
        Set<Long> manageableExamIds = examPermissionService.filterManageableExams(examMap.values()).stream()
            .map(Exam::getId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (manageableExamIds.isEmpty()) {
            return new GradingContext(List.of(), examMap, List.of(), Map.of());
        }

        List<Submission> manageableSubmissions = submissions.stream()
            .filter(item -> manageableExamIds.contains(item.getExamId()))
            .toList();
        List<Long> submissionIds = manageableSubmissions.stream().map(Submission::getId).toList();
        Map<Long, List<SubmissionAnswer>> answersBySubmission = loadAnswersBySubmissionIds(submissionIds).stream()
            .collect(Collectors.groupingBy(SubmissionAnswer::getSubmissionId));
        return new GradingContext(manageableSubmissions, examMap, submissionIds, answersBySubmission);
    }

    private List<SubmissionAnswer> loadAnswersBySubmissionIds(List<Long> submissionIds) {
        if (submissionIds == null || submissionIds.isEmpty()) {
            return List.of();
        }
        return submissionAnswerMapper.selectList(
            new LambdaQueryWrapper<SubmissionAnswer>().in(SubmissionAnswer::getSubmissionId, submissionIds)
        );
    }

    private Map<Long, Question> loadQuestionMap(List<SubmissionAnswer> answers) {
        Set<Long> questionIds = answers.stream()
            .map(SubmissionAnswer::getQuestionId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(HashSet::new));
        if (questionIds.isEmpty()) {
            return Map.of();
        }
        return questionMapper.selectBatchIds(questionIds).stream()
            .collect(Collectors.toMap(Question::getId, question -> question, (a, b) -> a));
    }

    private Map<String, PaperQuestion> loadPaperQuestionMap(Iterable<Exam> exams) {
        Set<Long> paperIds = new HashSet<>();
        for (Exam exam : exams) {
            if (exam != null && exam.getPaperId() != null) {
                paperIds.add(exam.getPaperId());
            }
        }
        if (paperIds.isEmpty()) {
            return Map.of();
        }
        return paperQuestionMapper.selectList(new LambdaQueryWrapper<PaperQuestion>().in(PaperQuestion::getPaperId, paperIds))
            .stream()
            .collect(Collectors.toMap(
                item -> paperQuestionKey(item.getPaperId(), item.getQuestionId()),
                item -> item,
                (left, right) -> left
            ));
    }

    private Map<Long, PaperQuestion> loadPaperQuestionMap(Exam exam, Set<Long> questionIds) {
        if (exam == null || exam.getPaperId() == null || questionIds == null || questionIds.isEmpty()) {
            return Map.of();
        }
        return paperQuestionMapper.selectList(
                new LambdaQueryWrapper<PaperQuestion>()
                    .eq(PaperQuestion::getPaperId, exam.getPaperId())
                    .in(PaperQuestion::getQuestionId, questionIds)
            ).stream()
            .collect(Collectors.toMap(PaperQuestion::getQuestionId, item -> item, (left, right) -> left));
    }

    private void recalculateSubmissionScores(Submission submission, Exam exam) {
        List<SubmissionAnswer> answers = submissionAnswerMapper.selectList(
            new LambdaQueryWrapper<SubmissionAnswer>().eq(SubmissionAnswer::getSubmissionId, submission.getId())
        );
        Map<Long, Question> questionMap = loadQuestionMap(answers);

        int objective = answers.stream().map(SubmissionAnswer::getObjectiveScore).filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
        int subjective = answers.stream().map(SubmissionAnswer::getSubjectiveScore).filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
        boolean allShortScored = answers.stream().allMatch(answer -> {
            Question q = questionMap.get(answer.getQuestionId());
            return q == null || !"SHORT".equals(q.getType()) || answer.getSubjectiveScore() != null;
        });

        submission.setObjectiveScore(objective);
        submission.setSubjectiveScore(subjective);
        submission.setTotalScore(objective + subjective);
        submission.setPassFlag(exam != null && submission.getTotalScore() >= exam.getPassScore());
        submission.setStatus(allShortScored ? SubmissionStatus.GRADED.name() : SubmissionStatus.SUBMITTED.name());
        submissionMapper.updateById(submission);
    }

    private void ensureShortQuestion(Question question) {
        if (question == null || !"SHORT".equals(question.getType())) {
            throw new BusinessException("仅允许批阅简答题");
        }
    }

    private void validateScore(Integer score, Question question, PaperQuestion paperQuestion) {
        if (score == null) {
            throw new BusinessException("评分不能为空");
        }
        int maxScore = resolveScoreLimit(question, paperQuestion);
        if (score < 0 || score > maxScore) {
            throw new BusinessException("评分必须在 0 到题目分值之间");
        }
    }

    private int resolveScoreLimit(Question question, PaperQuestion paperQuestion) {
        if (paperQuestion != null && paperQuestion.getScore() != null) {
            return paperQuestion.getScore();
        }
        return question == null || question.getDefaultScore() == null ? 0 : question.getDefaultScore();
    }

    private boolean isPendingShortAnswer(SubmissionAnswer answer, Question question) {
        return question != null && "SHORT".equals(question.getType()) && answer.getSubjectiveScore() == null;
    }

    private LocalDateTime resolveExamStartTime(Exam exam) {
        return exam == null || exam.getStartTime() == null ? LocalDateTime.MIN : exam.getStartTime();
    }

    private String paperQuestionKey(Long paperId, Long questionId) {
        return String.valueOf(paperId) + "_" + questionId;
    }

    private record GradingContext(List<Submission> submissions,
                                  Map<Long, Exam> examMap,
                                  List<Long> submissionIds,
                                  Map<Long, List<SubmissionAnswer>> answersBySubmission) {
    }

    private record QuestionGroupKey(Long examId, Long questionId) {
    }
}
