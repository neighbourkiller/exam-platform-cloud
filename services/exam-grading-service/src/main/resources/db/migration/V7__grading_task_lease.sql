ALTER TABLE grading_task
    ADD COLUMN lease_token VARCHAR(36) NULL COMMENT '当前阅卷租约令牌',
    ADD COLUMN lease_expires_at DATETIME(3) NULL COMMENT '阅卷租约到期时间';
