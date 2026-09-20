package com.ekusys.exam.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ekusys.exam.admin.dto.AdminExamMonitorExamItemView;
import com.ekusys.exam.admin.dto.AdminExamMonitorStudentStatusView;
import com.ekusys.exam.admin.dto.AdminExamMonitorSummaryView;
import com.ekusys.exam.common.enums.SessionStatus;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.exam.service.ExamStatusService;
import com.ekusys.exam.repository.entity.AntiCheatEvent;
import com.ekusys.exam.repository.entity.Exam;
import com.ekusys.exam.repository.entity.ExamSession;
import com.ekusys.exam.repository.entity.ExamTargetClass;
import com.ekusys.exam.repository.entity.StudentTeachingClass;
import com.ekusys.exam.repository.entity.Submission;
import com.ekusys.exam.repository.entity.TeachingClass;
import com.ekusys.exam.repository.entity.User;
import com.ekusys.exam.repository.mapper.AntiCheatEventMapper;
import com.ekusys.exam.repository.mapper.ExamMapper;
import com.ekusys.exam.repository.mapper.ExamSessionMapper;
import com.ekusys.exam.repository.mapper.ExamTargetClassMapper;
import com.ekusys.exam.repository.mapper.StudentTeachingClassMapper;
import com.ekusys.exam.repository.mapper.SubmissionMapper;
import com.ekusys.exam.repository.mapper.TeachingClassMapper;
import com.ekusys.exam.repository.mapper.UserMapper;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class AdminExamMonitorService {

    private static final String ENROLL_STATUS_ACTIVE = "ACTIVE";

    private final ExamMapper examMapper;
    private final ExamTargetClassMapper examTargetClassMapper;
    private final StudentTeachingClassMapper studentTeachingClassMapper;
    private final TeachingClassMapper teachingClassMapper;
    private final UserMapper userMapper;
    private final ExamSessionMapper examSessionMapper;
    private final SubmissionMapper submissionMapper;
    private final AntiCheatEventMapper antiCheatEventMapper;
    private final ExamStatusService examStatusService;

    public AdminExamMonitorService(ExamMapper examMapper,
                                   ExamTargetClassMapper examTargetClassMapper,
                                   StudentTeachingClassMapper studentTeachingClassMapper,
                                   TeachingClassMapper teachingClassMapper,
                                   UserMapper userMapper,
                                   ExamSessionMapper examSessionMapper,
                                   SubmissionMapper submissionMapper,
                                   AntiCheatEventMapper antiCheatEventMapper,
                                   ExamStatusService examStatusService) {
        this.examMapper = examMapper;
        this.examTargetClassMapper = examTargetClassMapper;
        this.studentTeachingClassMapper = studentTeachingClassMapper;
        this.teachingClassMapper = teachingClassMapper;
        this.userMapper = userMapper;
        this.examSessionMapper = examSessionMapper;
        this.submissionMapper = submissionMapper;
        this.antiCheatEventMapper = antiCheatEventMapper;
        this.examStatusService = examStatusService;
    }

    public AdminExamMonitorSummaryView summary(LocalDateTime startTime, LocalDateTime endTime) {
        LambdaQueryWrapper<Exam> wrapper = new LambdaQueryWrapper<Exam>().orderByDesc(Exam::getStartTime);
        if (startTime != null) {
            wrapper.ge(Exam::getStartTime, startTime);
        }
        if (endTime != null) {
            wrapper.le(Exam::getStartTime, endTime);
        }
        LocalDateTime now = LocalDateTime.now();
        List<AdminExamMonitorExamItemView> exams = examMapper.selectList(wrapper).stream()
            .peek(exam -> examStatusService.refreshExamStatusByTime(exam, now))
            .map(exam -> buildExamItem(exam, now))
            .toList();

        return AdminExamMonitorSummaryView.builder()
            .totalExams(exams.size())
            .notStartedCount(exams.stream().mapToInt(AdminExamMonitorExamItemView::getNotStartedCount).sum())
            .answeringCount(exams.stream().mapToInt(AdminExamMonitorExamItemView::getAnsweringCount).sum())
            .submittedCount(exams.stream().mapToInt(AdminExamMonitorExamItemView::getSubmittedCount).sum())
            .abnormalCount(exams.stream().mapToInt(AdminExamMonitorExamItemView::getAbnormalCount).sum())
            .absentCount(exams.stream().mapToInt(AdminExamMonitorExamItemView::getAbsentCount).sum())
            .exams(exams)
            .build();
    }

    public List<AdminExamMonitorStudentStatusView> students(Long examId) {
        Exam exam = examMapper.selectById(examId);
        if (exam == null) {
            throw new BusinessException("考试不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        examStatusService.refreshExamStatusByTime(exam, now);
        MonitorData data = loadMonitorData(exam);
        return data.students.stream()
            .map(student -> toStudentView(exam, student, data, now))
            .sorted(Comparator.comparing(AdminExamMonitorStudentStatusView::getStatus)
                .thenComparing(AdminExamMonitorStudentStatusView::getStudentId))
            .toList();
    }

    private AdminExamMonitorExamItemView buildExamItem(Exam exam, LocalDateTime now) {
        MonitorData data = loadMonitorData(exam);
        int notStarted = 0;
        int answering = 0;
        int submitted = 0;
        int absent = 0;
        for (User student : data.students) {
            String status = resolveStudentStatus(exam, student.getId(), data, now);
            switch (status) {
                case "未开始" -> notStarted++;
                case "考试中" -> answering++;
                case "已提交" -> submitted++;
                case "缺考" -> absent++;
                default -> {
                }
            }
        }
        return AdminExamMonitorExamItemView.builder()
            .examId(exam.getId())
            .name(exam.getName())
            .status(exam.getStatus())
            .startTime(exam.getStartTime())
            .endTime(exam.getEndTime())
            .totalStudents(data.students.size())
            .notStartedCount(notStarted)
            .answeringCount(answering)
            .submittedCount(submitted)
            .abnormalCount(data.eventsByStudent.size())
            .absentCount(absent)
            .build();
    }

    private AdminExamMonitorStudentStatusView toStudentView(Exam exam, User student, MonitorData data, LocalDateTime now) {
        AntiCheatEvent latestEvent = data.eventsByStudent.getOrDefault(student.getId(), List.of()).stream()
            .max(Comparator.comparing(AntiCheatEvent::getEventTime))
            .orElse(null);
        Submission submission = data.submissionByStudent.get(student.getId());
        return AdminExamMonitorStudentStatusView.builder()
            .studentId(student.getId())
            .username(student.getUsername())
            .realName(student.getRealName())
            .className(data.classNameByStudent.get(student.getId()))
            .status(resolveStudentStatus(exam, student.getId(), data, now))
            .abnormal(latestEvent != null)
            .submittedAt(submission == null ? null : submission.getSubmittedAt())
            .lastEventTime(latestEvent == null ? null : latestEvent.getEventTime())
            .latestEventType(latestEvent == null ? null : latestEvent.getEventType())
            .build();
    }

    private String resolveStudentStatus(Exam exam, Long studentId, MonitorData data, LocalDateTime now) {
        if (data.submissionByStudent.containsKey(studentId)) {
            return "已提交";
        }
        ExamSession session = data.sessionByStudent.get(studentId);
        if (session != null && SessionStatus.ANSWERING.name().equals(session.getStatus())) {
            return "考试中";
        }
        if (exam.getEndTime() != null && !now.isBefore(exam.getEndTime())) {
            return "缺考";
        }
        return "未开始";
    }

    private MonitorData loadMonitorData(Exam exam) {
        List<ExamTargetClass> targets = examTargetClassMapper.selectList(
            new LambdaQueryWrapper<ExamTargetClass>().eq(ExamTargetClass::getExamId, exam.getId())
        );
        Set<Long> classIds = targets.stream()
            .map(ExamTargetClass::getClassId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (classIds.isEmpty()) {
            return MonitorData.empty();
        }

        List<StudentTeachingClass> bindings = studentTeachingClassMapper.selectList(
            new LambdaQueryWrapper<StudentTeachingClass>()
                .in(StudentTeachingClass::getTeachingClassId, classIds)
                .eq(StudentTeachingClass::getEnrollStatus, ENROLL_STATUS_ACTIVE)
        );
        Set<Long> studentIds = bindings.stream()
            .map(StudentTeachingClass::getStudentId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (studentIds.isEmpty()) {
            return MonitorData.empty();
        }

        Map<Long, TeachingClass> classById = teachingClassMapper.selectBatchIds(classIds).stream()
            .collect(Collectors.toMap(TeachingClass::getId, Function.identity(), (a, b) -> a));
        Map<Long, String> classNameByStudent = new LinkedHashMap<>();
        for (StudentTeachingClass binding : bindings) {
            TeachingClass teachingClass = classById.get(binding.getTeachingClassId());
            if (teachingClass != null) {
                classNameByStudent.putIfAbsent(binding.getStudentId(), teachingClass.getName());
            }
        }

        List<User> students = userMapper.selectBatchIds(studentIds);
        Map<Long, ExamSession> sessionByStudent = examSessionMapper.selectList(
            new LambdaQueryWrapper<ExamSession>().eq(ExamSession::getExamId, exam.getId()).in(ExamSession::getStudentId, studentIds)
        ).stream().collect(Collectors.toMap(ExamSession::getStudentId, Function.identity(), (a, b) -> a));
        Map<Long, Submission> submissionByStudent = submissionMapper.selectList(
            new LambdaQueryWrapper<Submission>().eq(Submission::getExamId, exam.getId()).in(Submission::getStudentId, studentIds)
        ).stream().collect(Collectors.toMap(Submission::getStudentId, Function.identity(), (a, b) -> a));
        Map<Long, List<AntiCheatEvent>> eventsByStudent = antiCheatEventMapper.selectList(
            new LambdaQueryWrapper<AntiCheatEvent>().eq(AntiCheatEvent::getExamId, exam.getId()).in(AntiCheatEvent::getStudentId, studentIds)
        ).stream().collect(Collectors.groupingBy(AntiCheatEvent::getStudentId));
        return new MonitorData(students, sessionByStudent, submissionByStudent, eventsByStudent, classNameByStudent);
    }

    private record MonitorData(List<User> students,
                               Map<Long, ExamSession> sessionByStudent,
                               Map<Long, Submission> submissionByStudent,
                               Map<Long, List<AntiCheatEvent>> eventsByStudent,
                               Map<Long, String> classNameByStudent) {
        private static MonitorData empty() {
            return new MonitorData(List.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }
    }
}
