ALTER TABLE outbox_event
    ADD COLUMN lease_token VARCHAR(36) NULL AFTER next_retry_time,
    ADD COLUMN lease_until DATETIME(3) NULL AFTER lease_token,
    ADD COLUMN last_error VARCHAR(1000) NULL AFTER lease_until,
    ADD COLUMN failed_at DATETIME(3) NULL AFTER last_error,
    DROP INDEX idx_outbox_publish,
    ADD KEY idx_outbox_pending(status, next_retry_time, created_at, id),
    ADD KEY idx_outbox_lease(status, lease_until, id),
    ADD KEY idx_outbox_cleanup(status, published_at, id);

