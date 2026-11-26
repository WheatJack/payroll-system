-- =============================================================
-- 2026-11-26 · M1 后端增量升级（每个环境执行一次）
--
-- 对「已经按旧版 schema-mysql.sql 建过表」的环境执行。
-- 全新环境直接执行 schema-mysql.sql 即可，不需要本文件。
--
-- 注意：MySQL 的 ADD COLUMN / DROP INDEX 不支持 IF NOT EXISTS，
-- 重复执行会在已应用的语句上报「已存在 / 不存在」的错，跳过即可。
-- =============================================================

-- 1) 任务租约精度提升到微秒
--    TTL 可能只有毫秒级；DATETIME 是秒级精度，「已过期」判断会被拖到下一秒，
--    导致快速重试全部失败。
ALTER TABLE pay_period
  MODIFY processing_expire_at DATETIME(6) NULL COMMENT '租约过期时间，过期可被抢占';

-- 2) pay_record.version_no 默认值 1 → 0
--    契约统一为「新建记录 version=0，每次 changeset 保存 +1」。
ALTER TABLE pay_record
  MODIFY version_no INT NOT NULL DEFAULT 0;

-- 3) emp_comp_profile 唯一键纳入 deleted
--    调薪不再就地 UPDATE 旧段，而是把旧段作废（deleted=1）再开新段。
--    原来的 uk(employee_id, effective_from) 会让作废的旧段仍然占着那一天。
ALTER TABLE emp_comp_profile
  DROP INDEX uk_emp_effective,
  ADD UNIQUE KEY uk_emp_effective (employee_id, effective_from, deleted);

-- 4) sys_user.token_version
--    无状态 token 的版本号：重置密码时 +1，旧 token 立即失效。
ALTER TABLE sys_user
  ADD COLUMN token_version INT NOT NULL DEFAULT 0 COMMENT 'token 版本号，改密码时 +1' AFTER status;

-- 5) pay_record(company_id, period_ym)
--    本主体本期间查询与跨期间报表都要走这个前缀，避免全表扫。
ALTER TABLE pay_record
  ADD KEY idx_company_period (company_id, period_ym);

-- 6) pay_import_batch(period_id, status, file_hash)
--    导入幂等校验按「期间 + 状态 + 文件哈希」查。
ALTER TABLE pay_import_batch
  ADD KEY idx_period_status_hash (period_id, status, file_hash);

-- 7) pay_period.column_snapshot
--    期间工资表导出的前提：明细可能含已停用科目，
--    导出列必须取生成时的快照，不能取当前启用科目。
ALTER TABLE pay_period
  ADD COLUMN column_snapshot JSON NULL COMMENT '生成时的工资表列快照' AFTER version;

-- 8) 删除零写零读的预聚合表（留空表等于留下第二个真源）
DROP TABLE IF EXISTS pay_summary_monthly;

-- 9) 导入幂等交给唯一约束（P0-5）
--    同一期间 + 同一业务类型 + 同一文件只允许一个批次；并发上传靠撞键拦，
--    不再"先查后插"（TOCTOU）。APPEND 模式已删除：对绝对金额做累加等于重复发薪。
ALTER TABLE pay_import_batch
  ADD UNIQUE KEY uk_period_type_hash (period_id, biz_type, file_hash);

-- 10) 审计口径：补充 REPORT / GRANT_REPLACE 等实际使用的取值
ALTER TABLE pay_audit_log
  MODIFY biz_type VARCHAR(32) NOT NULL COMMENT 'RECORD/PERIOD/EMPLOYEE/IMPORT/EXPORT/VIEW/REPORT/ROLE/USER/DEPT/COMPANY',
  MODIFY action  VARCHAR(32) NOT NULL COMMENT 'CREATE/UPDATE/DELETE/LOCK/UNLOCK/EXPORT/VIEW/GRANT_REPLACE';

-- 11) 系统管理员角色（全新安装的 schema 已内置；老环境在此补齐）
--    没有它，部署完没有任何人能进入系统管理页创建用户/分配权限。
INSERT IGNORE INTO sys_role (company_id, role_code, role_name, data_scope)
VALUES (1, 'SYS_ADMIN', '系统管理员', 'ALL');

INSERT IGNORE INTO sys_role_grant (role_id, perm_code)
SELECT r.id, p.code
  FROM sys_role r
  JOIN (SELECT 'sys:user:view' AS code
        UNION ALL SELECT 'sys:user:edit'
        UNION ALL SELECT 'sys:role:view'
        UNION ALL SELECT 'sys:role:edit'
        UNION ALL SELECT 'sys:role:grant:all'
        UNION ALL SELECT 'sys:dept:edit'
        UNION ALL SELECT 'sys:company:edit') p
 WHERE r.company_id = 1 AND r.role_code = 'SYS_ADMIN' AND r.deleted = 0;
