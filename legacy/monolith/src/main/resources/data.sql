-- Legacy reference only.
-- Runtime seed data is managed by Flyway migrations under classpath:db/migration
-- and classpath:db/dev-seed (dev profile only).

INSERT INTO sys_role (id, code, name, create_time, update_time, create_by, update_by)
VALUES
    (1, 'ADMIN', '管理员', NOW(), NOW(), 0, 0),
    (2, 'TEACHER', '教师', NOW(), NOW(), 0, 0),
    (3, 'STUDENT', '学生', NOW(), NOW(), 0, 0)
ON DUPLICATE KEY UPDATE name = VALUES(name), update_time = NOW();

INSERT INTO sys_user (id, username, password, real_name, enabled, create_time, update_time, create_by, update_by)
VALUES
    (1001, 'admin', '{noop}123456', '系统管理员', 1, NOW(), NOW(), 0, 0),
    (1002, 'teacher1', '{noop}123456', '教师A', 1, NOW(), NOW(), 0, 0),
    (1003, 'student1', '{noop}123456', '学生A', 1, NOW(), NOW(), 0, 0)
ON DUPLICATE KEY UPDATE real_name = VALUES(real_name), enabled = VALUES(enabled), update_time = NOW();

INSERT INTO sys_user_role (id, user_id, role_id, create_time, update_time, create_by, update_by)
VALUES
    (2001, 1001, 1, NOW(), NOW(), 0, 0),
    (2002, 1002, 2, NOW(), NOW(), 0, 0),
    (2003, 1003, 3, NOW(), NOW(), 0, 0)
ON DUPLICATE KEY UPDATE update_time = NOW();

INSERT INTO subject (id, name, description, create_time, update_time, create_by, update_by)
VALUES
    (5001, 'Java程序设计', 'Java基础与面向对象', NOW(), NOW(), 0, 0),
    (5002, 'Spring Boot开发', 'Spring Boot Web与自动配置', NOW(), NOW(), 0, 0),
    (5003, '数据库原理与SQL', '关系数据库与SQL查询优化', NOW(), NOW(), 0, 0)
ON DUPLICATE KEY UPDATE name = VALUES(name), description = VALUES(description), update_time = NOW();

INSERT INTO student_profile (id, user_id, student_no, enrollment_year, status, create_time, update_time, create_by, update_by)
VALUES
    (3101, 1003, 'S20260001', '2026', 'ACTIVE', NOW(), NOW(), 0, 0)
ON DUPLICATE KEY UPDATE student_no = VALUES(student_no), enrollment_year = VALUES(enrollment_year), status = VALUES(status), update_time = NOW();

INSERT INTO teacher_profile (id, user_id, teacher_no, title, status, create_time, update_time, create_by, update_by)
VALUES
    (3201, 1002, 'T20260001', '讲师', 'ACTIVE', NOW(), NOW(), 0, 0)
ON DUPLICATE KEY UPDATE teacher_no = VALUES(teacher_no), title = VALUES(title), status = VALUES(status), update_time = NOW();

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




INSERT INTO question
(id, subject_id, type, difficulty, content, options_json, answer, analysis, default_score, creator_id, create_time, update_time, create_by, update_by)
VALUES
-- subject_id = 5001（Java）
(61001, 5001, 'SINGLE', 'EASY',   'Java程序运行在什么环境中？',
 '[{"label":"A","value":"JVM"},{"label":"B","value":"CLR"},{"label":"C","value":"Node.js"},{"label":"D","value":"V8"}]',
 'A', '略', 5, 1001, NOW(), NOW(), 1001, 1001),

(61002, 5001, 'MULTI',  'MEDIUM', '以下哪些属于Java语言特性？',
 '[{"label":"A","value":"面向对象"},{"label":"B","value":"跨平台"},{"label":"C","value":"指针算术"},{"label":"D","value":"自动垃圾回收"}]',
 'A,B,D', '略', 5, 1001, NOW(), NOW(), 1001, 1001),

(61003, 5001, 'JUDGE',  'EASY',   'Java是一种编译型并运行在虚拟机上的语言。',
 '[{"label":"A","value":"true"},{"label":"B","value":"false"}]',
 'A', '略', 5, 1001, NOW(), NOW(), 1001, 1001),

(61004, 5001, 'BLANK',  'MEDIUM', 'Java中不可变字符串对应的类是____。',
 NULL, 'String', '略', 5, 1001, NOW(), NOW(), 1001, 1001),

(61005, 5001, 'SHORT',  'HARD',   '简述JVM内存结构的主要组成部分。',
 NULL, '堆、虚拟机栈、本地方法栈、方法区、程序计数器', '略', 10, 1001, NOW(), NOW(), 1001, 1001),

-- subject_id = 5002（Spring Boot）
(61006, 5002, 'SINGLE', 'EASY',   'Spring Boot默认内嵌Web容器常用端口是？',
 '[{"label":"A","value":"80"},{"label":"B","value":"8080"},{"label":"C","value":"443"},{"label":"D","value":"3306"}]',
 'B', '略', 5, 1001, NOW(), NOW(), 1001, 1001),

(61007, 5002, 'MULTI',  'MEDIUM', '以下哪些注解常用于Spring Boot Web开发？',
 '[{"label":"A","value":"@RestController"},{"label":"B","value":"@RequestMapping"},{"label":"C","value":"@EntityScan"},{"label":"D","value":"@GetMapping"}]',
 'A,B,D', '略', 5, 1001, NOW(), NOW(), 1001, 1001),

(61008, 5002, 'JUDGE',  'EASY',   '@SpringBootApplication包含@ComponentScan能力。',
 '[{"label":"A","value":"true"},{"label":"B","value":"false"}]',
 'A', '略', 5, 1001, NOW(), NOW(), 1001, 1001),

(61009, 5002, 'BLANK',  'MEDIUM', 'Spring Boot默认Tomcat端口是____。',
 NULL, '8080', '略', 5, 1001, NOW(), NOW(), 1001, 1001),

(61010, 5002, 'SHORT',  'HARD',   '简述Spring Boot自动配置的大致原理。',
 NULL, '通过@EnableAutoConfiguration加载META-INF/spring/...自动配置类并按条件装配Bean', '略', 10, 1001, NOW(), NOW(), 1001, 1001),

-- subject_id = 5003（数据库/SQL）
(61011, 5003, 'SINGLE', 'EASY',   '关系型数据库中，主键的核心特性是？',
 '[{"label":"A","value":"可重复"},{"label":"B","value":"唯一且非空"},{"label":"C","value":"可为空"},{"label":"D","value":"仅用于排序"}]',
 'B', '略', 5, 1001, NOW(), NOW(), 1001, 1001),

(61012, 5003, 'MULTI',  'MEDIUM', '索引的主要作用包括哪些？',
 '[{"label":"A","value":"加快查询"},{"label":"B","value":"保证所有查询都更快"},{"label":"C","value":"可能增加写入开销"},{"label":"D","value":"可用于排序优化"}]',
 'A,C,D', '略', 5, 1001, NOW(), NOW(), 1001, 1001),

(61013, 5003, 'JUDGE',  'EASY',   'LEFT JOIN会返回左表全部记录。',
 '[{"label":"A","value":"true"},{"label":"B","value":"false"}]',
 'A', '略', 5, 1001, NOW(), NOW(), 1001, 1001),

(61014, 5003, 'BLANK',  'MEDIUM', 'SQL中用于排序的关键字是____。',
 NULL, 'ORDER BY', '略', 5, 1001, NOW(), NOW(), 1001, 1001),

(61015, 5003, 'SHORT',  'HARD',   '简述事务隔离级别及其意义。',
 NULL, '读未提交、读已提交、可重复读、串行化；用于平衡并发与一致性', '略', 10, 1001, NOW(), NOW(), 1001, 1001);
