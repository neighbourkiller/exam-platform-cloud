CREATE TABLE IF NOT EXISTS paper_snapshot_asset (
    id BIGINT PRIMARY KEY,
    snapshot_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    source_asset_id BIGINT NOT NULL,
    file_type VARCHAR(16) NOT NULL,
    url VARCHAR(512) NOT NULL,
    object_key VARCHAR(255) NOT NULL,
    original_name VARCHAR(255),
    content_type VARCHAR(128),
    size BIGINT,
    UNIQUE KEY uk_snapshot_source_asset(snapshot_id, source_asset_id),
    KEY idx_snapshot_asset_question(snapshot_id, question_id)
);
