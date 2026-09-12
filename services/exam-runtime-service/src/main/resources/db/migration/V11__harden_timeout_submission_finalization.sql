CREATE TABLE IF NOT EXISTS submission_draft_payload (
    submission_id BIGINT PRIMARY KEY COMMENT '考试提交 ID',
    client_id VARCHAR(64) NOT NULL COMMENT '最后成功写入的客户端标识',
    client_sequence BIGINT NOT NULL COMMENT '客户端单调序列，用于幂等去重',
    server_revision BIGINT NOT NULL COMMENT 'Runtime 分配的单调草稿版本',
    codec VARCHAR(32) NOT NULL COMMENT '草稿载荷编码格式',
    payload MEDIUMBLOB NOT NULL COMMENT '压缩后的完整答案草稿',
    payload_sha256 CHAR(64) NOT NULL COMMENT '未压缩规范载荷 SHA-256',
    accepted_at DATETIME(3) NOT NULL COMMENT '数据库接受该草稿的时间',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_draft_server_revision(submission_id, server_revision)
);

ALTER TABLE submission_timeout_task
    ADD COLUMN failure_code VARCHAR(64) NULL COMMENT '稳定失败码',
    ADD COLUMN incident_id CHAR(36) NULL COMMENT '学生和运维可关联的故障事件号',
    ADD COLUMN failed_at DATETIME(3) NULL COMMENT '进入永久失败状态的时间',
    ADD COLUMN replay_count INT NOT NULL DEFAULT 0 COMMENT '受审计重放次数';
