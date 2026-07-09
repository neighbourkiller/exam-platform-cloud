ALTER TABLE exam_session
    ADD COLUMN active_client_id VARCHAR(128) NULL AFTER claim_time,
    ADD COLUMN active_client_token VARCHAR(128) NULL AFTER active_client_id,
    ADD COLUMN active_client_lease_until DATETIME(3) NULL AFTER active_client_token,
    ADD COLUMN active_client_last_seen DATETIME(3) NULL AFTER active_client_lease_until,
    ADD KEY idx_exam_session_active_client_lease(active_client_lease_until);
