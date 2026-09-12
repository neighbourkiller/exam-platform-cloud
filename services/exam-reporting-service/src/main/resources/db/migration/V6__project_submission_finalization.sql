ALTER TABLE rpt_student_score
    ADD COLUMN timeout_submit TINYINT NULL COMMENT '是否由服务端超时交卷',
    ADD COLUMN submission_source VARCHAR(16) NULL COMMENT 'MANUAL/TIMEOUT',
    ADD COLUMN runtime_finalized_at DATETIME(3) NULL COMMENT 'Runtime 本地事务完成时间',
    ADD COLUMN final_snapshot_version BIGINT NULL COMMENT '最终答案采用的服务端草稿版本',
    ADD COLUMN payload_sha256 CHAR(64) NULL COMMENT '最终答案规范载荷哈希';

ALTER TABLE rpt_proctoring_student
    ADD COLUMN timeout_submit TINYINT NULL COMMENT '是否由服务端超时交卷',
    ADD COLUMN submission_source VARCHAR(16) NULL COMMENT 'MANUAL/TIMEOUT',
    ADD COLUMN runtime_finalized_at DATETIME(3) NULL COMMENT 'Runtime 本地事务完成时间';
