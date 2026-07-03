CREATE TABLE IF NOT EXISTS sys_user (id BIGINT PRIMARY KEY, username VARCHAR(64) NOT NULL UNIQUE,
 password VARCHAR(255) NOT NULL, real_name VARCHAR(64) NOT NULL, enabled TINYINT NOT NULL DEFAULT 1,
 token_version BIGINT NOT NULL DEFAULT 0, create_time DATETIME(3), update_time DATETIME(3), create_by BIGINT, update_by BIGINT);
CREATE TABLE IF NOT EXISTS sys_role (id BIGINT PRIMARY KEY, code VARCHAR(32) NOT NULL UNIQUE, name VARCHAR(64) NOT NULL,
 create_time DATETIME(3), update_time DATETIME(3), create_by BIGINT, update_by BIGINT);
CREATE TABLE IF NOT EXISTS sys_user_role (id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL, role_id BIGINT NOT NULL,
 create_time DATETIME(3), update_time DATETIME(3), create_by BIGINT, update_by BIGINT, UNIQUE KEY uk_user_role(user_id, role_id));
CREATE TABLE IF NOT EXISTS outbox_event (id VARCHAR(36) PRIMARY KEY, aggregate_type VARCHAR(64) NOT NULL,
 aggregate_id VARCHAR(64) NOT NULL, event_type VARCHAR(128) NOT NULL, payload_json JSON NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING', retry_count INT NOT NULL DEFAULT 0, next_retry_time DATETIME(3),
 created_at DATETIME(3) NOT NULL, published_at DATETIME(3), KEY idx_outbox_publish(status, next_retry_time));
