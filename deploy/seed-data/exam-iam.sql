START TRANSACTION;

INSERT INTO sys_role(id, code, name, create_time, update_time) VALUES
  (1, 'ADMIN', '管理员', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
  (2, 'TEACHER', '教师', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
  (3, 'STUDENT', '学生', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
ON DUPLICATE KEY UPDATE
  code = VALUES(code),
  name = VALUES(name),
  update_time = CURRENT_TIMESTAMP(3);

INSERT INTO sys_user(id, username, password, real_name, enabled, token_version, create_time, update_time) VALUES
  (1, 'admin', @seed_password_hash, '系统管理员', 1, 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
  (2, 'teacher01', @seed_password_hash, '李老师', 1, 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
  (20010001, '20010001', @seed_password_hash, '张三', 1, 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
  (20010002, '20010002', @seed_password_hash, '李四', 1, 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
  (20010003, '20010003', @seed_password_hash, '王五', 1, 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
ON DUPLICATE KEY UPDATE
  real_name = VALUES(real_name),
  enabled = 1,
  update_time = CURRENT_TIMESTAMP(3);

INSERT INTO sys_user_role(id, user_id, role_id, create_time, update_time) VALUES
  (10001, 1, 1, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
  (10002, 2, 2, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
  (10003, 20010001, 3, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
  (10004, 20010002, 3, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
  (10005, 20010003, 3, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
ON DUPLICATE KEY UPDATE
  user_id = VALUES(user_id),
  role_id = VALUES(role_id),
  update_time = CURRENT_TIMESTAMP(3);

COMMIT;
