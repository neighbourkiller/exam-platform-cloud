package com.ekusys.exam.analytics.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ekusys.exam.analytics.dto.ClassTrendItem;
import com.ekusys.exam.analytics.dto.ExamOverviewItem;
import com.ekusys.exam.analytics.dto.ScoreDistributionItem;
import com.ekusys.exam.analytics.dto.StudentScoreItem;
import com.ekusys.exam.analytics.dto.WrongTopicItem;
import com.ekusys.exam.common.enums.SubmissionStatus;
import com.ekusys.exam.exam.service.ExamPermissionService;
import com.ekusys.exam.repository.entity.Exam;
import com.ekusys.exam.repository.entity.ExamTargetClass;
import com.ekusys.exam.repository.entity.Paper;
import com.ekusys.exam.repository.entity.Question;
import com.ekusys.exam.repository.entity.StudentProfile;
import com.ekusys.exam.repository.entity.StudentTeachingClass;
import com.ekusys.exam.repository.entity.Submission;
import com.ekusys.exam.repository.entity.SubmissionAnswer;
import com.ekusys.exam.repository.entity.TeachingClass;
import com.ekusys.exam.repository.entity.User;
import com.ekusys.exam.repository.mapper.ExamTargetClassMapper;
import com.ekusys.exam.repository.mapper.ExamMapper;
import com.ekusys.exam.repository.mapper.PaperMapper;
import com.ekusys.exam.repository.mapper.QuestionMapper;
import com.ekusys.exam.repository.mapper.StudentProfileMapper;
import com.ekusys.exam.repository.mapper.StudentTeachingClassMapper;
import com.ekusys.exam.repository.mapper.SubmissionAnswerMapper;
import com.ekusys.exam.repository.mapper.SubmissionMapper;
import com.ekusys.exam.repository.mapper.TeachingClassMapper;
import com.ekusys.exam.repository.mapper.UserMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsService {

    private static final int DEFAULT_TOP_N = 10;
    private static final int MIN_TOP_N = 1;
    private static final int MAX_TOP_N = 50;

    private final SubmissionMapper submissionMapper;
    private final SubmissionAnswerMapper submissionAnswerMapper;
    private final ExamMapper examMapper;
    private final ExamTargetClassMapper examTargetClassMapper;
    private final PaperMapper paperMapper;
    private final StudentProfileMapper studentProfileMapper;
    private final StudentTeachingClassMapper studentTeachingClassMapper;
    private final TeachingClassMapper teachingClassMapper;
    private final QuestionMapper questionMapper;
    private final UserMapper userMapper;
    private final ExamPermissionService examPermissionService;

    public AnalyticsService(SubmissionMapper submissionMapper,
                            SubmissionAnswerMapper submissionAnswerMapper,
                            ExamMapper examMapper,
                            ExamTargetClassMapper examTargetClassMapper,
                            PaperMapper paperMapper,
                            StudentProfileMapper studentProfileMapper,
                            StudentTeachingClassMapper studentTeachingClassMapper,
                            TeachingClassMapper teachingClassMapper,
                            QuestionMapper questionMapper,
                            UserMapper userMapper,
                            ExamPermissionService examPermissionService) {
        this.submissionMapper = submissionMapper;
        this.submissionAnswerMapper = submissionAnswerMapper;
        this.examMapper = examMapper;
        this.examTargetClassMapper = examTargetClassMapper;
        this.paperMapper = paperMapper;
        this.studentProfileMapper = studentProfileMapper;
        this.studentTeachingClassMapper = studentTeachingClassMapper;
        this.teachingClassMapper = teachingClassMapper;
        this.questionMapper = questionMapper;
        this.userMapper = userMapper;
        this.examPermissionService = examPermissionService;
    }

    public List<ScoreDistributionItem> scoreDistribution(Long examId) {
        ensureExamManagePermission(examId);
        List<Submission> submissions = submissionMapper.selectList(
            new LambdaQueryWrapper<Submission>().eq(Submission::getExamId, examId)
        );
        int[] buckets = new int[5];
        for (Submission submission : submissions) {
            int score = submission.getTotalScore() == null ? 0 : submission.getTotalScore();
            if (score < 60) {
                buckets[0]++;
            } else if (score < 70) {
                buckets[1]++;
            } else if (score < 80) {
                buckets[2]++;
            } else if (score < 90) {
                buckets[3]++;
            } else {
                buckets[4]++;
            }
        }
        return List.of(
            ScoreDistributionItem.builder().range("0-59").count(buckets[0]).build(),
            ScoreDistributionItem.builder().range("60-69").count(buckets[1]).build(),
            ScoreDistributionItem.builder().range("70-79").count(buckets[2]).build(),
            ScoreDistributionItem.builder().range("80-89").count(buckets[3]).build(),
            ScoreDistributionItem.builder().range("90-100").count(buckets[4]).build()
        );
    }

    public ExamOverviewItem overview(Long examId) {
        Exam exam = ensureExamManagePermission(examId);
        List<Submission> submissions = submissionMapper.selectList(
            new LambdaQueryWrapper<Submission>().eq(Submission::getExamId, examId)
        );
        if (submissions.isEmpty()) {
            return ExamOverviewItem.builder()
                .totalStudents(0)
                .passCount(0)
                .passRate(0D)
                .avgScore(0D)
                .maxScore(null)
                .minScore(null)
                .build();
        }

        int passScore = exam == null || exam.getPassScore() == null ? 60 : exam.getPassScore();

        int passCount = 0;
        int maxScore = Integer.MIN_VALUE;
        int minScore = Integer.MAX_VALUE;
        long totalScore = 0;
        for (Submission submission : submissions) {
            int score = submission.getTotalScore() == null ? 0 : submission.getTotalScore();
            totalScore += score;
            maxScore = Math.max(maxScore, score);
            minScore = Math.min(minScore, score);
            if (score >= passScore) {
                passCount++;
            }
        }

        double avgScore = totalScore * 1.0 / submissions.size();
        double passRate = passCount * 100.0 / submissions.size();
        return ExamOverviewItem.builder()
            .totalStudents(submissions.size())
            .passCount(passCount)
            .passRate(round2(passRate))
            .avgScore(round2(avgScore))
            .maxScore(maxScore)
            .minScore(minScore)
            .build();
    }

    public List<ClassTrendItem> classTrend(Long examId) {
        ensureExamManagePermission(examId);
        List<Submission> submissions = submissionMapper.selectList(new LambdaQueryWrapper<Submission>().eq(Submission::getExamId, examId));
        if (submissions.isEmpty()) {
            return List.of();
        }

        Long subjectId = findExamSubjectId(examId);
        if (subjectId == null) {
            return List.of();
        }

        Set<Long> studentIds = submissions.stream().map(Submission::getStudentId).collect(Collectors.toSet());
        if (studentIds.isEmpty()) {
            return List.of();
        }

        List<StudentTeachingClass> bindings = studentTeachingClassMapper.selectList(
            new LambdaQueryWrapper<StudentTeachingClass>()
                .in(StudentTeachingClass::getStudentId, studentIds)
                .eq(StudentTeachingClass::getSubjectId, subjectId)
                .eq(StudentTeachingClass::getEnrollStatus, "ACTIVE")
        );
        Map<Long, Long> studentClassMap = bindings.stream()
            .collect(Collectors.toMap(StudentTeachingClass::getStudentId, StudentTeachingClass::getTeachingClassId, (a, b) -> a));

        Set<Long> classIds = bindings.stream().map(StudentTeachingClass::getTeachingClassId).collect(Collectors.toSet());
        Map<Long, TeachingClass> classMap = classIds.isEmpty()
            ? Map.of()
            : teachingClassMapper.selectBatchIds(classIds).stream()
                .collect(Collectors.toMap(TeachingClass::getId, c -> c, (a, b) -> a));

        Map<Long, List<Integer>> classScores = new HashMap<>();
        for (Submission submission : submissions) {
            Long classId = studentClassMap.get(submission.getStudentId());
            if (classId != null) {
                classScores.computeIfAbsent(classId, k -> new ArrayList<>()).add(submission.getTotalScore() == null ? 0 : submission.getTotalScore());
            }
        }

        List<ClassTrendItem> items = new ArrayList<>();
        for (Map.Entry<Long, List<Integer>> entry : classScores.entrySet()) {
            TeachingClass classroom = classMap.get(entry.getKey());
            double avg = entry.getValue().stream().mapToInt(Integer::intValue).average().orElse(0);
            items.add(ClassTrendItem.builder()
                .classId(entry.getKey())
                .className(classroom == null ? "未知班级" : classroom.getName())
                .avgScore(Math.round(avg * 100.0) / 100.0)
                .build());
        }
        items.sort(Comparator.comparing(ClassTrendItem::getClassId));
        return items;
    }

    public List<WrongTopicItem> wrongTopics(Long examId) {
        return wrongTopics(examId, DEFAULT_TOP_N);
    }

    public List<WrongTopicItem> wrongTopics(Long examId, Integer topN) {
        ensureExamManagePermission(examId);
        List<Submission> submissions = submissionMapper.selectList(new LambdaQueryWrapper<Submission>().eq(Submission::getExamId, examId));
        if (submissions.isEmpty()) {
            return List.of();
        }
        int safeTopN = sanitizeTopN(topN);
        List<Long> submissionIds = submissions.stream().map(Submission::getId).toList();
        List<SubmissionAnswer> answers = submissionAnswerMapper.selectList(new LambdaQueryWrapper<SubmissionAnswer>()
            .in(SubmissionAnswer::getSubmissionId, submissionIds)
            .isNotNull(SubmissionAnswer::getObjectiveCorrect));

        Map<Long, Integer> total = new HashMap<>();
        Map<Long, Integer> wrong = new HashMap<>();

        for (SubmissionAnswer answer : answers) {
            Long qid = answer.getQuestionId();
            total.put(qid, total.getOrDefault(qid, 0) + 1);
            if (Boolean.FALSE.equals(answer.getObjectiveCorrect())) {
                wrong.put(qid, wrong.getOrDefault(qid, 0) + 1);
            }
        }

        Map<Long, Question> questionMap = questionMapper.selectBatchIds(total.keySet()).stream()
            .collect(Collectors.toMap(Question::getId, question -> question, (a, b) -> a));

        List<WrongTopicItem> items = new ArrayList<>();
        for (Long qid : total.keySet()) {
            int t = total.getOrDefault(qid, 0);
            int w = wrong.getOrDefault(qid, 0);
            Question question = questionMap.get(qid);
            double rate = t == 0 ? 0 : (w * 1.0 / t);
            items.add(WrongTopicItem.builder()
                .questionId(qid)
                .questionContent(question == null ? "" : question.getContent())
                .wrongRate(Math.round(rate * 10000.0) / 100.0)
                .wrongCount(w)
                .totalCount(t)
                .build());
        }

        return items.stream()
            .sorted((a, b) -> Double.compare(b.getWrongRate(), a.getWrongRate()))
            .limit(safeTopN)
            .toList();
    }

    public List<StudentScoreItem> studentScores(Long examId) {
        ensureExamManagePermission(examId);
        List<ExamTargetClass> targetClasses = examTargetClassMapper.selectList(
            new LambdaQueryWrapper<ExamTargetClass>().eq(ExamTargetClass::getExamId, examId)
        );
        Set<Long> classIds = targetClasses.stream()
            .map(ExamTargetClass::getClassId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (classIds.isEmpty()) {
            return List.of();
        }

        Map<Long, TeachingClass> classMap = teachingClassMapper.selectBatchIds(classIds).stream()
            .filter(Objects::nonNull)
            .collect(Collectors.toMap(TeachingClass::getId, item -> item, (a, b) -> a));

        List<StudentTeachingClass> enrollments = studentTeachingClassMapper.selectList(
            new LambdaQueryWrapper<StudentTeachingClass>()
                .in(StudentTeachingClass::getTeachingClassId, classIds)
                .eq(StudentTeachingClass::getEnrollStatus, "ACTIVE")
        );
        Set<Long> studentIds = enrollments.stream()
            .map(StudentTeachingClass::getStudentId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (studentIds.isEmpty()) {
            return List.of();
        }

        Map<Long, List<String>> classNamesByStudent = buildClassNamesByStudent(enrollments, classMap);
        Map<Long, User> userMap = userMapper.selectBatchIds(studentIds).stream()
            .filter(Objects::nonNull)
            .collect(Collectors.toMap(User::getId, item -> item, (a, b) -> a));
        Map<Long, StudentProfile> profileMap = studentProfileMapper.selectList(
            new LambdaQueryWrapper<StudentProfile>().in(StudentProfile::getUserId, studentIds)
        ).stream().collect(Collectors.toMap(StudentProfile::getUserId, item -> item, (a, b) -> a));
        Map<Long, Submission> submissionMap = submissionMapper.selectList(
            new LambdaQueryWrapper<Submission>()
                .eq(Submission::getExamId, examId)
                .in(Submission::getStudentId, studentIds)
                .orderByDesc(Submission::getSubmittedAt, Submission::getUpdateTime, Submission::getId)
        ).stream().collect(Collectors.toMap(
            Submission::getStudentId,
            item -> item,
            this::pickLatestSubmission,
            LinkedHashMap::new
        ));

        List<StudentScoreItem> items = studentIds.stream()
            .map(studentId -> buildStudentScoreItem(studentId, userMap.get(studentId), profileMap.get(studentId),
                classNamesByStudent.getOrDefault(studentId, List.of()), submissionMap.get(studentId)))
            .collect(Collectors.toCollection(ArrayList::new));
        items.sort(Comparator
            .comparing((StudentScoreItem item) -> firstClassName(item.getClassNames()), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(item -> safeText(item.getStudentNo()), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(item -> safeText(item.getStudentName()), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(StudentScoreItem::getStudentId));
        return items;
    }

    private int sanitizeTopN(Integer topN) {
        if (topN == null) {
            return DEFAULT_TOP_N;
        }
        return Math.max(MIN_TOP_N, Math.min(MAX_TOP_N, topN));
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private Long findExamSubjectId(Long examId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null || exam.getPaperId() == null) {
            return null;
        }
        Paper paper = paperMapper.selectById(exam.getPaperId());
        return paper == null ? null : paper.getSubjectId();
    }

    private Map<Long, List<String>> buildClassNamesByStudent(List<StudentTeachingClass> enrollments,
                                                             Map<Long, TeachingClass> classMap) {
        Map<Long, List<String>> result = new LinkedHashMap<>();
        for (StudentTeachingClass enrollment : enrollments) {
            Long studentId = enrollment.getStudentId();
            if (studentId == null) {
                continue;
            }
            TeachingClass teachingClass = classMap.get(enrollment.getTeachingClassId());
            String className = teachingClass == null ? null : teachingClass.getName();
            result.computeIfAbsent(studentId, key -> new ArrayList<>());
            if (className != null && !className.isBlank() && !result.get(studentId).contains(className)) {
                result.get(studentId).add(className);
            }
        }
        return result;
    }

    private StudentScoreItem buildStudentScoreItem(Long studentId,
                                                   User user,
                                                   StudentProfile profile,
                                                   List<String> classNames,
                                                   Submission submission) {
        boolean submitted = submission != null && !SubmissionStatus.IN_PROGRESS.name().equals(submission.getStatus());
        return StudentScoreItem.builder()
            .studentId(studentId)
            .studentNo(profile == null ? null : profile.getStudentNo())
            .username(user == null ? null : user.getUsername())
            .studentName(user == null ? "学生" + studentId : coalesce(user.getRealName(), user.getUsername(), "学生" + studentId))
            .classNames(classNames == null ? List.of() : List.copyOf(classNames))
            .submissionId(submission == null ? null : submission.getId())
            .submissionStatus(submission == null ? null : submission.getStatus())
            .objectiveScore(submission == null ? null : submission.getObjectiveScore())
            .subjectiveScore(submission == null ? null : submission.getSubjectiveScore())
            .totalScore(submission == null ? null : submission.getTotalScore())
            .passFlag(submission == null ? null : submission.getPassFlag())
            .submittedAt(submission == null ? null : submission.getSubmittedAt())
            .submitted(submitted)
            .build();
    }

    private Submission pickLatestSubmission(Submission left, Submission right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        if (left.getSubmittedAt() == null && right.getSubmittedAt() == null) {
            return Objects.compare(left.getId(), right.getId(), Long::compareTo) >= 0 ? left : right;
        }
        if (left.getSubmittedAt() == null) {
            return right;
        }
        if (right.getSubmittedAt() == null) {
            return left;
        }
        return left.getSubmittedAt().isAfter(right.getSubmittedAt()) ? left : right;
    }

    private String firstClassName(List<String> classNames) {
        return classNames == null || classNames.isEmpty() ? "" : safeText(classNames.get(0));
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private String coalesce(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private Exam ensureExamManagePermission(Long examId) {
        Exam exam = examMapper.selectById(examId);
        examPermissionService.ensureCanManageExam(exam, "无权限查看该考试分析数据");
        return exam;
    }
}
