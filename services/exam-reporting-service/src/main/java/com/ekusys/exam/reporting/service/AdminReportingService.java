package com.ekusys.exam.reporting.service;

import com.ekusys.exam.admin.dto.AdminExamMonitorExamItemView;
import com.ekusys.exam.admin.dto.AdminExamMonitorStudentStatusView;
import com.ekusys.exam.admin.dto.AdminExamMonitorSummaryView;
import com.ekusys.exam.admin.dto.OperationAuditLogView;
import com.ekusys.exam.common.api.PageResponse;
import com.ekusys.exam.common.exception.BusinessException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AdminReportingService {
    private final JdbcTemplate jdbc;

    public AdminReportingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AdminExamMonitorSummaryView summary(LocalDateTime startTime, LocalDateTime endTime) {
        StringBuilder sql = new StringBuilder("select exam_id,name,status,start_time,end_time from rpt_exam where 1=1");
        List<Object> args = new ArrayList<>();
        if (startTime != null) { sql.append(" and start_time>=?"); args.add(startTime); }
        if (endTime != null) { sql.append(" and start_time<=?"); args.add(endTime); }
        sql.append(" order by start_time desc");
        List<AdminExamMonitorExamItemView> exams = jdbc.query(sql.toString(), (rs, rowNum) -> examItem(
            rs.getLong("exam_id"), rs.getString("name"), rs.getString("status"),
            rs.getObject("start_time", LocalDateTime.class), rs.getObject("end_time", LocalDateTime.class)), args.toArray());
        return new AdminExamMonitorSummaryView(exams.size(), sum(exams, "notStarted"), sum(exams, "answering"),
            sum(exams, "submitted"), sum(exams, "abnormal"), sum(exams, "absent"), exams);
    }

    public List<AdminExamMonitorStudentStatusView> students(Long examId) {
        LocalDateTime endTime = jdbc.query("select end_time from rpt_exam where exam_id=?",
            (rs, rowNum) -> rs.getObject("end_time", LocalDateTime.class), examId).stream().findFirst()
            .orElseThrow(() -> new BusinessException("考试不存在或报表尚未生成"));
        return jdbc.query(
            """
                select c.student_id,c.username,c.student_name,c.class_name,p.session_status,p.event_count,p.last_event_time,p.latest_event_type,
                       s.submission_id,s.submitted_at
                  from rpt_exam_candidate c
                  left join rpt_proctoring_student p on p.exam_id=c.exam_id and p.student_id=c.student_id
                  left join rpt_student_score s on s.exam_id=c.exam_id and s.student_id=c.student_id
                 where c.exam_id=? order by c.class_name,c.student_id
                """,
            (rs, rowNum) -> {
                boolean submitted = rs.getObject("submission_id") != null;
                String status = submitted ? "已提交" : "ANSWERING".equals(rs.getString("session_status"))
                    ? "考试中" : !LocalDateTime.now().isBefore(endTime) ? "缺考" : "未开始";
                return new AdminExamMonitorStudentStatusView(rs.getLong("student_id"), rs.getString("username"),
                    rs.getString("student_name"), rs.getString("class_name"), status, rs.getInt("event_count") > 0,
                    rs.getObject("submitted_at", LocalDateTime.class), rs.getObject("last_event_time", LocalDateTime.class),
                    rs.getString("latest_event_type"));
            }, examId);
    }

    public PageResponse<OperationAuditLogView> audit(long pageNum, long pageSize, String operatorKeyword,
                                                     String action, String targetType, String targetId, String status,
                                                     LocalDateTime startTime, LocalDateTime endTime) {
        pageNum = Math.max(1, pageNum); pageSize = Math.max(1, Math.min(100, pageSize));
        StringBuilder where = new StringBuilder(" where 1=1");
        List<Object> args = new ArrayList<>();
        addLike(where, args, "operator_username", operatorKeyword);
        addEqual(where, args, "action", action); addEqual(where, args, "target_type", targetType);
        addEqual(where, args, "target_id", targetId); addEqual(where, args, "status", status);
        if (startTime != null) { where.append(" and operate_time>=?"); args.add(startTime); }
        if (endTime != null) { where.append(" and operate_time<=?"); args.add(endTime); }
        Long total = jdbc.queryForObject("select count(*) from operation_audit_log" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(pageSize); pageArgs.add((pageNum - 1) * pageSize);
        List<OperationAuditLogView> records = jdbc.query(
            "select * from operation_audit_log" + where + " order by operate_time desc,id desc limit ? offset ?",
            (rs, rowNum) -> new OperationAuditLogView(rs.getLong("id"), nullableLong(rs, "operator_id"),
                rs.getString("operator_username"), rs.getString("operator_roles"), rs.getString("action"),
                rs.getString("target_type"), rs.getString("target_id"), rs.getString("request_method"),
                rs.getString("request_path"), rs.getString("request_ip"), rs.getString("detail"),
                rs.getString("status"), rs.getString("error_message"), rs.getObject("operate_time", LocalDateTime.class)),
            pageArgs.toArray());
        return PageResponse.<OperationAuditLogView>builder().pageNum(pageNum).pageSize(pageSize)
            .total(total == null ? 0 : total).records(records).build();
    }

    private AdminExamMonitorExamItemView examItem(Long examId, String name, String status,
                                                   LocalDateTime startTime, LocalDateTime endTime) {
        List<AdminExamMonitorStudentStatusView> students = students(examId);
        int notStarted = 0, answering = 0, submitted = 0, absent = 0, abnormal = 0;
        for (AdminExamMonitorStudentStatusView student : students) {
            switch (student.status()) { case "未开始" -> notStarted++; case "考试中" -> answering++;
                case "已提交" -> submitted++; case "缺考" -> absent++; default -> { } }
            if (Boolean.TRUE.equals(student.abnormal())) abnormal++;
        }
        return new AdminExamMonitorExamItemView(examId, name, status, startTime, endTime, students.size(),
            notStarted, answering, submitted, abnormal, absent);
    }

    private int sum(List<AdminExamMonitorExamItemView> items, String type) {
        return items.stream().mapToInt(item -> switch (type) {
            case "notStarted" -> item.notStartedCount(); case "answering" -> item.answeringCount();
            case "submitted" -> item.submittedCount(); case "abnormal" -> item.abnormalCount();
            default -> item.absentCount(); }).sum();
    }

    private void addLike(StringBuilder where, List<Object> args, String column, String value) {
        if (value != null && !value.isBlank()) { where.append(" and ").append(column).append(" like ?"); args.add("%" + value.trim() + "%"); }
    }

    private void addEqual(StringBuilder where, List<Object> args, String column, String value) {
        if (value != null && !value.isBlank()) { where.append(" and ").append(column).append("=?"); args.add(value.trim()); }
    }

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return rs.getObject(column) == null ? null : rs.getLong(column);
    }
}
