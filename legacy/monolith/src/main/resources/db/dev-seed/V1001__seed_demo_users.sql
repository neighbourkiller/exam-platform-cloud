INSERT INTO sys_user (id, username, password, real_name, enabled, create_time, update_time, create_by, update_by)
VALUES
    (1001, 'admin', '{noop}123456', '系统管理员', 1, NOW(), NOW(), 0, 0),
    (1002, 'teacher1', '{noop}123456', '教师A', 1, NOW(), NOW(), 0, 0),
    (1003, 'student1', '{noop}123456', '学生A', 1, NOW(), NOW(), 0, 0)
ON DUPLICATE KEY UPDATE
    real_name = VALUES(real_name),
    enabled = VALUES(enabled),
    update_time = NOW();

INSERT INTO sys_user_role (id, user_id, role_id, create_time, update_time, create_by, update_by)
VALUES
    (2001, 1001, 1, NOW(), NOW(), 0, 0),
    (2002, 1002, 2, NOW(), NOW(), 0, 0),
    (2003, 1003, 3, NOW(), NOW(), 0, 0)
ON DUPLICATE KEY UPDATE
    update_time = NOW();
