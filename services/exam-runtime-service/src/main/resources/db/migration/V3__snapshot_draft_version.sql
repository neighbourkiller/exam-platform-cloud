ALTER TABLE submission
    ADD COLUMN draft_version BIGINT NOT NULL DEFAULT 0 AFTER timeout_submit;
