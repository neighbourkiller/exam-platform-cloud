INSERT INTO subject (id, name, description, create_time, update_time, create_by, update_by)
VALUES
    (5001, 'Java程序设计', 'Java基础与面向对象', NOW(), NOW(), 0, 0),
    (5002, 'Spring Boot开发', 'Spring Boot Web与自动配置', NOW(), NOW(), 0, 0),
    (5003, '数据库原理与SQL', '关系数据库与SQL查询优化', NOW(), NOW(), 0, 0)
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    description = VALUES(description),
    update_time = NOW();

INSERT INTO student_profile (id, user_id, student_no, enrollment_year, status, create_time, update_time, create_by, update_by)
VALUES
    (3101, 1003, 'S20260001', '2026', 'ACTIVE', NOW(), NOW(), 0, 0)
ON DUPLICATE KEY UPDATE
    student_no = VALUES(student_no),
    enrollment_year = VALUES(enrollment_year),
    status = VALUES(status),
    update_time = NOW();

INSERT INTO teacher_profile (id, user_id, teacher_no, title, status, create_time, update_time, create_by, update_by)
VALUES
    (3201, 1002, 'T20260001', '讲师', 'ACTIVE', NOW(), NOW(), 0, 0)
ON DUPLICATE KEY UPDATE
    teacher_no = VALUES(teacher_no),
    title = VALUES(title),
    status = VALUES(status),
    update_time = NOW();

INSERT INTO teaching_class (id, name, subject_id, teacher_id, term, status, capacity, create_time, update_time, create_by, update_by)
VALUES
    (3301, 'Java程序设计-1班', 5001, 1002, '2026-春', 'ONGOING', 60, NOW(), NOW(), 0, 0),
    (3302, 'Spring Boot开发-1班', 5002, 1002, '2026-春', 'ONGOING', 60, NOW(), NOW(), 0, 0)
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    subject_id = VALUES(subject_id),
    teacher_id = VALUES(teacher_id),
    term = VALUES(term),
    status = VALUES(status),
    capacity = VALUES(capacity),
    update_time = NOW();

INSERT INTO student_teaching_class (
    id, student_id, subject_id, teaching_class_id, enroll_status, enrolled_at, create_time, update_time, create_by, update_by
)
VALUES
    (3401, 1003, 5001, 3301, 'ACTIVE', NOW(), NOW(), NOW(), 0, 0),
    (3402, 1003, 5002, 3302, 'ACTIVE', NOW(), NOW(), NOW(), 0, 0)
ON DUPLICATE KEY UPDATE
    teaching_class_id = VALUES(teaching_class_id),
    enroll_status = VALUES(enroll_status),
    enrolled_at = VALUES(enrolled_at),
    update_time = NOW();
