START TRANSACTION;

INSERT INTO teacher_profile(id, user_id, teacher_no, title, status, create_time, update_time) VALUES
  (2, 2, 'T001', '讲师', 'ACTIVE', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
ON DUPLICATE KEY UPDATE
  teacher_no = VALUES(teacher_no),
  title = VALUES(title),
  status = 'ACTIVE',
  update_time = CURRENT_TIMESTAMP(3);

INSERT INTO student_profile(id, user_id, student_no, enrollment_year, status, create_time, update_time) VALUES
  (20010001, 20010001, '20010001', '2020', 'ACTIVE', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
  (20010002, 20010002, '20010002', '2020', 'ACTIVE', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)),
  (20010003, 20010003, '20010003', '2020', 'ACTIVE', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
ON DUPLICATE KEY UPDATE
  student_no = VALUES(student_no),
  enrollment_year = VALUES(enrollment_year),
  status = 'ACTIVE',
  update_time = CURRENT_TIMESTAMP(3);

INSERT INTO subject(id, name, description, create_time, update_time, create_by, update_by) VALUES
  (10001, '计算机网络', '介绍网络体系结构、TCP/IP 协议、路由交换、应用层协议与网络安全基础。', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 1, 1),
  (10002, '数据结构与算法', '涵盖线性表、树、图、查找、排序以及常用算法设计与复杂度分析。', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 1, 1),
  (10003, '操作系统', '介绍进程线程、处理器调度、内存管理、文件系统、并发控制与死锁。', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 1, 1),
  (10004, '数据库系统', '涵盖关系模型、SQL、事务并发、索引、数据库设计与性能优化。', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 1, 1),
  (10005, 'Java 程序设计', '介绍 Java 语法、面向对象、集合、异常、并发、JVM 与工程实践。', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 1, 1)
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  description = VALUES(description),
  update_time = CURRENT_TIMESTAMP(3),
  update_by = VALUES(update_by);

COMMIT;
