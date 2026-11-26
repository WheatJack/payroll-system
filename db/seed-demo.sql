-- =============================================================
-- 本地/演示环境初始化数据（不要在生产线执行）
--
-- 用途：schema-mysql.sql 只建表 + 集团通用科目 + 内置角色，
-- 没有任何部门与登录账号，直接启动会"登录不了、建人员没有部门可选"。
-- 本脚本补一条最小可用的演示数据：
--
--   部门：综合部（默认主体下）
--   账号：hr01 / Passw0rd!   角色 薪酬专员（编制，ALL 数据范围）
--         hr02 / Passw0rd!   角色 薪酬复核（锁定/复核，ALL 数据范围）
--         admin / Passw0rd!  角色 系统管理员（全部 sys:* 权限）
--
-- 口令用 BCrypt(强散列) 存储，明文只在本文件的注释里出现，
-- 上线前必须全部删除并改为运维侧单独立账号。
--
-- 导入时必须带字符集，否则中文会被按 latin1 解释成乱码：
--   docker exec -i payroll-mysql mysql -uroot -p --default-character-set=utf8mb4 payroll < db/seed-demo.sql
-- =============================================================

INSERT IGNORE INTO sys_dept (company_id, parent_id, dept_code, dept_name, ancestors, sort_no, status)
VALUES (1, 0, 'D001', '综合部', '', 10, 1);

INSERT IGNORE INTO sys_user (company_id, username, password_hash, real_name, dept_id, status)
SELECT 1, 'hr01', '$2a$10$pzrZzng3ntQtPyi3xD61jO1OVdmPerxACrcsKKsipKXgkuWp0mFKK', '薪酬专员', d.id, 1
  FROM sys_dept d
 WHERE d.company_id = 1 AND d.dept_code = 'D001' AND d.deleted = 0;

INSERT IGNORE INTO sys_user (company_id, username, password_hash, real_name, dept_id, status)
SELECT 1, 'hr02', '$2a$10$pzrZzng3ntQtPyi3xD61jO1OVdmPerxACrcsKKsipKXgkuWp0mFKK', '薪酬复核', d.id, 1
  FROM sys_dept d
 WHERE d.company_id = 1 AND d.dept_code = 'D001' AND d.deleted = 0;

INSERT IGNORE INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
  FROM sys_user u
  JOIN sys_role r ON r.role_code = 'PAYROLL_CLERK' AND r.company_id = 1 AND r.deleted = 0
 WHERE u.username = 'hr01' AND u.deleted = 0;

INSERT IGNORE INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
  FROM sys_user u
  JOIN sys_role r ON r.role_code = 'PAYROLL_REVIEWER' AND r.company_id = 1 AND r.deleted = 0
 WHERE u.username = 'hr02' AND u.deleted = 0;

-- 全新安装后的第一个管理员：没有它，部署完没有任何人能进入系统管理页
INSERT IGNORE INTO sys_user (company_id, username, password_hash, real_name, dept_id, status)
SELECT 1, 'admin', '$2a$10$pzrZzng3ntQtPyi3xD61jO1OVdmPerxACrcsKKsipKXgkuWp0mFKK', '系统管理员', d.id, 1
  FROM sys_dept d
 WHERE d.company_id = 1 AND d.dept_code = 'D001' AND d.deleted = 0;

INSERT IGNORE INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
  FROM sys_user u
  JOIN sys_role r ON r.role_code = 'SYS_ADMIN' AND r.company_id = 1 AND r.deleted = 0
 WHERE u.username = 'admin' AND u.deleted = 0;
