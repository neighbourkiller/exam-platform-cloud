CREATE TABLE IF NOT EXISTS question (id BIGINT PRIMARY KEY, subject_id BIGINT NOT NULL, type VARCHAR(16) NOT NULL,
 difficulty VARCHAR(16) NOT NULL, content TEXT NOT NULL, options_json TEXT, answer TEXT NOT NULL, analysis TEXT,
 default_score INT NOT NULL DEFAULT 0, creator_id BIGINT, create_time DATETIME(3), update_time DATETIME(3), create_by BIGINT, update_by BIGINT);
CREATE TABLE IF NOT EXISTS question_asset (id BIGINT PRIMARY KEY, question_id BIGINT, uploader_id BIGINT NOT NULL,
 file_type VARCHAR(16) NOT NULL, url VARCHAR(512) NOT NULL, object_key VARCHAR(255) NOT NULL,
 original_name VARCHAR(255), content_type VARCHAR(128), size BIGINT, create_time DATETIME(3), update_time DATETIME(3), create_by BIGINT, update_by BIGINT);
CREATE TABLE IF NOT EXISTS paper (id BIGINT PRIMARY KEY, name VARCHAR(128) NOT NULL, subject_id BIGINT NOT NULL,
 description VARCHAR(255), total_score INT NOT NULL DEFAULT 0, teacher_id BIGINT, version BIGINT NOT NULL DEFAULT 0,
 create_time DATETIME(3), update_time DATETIME(3), create_by BIGINT, update_by BIGINT);
CREATE TABLE IF NOT EXISTS paper_question (id BIGINT PRIMARY KEY, paper_id BIGINT NOT NULL, question_id BIGINT NOT NULL,
 score INT NOT NULL, sort_order INT NOT NULL, create_time DATETIME(3), update_time DATETIME(3), create_by BIGINT, update_by BIGINT,
 UNIQUE KEY uk_paper_question(paper_id, question_id));
CREATE TABLE IF NOT EXISTS paper_snapshot (id BIGINT PRIMARY KEY, paper_id BIGINT NOT NULL, version BIGINT NOT NULL,
 name VARCHAR(128) NOT NULL, subject_id BIGINT NOT NULL, total_score INT NOT NULL, created_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_paper_snapshot_version(paper_id, version));
CREATE TABLE IF NOT EXISTS paper_snapshot_question (id BIGINT PRIMARY KEY, snapshot_id BIGINT NOT NULL, question_id BIGINT NOT NULL,
 type VARCHAR(16) NOT NULL, difficulty VARCHAR(16) NOT NULL, content TEXT NOT NULL, options_json TEXT, answer TEXT NOT NULL,
 analysis TEXT, score INT NOT NULL, sort_order INT NOT NULL, KEY idx_snapshot_question(snapshot_id, sort_order));
