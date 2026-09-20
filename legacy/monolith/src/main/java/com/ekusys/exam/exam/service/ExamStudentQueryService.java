package com.ekusys.exam.exam.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ekusys.exam.common.enums.SubmissionStatus;
import com.ekusys.exam.exam.dto.StudentExamResultView;
import com.ekusys.exam.exam.dto.StudentExamView;
import com.ekusys.exam.repository.entity.Exam;
import com.ekusys.exam.repository.entity.ExamTargetClass;
import com.ekusys.exam.repository.entity.Paper;
import com.ekusys.exam.repository.entity.Subject;
import com.ekusys.exam.repository.entity.Submission;
import com.ekusys.exam.repository.mapper.ExamMapper;
import com.ekusys.exam.repository.mapper.ExamTargetClassMapper;
import com.ekusys.exam.repository.mapper.PaperMapper;
import com.ekusys.exam.repository.mapper.SubjectMapper;
import com.ekusys.exam.repository.mapper.SubmissionMapper;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class ExamStudentQueryService {

    private final ExamMapper examMapper;
    private final ExamTargetClassMapper examTargetClassMapper;
    private final SubmissionMapper submissionMapper;
    private final PaperMapper paperMapper;
    private final SubjectMapper subjectMapper;
    private final ExamAccessService examAccessService;
    private final ExamStatusService examStatusService;
    private final ExamProctoringPolicyService proctoringPolicyService;

    public ExamStudentQueryService(ExamMapper examMapper,
                                   ExamTargetClassMapper examTargetClassMapper,
                                   SubmissionMapper submissionMapper,
                                   PaperMapper paperMapper,
                                   SubjectMapper subjectMapper,
                                   ExamAccessService examAccessService,
                                   ExamStatusService examStatusService,
                                   ExamProctoringPolicyService proctoringPolicyService) {
        this.examMapper = examMapper;
        this.examTargetClassMapper = examTargetClassMapper;
        this.submissionMapper = submissionMapper;
        this.paperMapper = paperMapper;
        this.subjectMapper = subjectMapper;
        this.examAccessService = examAccessService;
        this.examStatusService = examStatusService;
        this.proctoringPolicyService = proctoringPolicyService;
    }

    public List<StudentExamView> listStudentExams() {
        Long studentId = examAccessService.getCurrentUserId();
        List<Long> classIds = examAccessService.listActiveTeachingClassIdsByStudent(studentId);
        if (classIds.isEmpty()) {
            return List.of();
        }

        Set<Long> examIds = examTargetClassMapper.selectList(
            new LambdaQueryWrapper<ExamTargetClass>().in(ExamTargetClass::getClassId, classIds)
        ).stream().map(ExamTargetClass::getExamId).collect(Collectors.toSet());
        if (examIds.isEmpty()) {
            return List.of();
        }

        Set<Long> submittedIds = submissionMapper.selectList(
            new LambdaQueryWrapper<Submission>()
                .eq(Submission::getStudentId, studentId)
                .in(Submission::getExamId, examIds)
        ).stream()
            .filter(item -> SubmissionStatus.PROCESSING.name().equals(item.getStatus())
                || SubmissionStatus.SUBMITTED.name().equals(item.getStatus())
                || SubmissionStatus.GRADED.name().equals(item.getStatus()))
            .map(Submission::getExamId)
            .collect(Collectors.toSet());

        List<Exam> exams = examMapper.selectBatchIds(examIds);
        LocalDateTime now = LocalDateTime.now();
        exams.forEach(item -> examStatusService.refreshExamStatusByTime(item, now));
        Map<Long, String> examSubjectNameMap = buildExamSubjectNameMap(exams);

        return exams.stream()
            .sorted((a, b) -> b.getStartTime().compareTo(a.getStartTime()))
            .map(exam -> {
                var proctoringPolicy = proctoringPolicyService.resolve(exam.getProctoringLevel(), exam.getProctoringConfigJson());
                return StudentExamView.builder()
                    .examId(exam.getId())
                    .name(exam.getName())
                    .subjectName(examSubjectNameMap.get(exam.getId()))
                    .startTime(exam.getStartTime())
                    .endTime(exam.getEndTime())
                    .durationMinutes(exam.getDurationMinutes())
                    .status(exam.getStatus())
                    .submitted(submittedIds.contains(exam.getId()))
                    .proctoringLevel(proctoringPolicy.getLevel())
                    .proctoringPolicy(proctoringPolicy)
                    .build();
            })
            .toList();
    }

    public List<StudentExamResultView> listStudentExamResults() {
        Long studentId = examAccessService.getCurrentUserId();
        List<Long> classIds = examAccessService.listActiveTeachingClassIdsByStudent(studentId);
        if (classIds.isEmpty()) {
            return List.of();
        }

        Set<Long> examIds = examTargetClassMapper.selectList(
            new LambdaQueryWrapper<ExamTargetClass>().in(ExamTargetClass::getClassId, classIds)
        ).stream().map(ExamTargetClass::getExamId).collect(Collectors.toSet());
        if (examIds.isEmpty()) {
            return List.of();
        }

        List<Exam> exams = examMapper.selectBatchIds(examIds);
        LocalDateTime now = LocalDateTime.now();
        exams.forEach(item -> examStatusService.refreshExamStatusByTime(item, now));
        Map<Long, String> examSubjectNameMap = buildExamSubjectNameMap(exams);

        Map<Long, Submission> latestSubmissionByExamId = submissionMapper.selectList(
            new LambdaQueryWrapper<Submission>()
                .eq(Submission::getStudentId, studentId)
                .in(Submission::getExamId, examIds)
                .orderByDesc(Submission::getSubmittedAt, Submission::getUpdateTime, Submission::getId)
        ).stream().collect(Collectors.toMap(
            Submission::getExamId,
            item -> item,
            this::pickLatestSubmission
        ));

        return exams.stream()
            .sorted((a, b) -> b.getStartTime().compareTo(a.getStartTime()))
            .map(exam -> {
                Submission submission = latestSubmissionByExamId.get(exam.getId());
                boolean submitted = submission != null && !SubmissionStatus.IN_PROGRESS.name().equals(submission.getStatus());
                return StudentExamResultView.builder()
                    .examId(exam.getId())
                    .name(exam.getName())
                    .subjectName(examSubjectNameMap.get(exam.getId()))
                    .startTime(exam.getStartTime())
                    .endTime(exam.getEndTime())
                    .durationMinutes(exam.getDurationMinutes())
                    .examStatus(exam.getStatus())
                    .submissionId(submission == null ? null : submission.getId())
                    .submissionStatus(submission == null ? null : submission.getStatus())
                    .objectiveScore(submission == null ? null : submission.getObjectiveScore())
                    .subjectiveScore(submission == null ? null : submission.getSubjectiveScore())
                    .totalScore(submission == null ? null : submission.getTotalScore())
                    .passFlag(submission == null ? null : submission.getPassFlag())
                    .submittedAt(submission == null ? null : submission.getSubmittedAt())
                    .submitted(submitted)
                    .build();
            }).toList();
    }

    private Map<Long, String> buildExamSubjectNameMap(List<Exam> exams) {
        if (exams == null || exams.isEmpty()) {
            return Map.of();
        }
        Set<Long> paperIds = exams.stream()
            .map(Exam::getPaperId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (paperIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, Paper> paperMap = paperMapper.selectBatchIds(paperIds).stream()
            .collect(Collectors.toMap(Paper::getId, item -> item, (a, b) -> a));
        Set<Long> subjectIds = paperMap.values().stream()
            .map(Paper::getSubjectId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, Subject> subjectMap = subjectIds.isEmpty()
            ? Map.of()
            : subjectMapper.selectBatchIds(subjectIds).stream()
                .collect(Collectors.toMap(Subject::getId, item -> item, (a, b) -> a));

        Map<Long, String> result = new HashMap<>();
        for (Exam exam : exams) {
            Paper paper = paperMap.get(exam.getPaperId());
            if (paper == null) {
                result.put(exam.getId(), null);
                continue;
            }
            Subject subject = subjectMap.get(paper.getSubjectId());
            result.put(exam.getId(), subject == null ? null : subject.getName());
        }
        return result;
    }

    private Submission pickLatestSubmission(Submission left, Submission right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        LocalDateTime leftTime = left.getSubmittedAt() != null ? left.getSubmittedAt() : left.getUpdateTime();
        LocalDateTime rightTime = right.getSubmittedAt() != null ? right.getSubmittedAt() : right.getUpdateTime();
        if (leftTime == null && rightTime == null) {
            return Objects.compare(left.getId(), right.getId(), Long::compareTo) >= 0 ? left : right;
        }
        if (leftTime == null) {
            return right;
        }
        if (rightTime == null) {
            return left;
        }
        return leftTime.isAfter(rightTime) ? left : right;
    }
}
