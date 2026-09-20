INSERT INTO sys_role (id, code, name, create_time, update_time, create_by, update_by)
VALUES
    (1, 'ADMIN', '管理员', NOW(), NOW(), 0, 0),
    (2, 'TEACHER', '教师', NOW(), NOW(), 0, 0),
    (3, 'STUDENT', '学生', NOW(), NOW(), 0, 0)
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    update_time = NOW();
