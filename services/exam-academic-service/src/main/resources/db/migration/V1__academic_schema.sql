CREATE TABLE IF NOT EXISTS subject (id BIGINT PRIMARY KEY, name VARCHAR(64) NOT NULL, description VARCHAR(255),
 create_time DATETIME(3), update_time DATETIME(3), create_by BIGINT, update_by BIGINT);
CREATE TABLE IF NOT EXISTS student_profile (id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL UNIQUE, student_no VARCHAR(64),
 enrollment_year VARCHAR(16), status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', create_time DATETIME(3), update_time DATETIME(3), create_by BIGINT, update_by BIGINT,
 UNIQUE KEY uk_student_no(student_no));
CREATE TABLE IF NOT EXISTS teacher_profile (id BIGINT PRIMARY KEY, user_id BIGINT NOT NULL UNIQUE, teacher_no VARCHAR(64),
 title VARCHAR(32), status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', create_time DATETIME(3), update_time DATETIME(3), create_by BIGINT, update_by BIGINT);
CREATE TABLE IF NOT EXISTS teaching_class (id BIGINT PRIMARY KEY, name VARCHAR(128) NOT NULL, subject_id BIGINT NOT NULL,
 teacher_id BIGINT NOT NULL, term VARCHAR(32) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'ONGOING', capacity INT,
 roster_version BIGINT NOT NULL DEFAULT 0, create_time DATETIME(3), update_time DATETIME(3), create_by BIGINT, update_by BIGINT);
CREATE TABLE IF NOT EXISTS student_teaching_class (id BIGINT PRIMARY KEY, student_id BIGINT NOT NULL, subject_id BIGINT NOT NULL,
 teaching_class_id BIGINT NOT NULL, enroll_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', enrolled_at DATETIME(3), dropped_at DATETIME(3),
 create_time DATETIME(3), update_time DATETIME(3), create_by BIGINT, update_by BIGINT,
 UNIQUE KEY uk_student_class(student_id, teaching_class_id), KEY idx_class_status(teaching_class_id, enroll_status));
