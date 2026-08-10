CREATE TABLE IF NOT EXISTS runtime_exam_definition (
    exam_id BIGINT PRIMARY KEY COMMENT '考试 ID',
    name VARCHAR(255) NULL COMMENT '考试名称',
    start_time DATETIME(3) NULL COMMENT '考试开始时间',
    end_time DATETIME(3) NULL COMMENT '考试结束时间',
    duration_minutes INT NULL COMMENT '作答时长（分钟）',
    pass_score INT NULL COMMENT '及格分数',
    paper_snapshot_id BIGINT NULL COMMENT '试卷快照 ID',
    paper_snapshot_version BIGINT NULL COMMENT '试卷快照版本',
    publisher_id BIGINT NULL COMMENT '发布人 ID',
    proctoring_level VARCHAR(32) NULL COMMENT '监考等级',
    proctoring_config_json LONGTEXT NULL COMMENT '监考策略 JSON',
    exam_status VARCHAR(32) NOT NULL COMMENT 'PUBLISHED/TERMINATED',
    provisioning_status VARCHAR(32) NOT NULL COMMENT 'PROVISIONING/READY/FAILED/TERMINATED',
    source_event_id VARCHAR(36) NULL COMMENT '最近一次来源事件 ID',
    source_event_version INT NOT NULL DEFAULT 1 COMMENT '来源事件版本',
    candidate_count INT NOT NULL DEFAULT 0 COMMENT '冻结考生人数',
    prepared_count INT NOT NULL DEFAULT 0 COMMENT '已创建会话人数',
    last_error VARCHAR(1000) NULL COMMENT '最近一次预创建失败原因',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_runtime_exam_provisioning(provisioning_status, start_time, exam_id)
);

CREATE TABLE IF NOT EXISTS inbox_event (
    event_id VARCHAR(36) NOT NULL COMMENT '消息事件 ID',
    consumer_name VARCHAR(64) NOT NULL COMMENT '消费者名称',
    event_type VARCHAR(128) NOT NULL COMMENT '事件类型',
    processed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (event_id, consumer_name),
    KEY idx_runtime_inbox_processed(processed_at)
);

ALTER TABLE exam_session
    ADD COLUMN first_paper_delivered_at DATETIME(3) NULL COMMENT '首次试卷交付时间' AFTER deadline_time,
    ADD KEY idx_session_exam_status_student(exam_id, status, student_id);

UPDATE exam_session
   SET first_paper_delivered_at=COALESCE(start_time, create_time, CURRENT_TIMESTAMP(3))
 WHERE status<>'PREPARED';

ALTER TABLE submission_timeout_task
    MODIFY due_at DATETIME(3) NULL COMMENT '任务到期时间，预创建阶段为空',
    MODIFY status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        COMMENT 'WAITING/PENDING/PROCESSING/DONE/FAILED/CANCELLED';
