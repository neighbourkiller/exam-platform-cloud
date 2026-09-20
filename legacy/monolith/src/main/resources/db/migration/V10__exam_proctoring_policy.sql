ALTER TABLE exam
    ADD COLUMN proctoring_level VARCHAR(32) NOT NULL DEFAULT 'STANDARD' AFTER publisher_id,
    ADD COLUMN proctoring_config_json TEXT NULL AFTER proctoring_level;
