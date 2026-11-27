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
  MODIFY biz_type VARCHAR(32) NOT NULL COMMENT 'RECORD/PERIOD/EMPLOYEE/IMPORT/EXPORT/VIEW/REPORT/ROLE/USER/DEPT/COMPANY/CONFIG',
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

-- 12) 系统参数表（可在线调整月计薪天数 / 工龄单价 / 节假日）
CREATE TABLE IF NOT EXISTS sys_config (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  config_key   VARCHAR(64)  NOT NULL,
  config_value TEXT         NULL,
  value_type   VARCHAR(16)  NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT/DECIMAL/INT/DATE_LIST',
  description  VARCHAR(255) NULL,
  editable     TINYINT      NOT NULL DEFAULT 1 COMMENT '0=只读（运维参数）',
  updated_by   BIGINT       NULL,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_config_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统参数（可在线调整的业务规则）';

INSERT IGNORE INTO sys_config (config_key, config_value, value_type, description, editable) VALUES
  ('payroll.monthly-payable-days', '21.75', 'DECIMAL',
   '月平均计薪天数：任职不足整月时的折算分母', 1),
  ('payroll.seniority-pay-per-year', '100', 'DECIMAL',
   '工龄工资：每满一年金额（元）', 1),
  ('payroll.calendar.holidays', '', 'DATE_LIST',
   '法定节假日（逗号分隔 ISO 日期）；为空时回退环境变量 PAYROLL_HOLIDAYS', 1),
  ('payroll.system-version', '1', 'TEXT',
   '系统版本号（只读，运维参数）', 0);

-- 13) 复核岗补 payroll:period:unlock
--    目录里有这个权限点、但没有任何角色持有 → 解锁对所有人 403、锁定不可逆。
INSERT IGNORE INTO sys_role_grant (role_id, perm_code)
SELECT r.id, 'payroll:period:unlock'
  FROM sys_role r
 WHERE r.company_id = 1 AND r.role_code = 'PAYROLL_REVIEWER' AND r.deleted = 0;

-- 14) pay_period.last_run_fail_count
--    分批生成时部分员工失败：run 记 FAILED，期间上留失败人数供前端提示。
ALTER TABLE pay_period
  ADD COLUMN last_run_fail_count INT NOT NULL DEFAULT 0 COMMENT '上次生成失败人数' AFTER employee_count;

-- 15) pay_run_skip：保留完整跳过明细
--    pay_run.error_msg 有 64KB 上限且会截断，HR 关掉弹窗后无法回看"跳过了谁"。
CREATE TABLE IF NOT EXISTS pay_run_skip (
  id       BIGINT       NOT NULL AUTO_INCREMENT,
  run_id   BIGINT       NOT NULL,
  emp_no   VARCHAR(32)  NOT NULL,
  emp_name VARCHAR(64)  NULL,
  reason   VARCHAR(255) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生成跳过明细';

-- 16) H-4 存量回填：已锁定/已归档期间下的记录状态补成 CONFIRMED
--    DRAFT 只代表"编制中"；历史上锁定期间没有同步状态，报表/下游会误判。
UPDATE pay_record r
  JOIN pay_period p ON p.id = r.period_id
   SET r.record_status = 'CONFIRMED'
 WHERE p.status IN ('LOCKED', 'ARCHIVED')
   AND r.record_status = 'DRAFT'
   AND r.deleted = 0;

-- 17) C1 uk_emp_effective 改为 active_key 生成列
--     deleted 直接进唯一键时，同一天只能有一条"已作废"段：第二次同日更正会撞键，
--     员工同一天调薪改错后再也改不回来。
ALTER TABLE emp_comp_profile
  DROP INDEX uk_emp_effective,
  ADD COLUMN active_key TINYINT AS (IF(deleted = 0, 1, NULL)) STORED,
  ADD UNIQUE KEY uk_emp_effective (employee_id, effective_from, active_key);

-- 18) pay_record.period_ym 索引（集团级跨期间查询）
ALTER TABLE pay_record
  ADD KEY idx_period_ym (period_ym);

-- 19) 薪酬专员补 payroll:sensitive:view（证件号按需查看接口的唯一权限入口）
INSERT IGNORE INTO sys_role_grant (role_id, perm_code)
SELECT r.id, 'payroll:sensitive:view'
  FROM sys_role r
 WHERE r.company_id = 1 AND r.role_code = 'PAYROLL_CLERK' AND r.deleted = 0;

-- 20) 个税与社保公积金：5 张表 + 3 个扣款科目 + 参数种子
CREATE TABLE IF NOT EXISTS pay_tax_bracket (
  id BIGINT NOT NULL AUTO_INCREMENT,
  year_min DECIMAL(18,2) NOT NULL,
  year_max DECIMAL(18,2) NULL,
  rate DECIMAL(6,4) NOT NULL,
  quick_deduction DECIMAL(18,2) NOT NULL DEFAULT 0,
  sort_no INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id), KEY idx_range (year_min, year_max)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工资薪金预扣率表';

CREATE TABLE IF NOT EXISTS pay_insurance_param (
  id BIGINT NOT NULL AUTO_INCREMENT,
  city_code VARCHAR(32) NOT NULL, city_name VARCHAR(64) NOT NULL,
  insurance_type VARCHAR(32) NOT NULL, personal_rate DECIMAL(6,4) NOT NULL,
  base_min DECIMAL(18,2) NOT NULL, base_max DECIMAL(18,2) NOT NULL,
  extra_amount DECIMAL(18,2) NOT NULL DEFAULT 0, effective_from DATE NOT NULL,
  status TINYINT NOT NULL DEFAULT 1,
  PRIMARY KEY (id), UNIQUE KEY uk_city_type_from (city_code, insurance_type, effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='社保公积金参数';

CREATE TABLE IF NOT EXISTS emp_social_insurance (
  id BIGINT NOT NULL AUTO_INCREMENT, employee_id BIGINT NOT NULL,
  city_code VARCHAR(32) NOT NULL, social_base DECIMAL(18,2) NULL,
  housing_fund_rate DECIMAL(6,4) NULL, effective_from DATE NOT NULL,
  effective_to DATE NULL, created_by BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id), UNIQUE KEY uk_emp_from (employee_id, effective_from),
  KEY idx_emp_range (employee_id, effective_from, effective_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工参保信息';

CREATE TABLE IF NOT EXISTS emp_special_deduction (
  id BIGINT NOT NULL AUTO_INCREMENT, employee_id BIGINT NOT NULL,
  deduction_type VARCHAR(32) NOT NULL, monthly_amount DECIMAL(18,2) NOT NULL,
  effective_from DATE NOT NULL, effective_to DATE NULL, remark VARCHAR(255) NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_emp_type_from (employee_id, deduction_type, effective_from),
  KEY idx_emp_range (employee_id, effective_from, effective_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='专项附加扣除';

CREATE TABLE IF NOT EXISTS pay_ytd (
  id BIGINT NOT NULL AUTO_INCREMENT, employee_id BIGINT NOT NULL,
  tax_year INT NOT NULL, company_id BIGINT NOT NULL,
  cum_income DECIMAL(18,2) NOT NULL DEFAULT 0, cum_social DECIMAL(18,2) NOT NULL DEFAULT 0,
  cum_fund DECIMAL(18,2) NOT NULL DEFAULT 0, cum_special_deduction DECIMAL(18,2) NOT NULL DEFAULT 0,
  cum_tax DECIMAL(18,2) NOT NULL DEFAULT 0, last_period_ym CHAR(7) NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id), UNIQUE KEY uk_emp_year (employee_id, tax_year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='个税年度累计台账';

INSERT IGNORE INTO pay_tax_bracket (year_min, year_max, rate, quick_deduction, sort_no) VALUES
  (0, 36000, 0.0300, 0, 10), (36000, 144000, 0.1000, 2520, 20),
  (144000, 300000, 0.2000, 16920, 30), (300000, 420000, 0.2500, 31920, 40),
  (420000, 660000, 0.3000, 52920, 50), (660000, 960000, 0.3500, 85920, 60),
  (960000, NULL, 0.4500, 181920, 70);

INSERT IGNORE INTO pay_insurance_param
  (city_code, city_name, insurance_type, personal_rate, base_min, base_max, extra_amount, effective_from, status) VALUES
  ('BJ','北京','PENSION',0.0800,6821,35283,0,'2025-01-01',1),
  ('BJ','北京','MEDICAL',0.0200,6821,35283,3,'2025-01-01',1),
  ('BJ','北京','UNEMPLOYMENT',0.0050,6821,35283,0,'2025-01-01',1),
  ('BJ','北京','HOUSING_FUND',0.1200,2420,35283,0,'2025-01-01',1),
  ('SH','上海','PENSION',0.0800,7384,36921,0,'2025-01-01',1),
  ('SH','上海','MEDICAL',0.0200,7384,36921,0,'2025-01-01',1),
  ('SH','上海','UNEMPLOYMENT',0.0050,7384,36921,0,'2025-01-01',1),
  ('SH','上海','HOUSING_FUND',0.0700,2690,36921,0,'2025-01-01',1),
  ('SZ','深圳','PENSION',0.0800,2360,38892,0,'2025-01-01',1),
  ('SZ','深圳','MEDICAL',0.0200,2360,38892,0,'2025-01-01',1),
  ('SZ','深圳','UNEMPLOYMENT',0.0030,2360,38892,0,'2025-01-01',1),
  ('SZ','深圳','HOUSING_FUND',0.0500,2360,38892,0,'2025-01-01',1);

INSERT IGNORE INTO pay_item (company_id, item_code, item_name, category, direction, data_type,
                             precision_scale, include_in_gross, editable, importable, sort_no, status) VALUES
  (0,'SOCIAL_INS','社保个人','COMPUTED',2,'AMOUNT',2,0,0,0,80,1),
  (0,'HOUSING_FUND','公积金个人','COMPUTED',2,'AMOUNT',2,0,0,0,82,1),
  (0,'TAX','个人所得税','COMPUTED',2,'AMOUNT',2,0,0,0,84,1);
