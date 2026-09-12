ALTER TABLE submission_timeout_task
    ADD COLUMN available_at DATETIME(3)
        GENERATED ALWAYS AS (
            CASE
                WHEN due_at IS NULL THEN NULL
                WHEN next_retry_at IS NULL OR next_retry_at < due_at THEN due_at
                ELSE next_retry_at
            END
        ) STORED COMMENT '任务实际可领取时间，由到期时间和重试时间计算',
    ADD KEY idx_timeout_task_claim(status, available_at, id);
