package com.ekusys.exam.runtime.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.SecurityUtils;
import com.ekusys.exam.exam.dto.ProctoringDispositionRequest;
import com.ekusys.exam.exam.dto.ProctoringDispositionView;
import com.ekusys.exam.exam.dto.ProctoringEventStatView;
import com.ekusys.exam.exam.dto.ProctoringOverviewView;
import com.ekusys.exam.exam.dto.ProctoringRecentEventView;
import com.ekusys.exam.exam.dto.ProctoringStudentTimelineView;
import com.ekusys.exam.exam.dto.ProctoringStudentView;
import com.ekusys.exam.exam.dto.ProctoringTimelineEventView;
import com.ekusys.exam.iam.api.UserBatchRequest;
import com.ekusys.exam.iam.api.UserSummary;
import com.ekusys.exam.management.api.RuntimeExamProctoringContext;
import com.ekusys.exam.runtime.client.IamRuntimeClient;
import com.ekusys.exam.runtime.client.ManagementRuntimeClient;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProctoringService {
    private static final Set<String> DISPOSITIONS = Set.of("PENDING_REVIEW", "CONFIRMED", "FALSE_POSITIVE", "CLOSED");

    private final JdbcTemplate jdbc;
    private final ManagementRuntimeClient management;
    private final IamRuntimeClient iam;
    private final ExamClientLeaseService clientLeaseService;

    public ProctoringService(JdbcTemplate jdbc, ManagementRuntimeClient management, IamRuntimeClient iam,
                             ExamClientLeaseService clientLeaseService) {
        this.jdbc = jdbc;
        this.management = management;
        this.iam = iam;
        this.clientLeaseService = clientLeaseService;
    }

    public ProctoringOverviewView overview(Long examId) {
        Context context = context(examId);
        List<ProctoringStudentView> students = context.students();
        return new ProctoringOverviewView(
            examId, context.exam().metadata().name(), context.exam().status(), students.size(),
            count(students, ProctoringStudentView::answering), countRisk(students, "LOW"),
            countRisk(students, "MEDIUM"), countRisk(students, "HIGH"),
            count(students, ProctoringStudentView::snapshotAlert), countDisposition(students, "PENDING_REVIEW"),
            countDisposition(students, "CONFIRMED"), countDisposition(students, "FALSE_POSITIVE"),
            countDisposition(students, "CLOSED"), recentEvents(examId, context.users()), eventStats(examId, null));
    }

    public List<ProctoringStudentView> students(Long examId) {
        return context(examId).students().stream()
            .sorted(java.util.Comparator.comparing(ProctoringStudentView::riskScore).reversed()
                .thenComparing(ProctoringStudentView::studentId))
            .toList();
    }

    public ProctoringStudentTimelineView timeline(Long examId, Long studentId) {
        Context context = context(examId);
        ProctoringStudentView student = context.students().stream().filter(item -> item.studentId().equals(studentId))
            .findFirst().orElseThrow(() -> new BusinessException("学生不在该考试监考范围内"));
        List<ProctoringTimelineEventView> events = jdbc.query(
            "select event_type,event_time,duration_ms,payload,evidence_json from anti_cheat_event where exam_id=? and student_id=? order by event_time desc,id desc",
            (rs, rowNum) -> new ProctoringTimelineEventView(rs.getString("event_type"),
                rs.getObject("event_time", LocalDateTime.class), nullableLong(rs, "duration_ms"),
                rs.getString("payload"), rs.getString("evidence_json")), examId, studentId);
        return new ProctoringStudentTimelineView(
            examId, context.exam().metadata().name(), context.exam().status(), studentId,
            student.studentName(), student.username(), student.classNames(), student.riskScore(), student.riskLevel(),
            student.eventCount(), student.latestEventType(), student.lastEventTime(), student.lastSnapshotTime(),
            student.answering(), student.snapshotAlert(), student.totalOffscreenDurationMs(), student.longOffscreen(),
            student.disposition(), eventStats(examId, studentId), events);
    }

    @Transactional
    public ProctoringDispositionView updateDisposition(Long examId, Long studentId, ProctoringDispositionRequest request) {
        RuntimeExamProctoringContext exam = requireManage(examId);
        if (!exam.candidateIds().contains(studentId)) throw new BusinessException("学生不在该考试监考范围内");
        String status = request.status().trim().toUpperCase(Locale.ROOT);
        if (!DISPOSITIONS.contains(status)) throw new BusinessException("无效的处置状态");
        Long handlerId = SecurityUtils.getCurrentUserId();
        jdbc.update(
            """
                insert into proctoring_disposition(id,exam_id,student_id,status,remark,handled_by,handled_at,create_time,update_time)
                values(?,?,?,?,?,?,current_timestamp(3),current_timestamp(3),current_timestamp(3))
                on duplicate key update status=values(status),remark=values(remark),handled_by=values(handled_by),
                    handled_at=current_timestamp(3),update_time=current_timestamp(3)
                """,
            IdWorker.getId(), examId, studentId, status, blankToNull(request.remark()), handlerId
        );
        return disposition(examId, studentId);
    }

    private Context context(Long examId) {
        RuntimeExamProctoringContext exam = requireManage(examId);
        List<Long> candidateIds = exam.candidateIds();
        List<UserSummary> userValues = candidateIds.isEmpty() ? List.of() : iam.batch(new UserBatchRequest(candidateIds)).getData();
        Map<Long, UserSummary> users = userValues == null ? Map.of() : userValues.stream()
            .collect(Collectors.toMap(UserSummary::id, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        List<ProctoringStudentView> students = candidateIds.stream().map(id -> student(examId, id, users.get(id))).toList();
        return new Context(exam, users, students);
    }

    private ProctoringStudentView student(Long examId, Long studentId, UserSummary user) {
        EventSummary event = jdbc.queryForObject(
            """
                select count(*) event_count,max(event_time) last_event_time,
                       substring_index(group_concat(event_type order by event_time desc,id desc),',',1) latest_event_type,
                       coalesce(sum(case when event_type in ('WINDOW_BLUR','TAB_HIDDEN','FULLSCREEN_EXIT') then greatest(coalesce(duration_ms,0),0) else 0 end),0) offscreen_ms
                  from anti_cheat_event where exam_id=? and student_id=?
                """,
            (rs, rowNum) -> new EventSummary(rs.getInt("event_count"), rs.getString("latest_event_type"),
                rs.getObject("last_event_time", LocalDateTime.class), rs.getLong("offscreen_ms")), examId, studentId);
        SessionSummary session = jdbc.query(
            "select status,last_snapshot_time from exam_session where exam_id=? and student_id=? order by update_time desc,id desc limit 1",
            (rs, rowNum) -> new SessionSummary(rs.getString("status"), rs.getObject("last_snapshot_time", LocalDateTime.class)),
            examId, studentId).stream().findFirst().orElse(new SessionSummary(null, null));
        LocalDateTime liveSnapshotTime = clientLeaseService.lastSnapshot(examId, studentId);
        LocalDateTime lastSnapshotTime = latest(session.lastSnapshotTime(), liveSnapshotTime);
        int riskScore = Math.min(100, event.eventCount() * 10 + (event.offscreenMs() >= 30000 ? 20 : 0));
        String riskLevel = riskScore >= 60 ? "HIGH" : riskScore >= 20 ? "MEDIUM" : "LOW";
        boolean answering = "ANSWERING".equals(session.status());
        boolean snapshotAlert = answering && (lastSnapshotTime == null
            || lastSnapshotTime.isBefore(LocalDateTime.now().minusSeconds(30)));
        String name = user == null ? "学生" + studentId : firstNonBlank(user.realName(), user.username(), "学生" + studentId);
        return new ProctoringStudentView(studentId, name, user == null ? null : user.username(), List.of(),
            riskScore, riskLevel, event.eventCount(), event.latestType(), event.lastTime(), lastSnapshotTime,
            answering, snapshotAlert, event.offscreenMs(), event.offscreenMs() >= 30000, disposition(examId, studentId));
    }

    private ProctoringDispositionView disposition(Long examId, Long studentId) {
        return jdbc.query(
            "select status,remark,handled_by,handled_at from proctoring_disposition where exam_id=? and student_id=?",
            (rs, rowNum) -> new ProctoringDispositionView(rs.getString("status"), rs.getString("remark"),
                nullableLong(rs, "handled_by"), null, rs.getObject("handled_at", LocalDateTime.class)),
            examId, studentId).stream().findFirst().orElse(new ProctoringDispositionView("PENDING_REVIEW", null, null, null, null));
    }

    private LocalDateTime latest(LocalDateTime left, LocalDateTime right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isAfter(right) ? left : right;
    }

    private List<ProctoringRecentEventView> recentEvents(Long examId, Map<Long, UserSummary> users) {
        return jdbc.query(
            "select student_id,event_type,event_time,duration_ms from anti_cheat_event where exam_id=? order by event_time desc,id desc limit 20",
            (rs, rowNum) -> {
                long studentId = rs.getLong("student_id");
                UserSummary user = users.get(studentId);
                return new ProctoringRecentEventView(studentId,
                    user == null ? "学生" + studentId : firstNonBlank(user.realName(), user.username(), "学生" + studentId),
                    user == null ? null : user.username(), List.of(), rs.getString("event_type"),
                    rs.getObject("event_time", LocalDateTime.class), nullableLong(rs, "duration_ms"));
            }, examId);
    }

    private List<ProctoringEventStatView> eventStats(Long examId, Long studentId) {
        String sql = "select event_type,count(*) cnt,coalesce(sum(greatest(coalesce(duration_ms,0),0)),0) duration from anti_cheat_event where exam_id=?";
        List<Object> args = new ArrayList<>();
        args.add(examId);
        if (studentId != null) { sql += " and student_id=?"; args.add(studentId); }
        sql += " group by event_type order by cnt desc,event_type";
        return jdbc.query(sql, (rs, rowNum) -> new ProctoringEventStatView(rs.getString("event_type"),
            rs.getInt("cnt"), rs.getLong("duration")), args.toArray());
    }

    private RuntimeExamProctoringContext requireManage(Long examId) {
        RuntimeExamProctoringContext exam = management.proctoringContext(examId).getData();
        if (exam == null) throw new BusinessException("考试不存在");
        if (!SecurityUtils.getCurrentRoles().contains("ADMIN")
            && !java.util.Objects.equals(exam.metadata().publisherId(), SecurityUtils.getCurrentUserId())) {
            throw new BusinessException("无权限查看该考试监考信息");
        }
        return exam;
    }

    private int count(List<ProctoringStudentView> values, Function<ProctoringStudentView, Boolean> getter) {
        return (int) values.stream().filter(item -> Boolean.TRUE.equals(getter.apply(item))).count();
    }

    private int countRisk(List<ProctoringStudentView> values, String risk) {
        return (int) values.stream().filter(item -> risk.equals(item.riskLevel())).count();
    }

    private int countDisposition(List<ProctoringStudentView> values, String status) {
        return (int) values.stream().filter(item -> status.equals(item.disposition().status())).count();
    }

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return rs.getObject(column) == null ? null : rs.getLong(column);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private record EventSummary(int eventCount, String latestType, LocalDateTime lastTime, long offscreenMs) {
    }

    private record SessionSummary(String status, LocalDateTime lastSnapshotTime) {
    }

    private record Context(RuntimeExamProctoringContext exam, Map<Long, UserSummary> users,
                           List<ProctoringStudentView> students) {
    }
}
