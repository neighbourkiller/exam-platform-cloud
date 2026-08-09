CREATE TABLE IF NOT EXISTS submission_timeout_task (
    id BIGINT PRIMARY KEY COMMENT '超时交卷任务主键，与会话 ID 保持一致',
    session_id BIGINT NOT NULL COMMENT '考试会话 ID',
    exam_id BIGINT NOT NULL COMMENT '考试 ID',
    student_id BIGINT NOT NULL COMMENT '学生 ID',
    due_at DATETIME(3) NOT NULL COMMENT '任务到期时间',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/DONE/FAILED',
    claim_token VARCHAR(36) NULL COMMENT '当前处理租约令牌',
    lease_until DATETIME(3) NULL COMMENT '当前处理租约到期时间',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '累计处理次数',
    next_retry_at DATETIME(3) NULL COMMENT '下次允许重试时间',
    last_error VARCHAR(1000) NULL COMMENT '最近一次失败原因',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at DATETIME(3) NULL,
    UNIQUE KEY uk_timeout_task_session(session_id),
    KEY idx_timeout_task_due(status, due_at, next_retry_at, id),
    KEY idx_timeout_task_lease(status, lease_until, id),
    KEY idx_timeout_task_exam(exam_id, status, due_at)
);

CREATE TABLE IF NOT EXISTS submission_final_payload (
    submission_id BIGINT PRIMARY KEY COMMENT '考试提交 ID',
    source VARCHAR(16) NOT NULL COMMENT 'MANUAL/TIMEOUT',
    snapshot_version BIGINT NOT NULL DEFAULT 0 COMMENT '最终答案采用的快照版本',
    codec VARCHAR(32) NOT NULL COMMENT '载荷编码格式',
    payload MEDIUMBLOB NOT NULL COMMENT '压缩后的最终答案载荷',
    payload_sha256 CHAR(64) NOT NULL COMMENT '未压缩规范载荷 SHA-256',
    finalized_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

INSERT IGNORE INTO submission_timeout_task(
    id, session_id, exam_id, student_id, due_at, status, created_at, updated_at
)
SELECT id, id, exam_id, student_id, deadline_time, 'PENDING',
       CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
  FROM exam_session
 WHERE status IN ('ANSWERING', 'AUTO_SUBMITTING')
   AND deadline_time IS NOT NULL;
