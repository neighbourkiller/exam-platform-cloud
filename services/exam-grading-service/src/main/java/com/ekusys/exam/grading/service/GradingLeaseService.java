package com.ekusys.exam.grading.service;

import com.ekusys.exam.common.exception.BusinessException;
import com.ekusys.exam.common.security.SecurityUtils;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class GradingLeaseService {
    private final JdbcTemplate jdbc;

    public GradingLeaseService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Lease(String token, int expiresInSeconds) {}

    public Lease claim(Long answerId) {
        String token = UUID.randomUUID().toString();
        int changed = jdbc.update("""
            update grading_task set assigned_teacher_id=?, lease_token=?,
                lease_expires_at=timestampadd(minute,5,current_timestamp(3)), update_time=current_timestamp(3)
            where answer_id=? and status='PENDING'
                and (lease_expires_at is null or lease_expires_at<=current_timestamp(3))
            """, currentTeacher(), token, answerId);
        requireChanged(changed);
        return new Lease(token, 300);
    }

    public Lease renew(Long answerId, String token) {
        int changed = jdbc.update("""
            update grading_task set lease_expires_at=timestampadd(minute,5,current_timestamp(3)),
                update_time=current_timestamp(3)
            where answer_id=? and status='PENDING' and assigned_teacher_id=? and lease_token=?
                and lease_expires_at>current_timestamp(3)
            """, answerId, currentTeacher(), token);
        requireChanged(changed);
        return new Lease(token, 300);
    }

    public void release(Long answerId, String token) {
        jdbc.update("""
            update grading_task set assigned_teacher_id=null, lease_token=null, lease_expires_at=null,
                update_time=current_timestamp(3)
            where answer_id=? and status='PENDING' and assigned_teacher_id=? and lease_token=?
            """, answerId, currentTeacher(), token);
    }

    private Long currentTeacher() {
        Long teacher = SecurityUtils.getCurrentUserId();
        if (teacher == null) throw new BusinessException("无法识别当前阅卷老师");
        return teacher;
    }

    private void requireChanged(int changed) {
        if (changed != 1) throw new BusinessException("GRADING_LEASE_CONFLICT", "答案已被认领、已批阅或租约已过期，请刷新后重新认领");
    }
}
