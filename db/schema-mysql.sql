-- =============================================================
-- 工资核算系统 · DDL (MySQL 8.0)
-- 约定:
--   1. 金额一律 DECIMAL(18,4)，数量 DECIMAL(12,2)，禁止 DOUBLE/FLOAT
--   2. 所有业务表带 deleted 软删除标志，不提供物理删除
--   3. 需求4 的唯一性通过生成列 active_key 实现（NULL 不参与唯一约束），
--      索引为集团级 (employee_id, period_ym)，跨主体同月重复发薪会被拦住
--   4. PostgreSQL 变体: 把 uk_emp_ym_active 换成
--      CREATE UNIQUE INDEX uk_emp_ym_active
--        ON pay_record(employee_id, period_ym) WHERE deleted = 0;
-- =============================================================

SET NAMES utf8mb4;

-- -------------------------------------------------------------
-- 组织与权限
-- -------------------------------------------------------------

CREATE TABLE sys_company (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  company_code  VARCHAR(32)  NOT NULL COMMENT '主体编码',
  company_name  VARCHAR(128) NOT NULL COMMENT '主体名称',
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
  created_by    BIGINT       NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by    BIGINT       NULL,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted       TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_company_code (company_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='法人主体';

CREATE TABLE sys_dept (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  company_id  BIGINT       NOT NULL,
  parent_id   BIGINT       NOT NULL DEFAULT 0,
  dept_code   VARCHAR(32)  NOT NULL,
  dept_name   VARCHAR(64)  NOT NULL,
  ancestors   VARCHAR(512) NOT NULL DEFAULT '' COMMENT '祖先路径 ,1,2,3, 用于数据权限前缀匹配',
  sort_no     INT          NOT NULL DEFAULT 0,
  status      TINYINT      NOT NULL DEFAULT 1,
  created_by  BIGINT       NULL,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by  BIGINT       NULL,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted     TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_dept_code (company_id, dept_code),
  KEY idx_parent (parent_id),
  KEY idx_ancestors (ancestors(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='部门';

CREATE TABLE sys_user (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  company_id    BIGINT       NOT NULL,
  username      VARCHAR(64)  NOT NULL,
  password_hash VARCHAR(128) NOT NULL COMMENT 'BCrypt',
  real_name     VARCHAR(64)  NOT NULL,
  employee_id   BIGINT       NULL COMMENT '关联人员, SELF 数据范围用',
  dept_id       BIGINT       NULL,
  mobile_mask   VARCHAR(16)  NULL,
  status        TINYINT      NOT NULL DEFAULT 1,
  -- 无状态 token 的版本号：改密码/强制下线时 +1，旧 token 立即失效
  token_version INT          NOT NULL DEFAULT 0,
  last_login_at DATETIME     NULL,
  created_by    BIGINT       NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by    BIGINT       NULL,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted       TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username),
  KEY idx_employee (employee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户';

CREATE TABLE sys_role (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  company_id  BIGINT      NOT NULL,
  role_code   VARCHAR(64) NOT NULL,
  role_name   VARCHAR(64) NOT NULL,
  data_scope  VARCHAR(16) NOT NULL DEFAULT 'SELF' COMMENT 'ALL集团 / COMPANY本公司 / DEPT_SUB部门及下级 / DEPT本部门 / SELF本人',
  remark      VARCHAR(255) NULL,
  status      TINYINT     NOT NULL DEFAULT 1,
  created_by  BIGINT      NULL,
  created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by  BIGINT      NULL,
  updated_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted     TINYINT     NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_role_code (company_id, role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色';

CREATE TABLE sys_user_role (
  id      BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_role (user_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户角色';

CREATE TABLE sys_role_grant (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  role_id     BIGINT      NOT NULL,
  perm_code   VARCHAR(64) NOT NULL COMMENT '见详细设计 §4.7 权限点',
  PRIMARY KEY (id),
  UNIQUE KEY uk_role_perm (role_id, perm_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色权限点';

-- -------------------------------------------------------------
-- 人员与薪酬档案
-- -------------------------------------------------------------

CREATE TABLE emp_employee (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  company_id      BIGINT       NOT NULL COMMENT '当前归属法人主体（调动时变更，历史工资表走快照）',
  emp_no          VARCHAR(32)  NOT NULL COMMENT '工号（集团内统一唯一）',
  name            VARCHAR(64)  NOT NULL,
  gender          TINYINT      NOT NULL DEFAULT 0 COMMENT '0未知 1男 2女',
  id_type         VARCHAR(16)  NOT NULL DEFAULT 'ID_CARD',
  id_no_cipher    VARBINARY(256) NOT NULL COMMENT 'AES-256 密文',
  id_no_hash      CHAR(64)     NOT NULL COMMENT 'HMAC-SHA256(盲索引密钥, 归一化证件号) 用于查重；不可逆、抗彩虹表',
  id_no_hash_version TINYINT   NOT NULL DEFAULT 1 COMMENT '盲索引密钥版本，密钥轮换时按版本渐进重算',
  id_no_mask      VARCHAR(32)  NOT NULL COMMENT '展示掩码',
  mobile_cipher   VARBINARY(128) NULL,
  mobile_hash     CHAR(64)     NULL,
  mobile_mask     VARCHAR(16)  NULL,
  employee_type   VARCHAR(32)  NOT NULL COMMENT 'REGULAR/PROBATION/INTERN/LABOR/OUTSOURCE/REHIRE',
  job_level       VARCHAR(32)  NULL COMMENT '职级',
  position_name   VARCHAR(64)  NULL COMMENT '岗位',
  dept_id         BIGINT       NOT NULL,
  hire_date       DATE         NOT NULL COMMENT '入职日期（本单位工龄起算）',
  work_start_date DATE         NULL COMMENT '参加工作时间（社会工龄起算）',
  leave_date      DATE         NULL,
  status          TINYINT      NOT NULL DEFAULT 1 COMMENT '1在职 2离职 3停薪留职',
  remark          VARCHAR(255) NULL,
  created_by      BIGINT       NULL,
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by      BIGINT       NULL,
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted         TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  -- 集团内统一工号；若各法人独立编号，改为 UNIQUE(company_id, emp_no)
  UNIQUE KEY uk_emp_no (emp_no),
  -- 同一人在集团内只有一条人员记录，跨主体调动不重建（工龄连续）
  UNIQUE KEY uk_id_hash (id_no_hash),
  KEY idx_company_dept (company_id, dept_id),
  KEY idx_dept (dept_id),
  KEY idx_name (name),
  KEY idx_type_status (employee_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人员主数据';

CREATE TABLE emp_comp_profile (
  id             BIGINT        NOT NULL AUTO_INCREMENT,
  employee_id    BIGINT        NOT NULL,
  effective_from DATE          NOT NULL COMMENT '生效起（含）',
  effective_to   DATE          NULL COMMENT '生效止（含），NULL=至今',
  base_salary    DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '基本工资=加班费/经济补偿法定基数，独立建模',
  change_reason  VARCHAR(255)  NULL COMMENT '调薪原因',
  status         TINYINT       NOT NULL DEFAULT 1 COMMENT '1有效 2作废',
  created_by     BIGINT        NULL,
  created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by     BIGINT        NULL,
  updated_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted        TINYINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  -- 生成列：只让"有效"段参与唯一约束。把 deleted 直接放进唯一键会让同一天
  -- 只能存在一条作废段，第二次同日更正会撞键（同一错误改不回来）。
  active_key TINYINT AS (IF(deleted = 0, 1, NULL)) STORED,
  UNIQUE KEY uk_emp_effective (employee_id, effective_from, active_key),
  KEY idx_emp_range (employee_id, effective_from, effective_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='薪酬档案（按生效区间版本化）';

CREATE TABLE emp_comp_profile_item (
  id         BIGINT        NOT NULL AUTO_INCREMENT,
  profile_id BIGINT        NOT NULL,
  item_code  VARCHAR(32)   NOT NULL COMMENT '固定类工资项编码',
  amount     DECIMAL(18,4) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_profile_item (profile_id, item_code),
  KEY idx_profile (profile_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='薪酬档案固定津贴明细';

-- -------------------------------------------------------------
-- 员工在各主体的任职区间（多法人归属历史）
-- 核算时按"期间末日"确定员工当时属于哪个主体，而不是取人员表的当前 company_id。
-- 没有这张表，解锁重算历史期间会用错法人主体。
-- -------------------------------------------------------------

CREATE TABLE emp_employment (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  employee_id BIGINT       NOT NULL,
  company_id  BIGINT       NOT NULL,
  dept_id     BIGINT       NOT NULL,
  start_date  DATE         NOT NULL COMMENT '在该主体的起始日（含）',
  end_date    DATE         NULL COMMENT '结束日（含），NULL=至今',
  change_type VARCHAR(16)  NOT NULL DEFAULT 'HIRE' COMMENT 'HIRE入职 / TRANSFER调入 / PROMOTE调岗 / LEAVE离职',
  remark      VARCHAR(255) NULL,
  created_by  BIGINT       NULL,
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by  BIGINT       NULL,
  updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted     TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_emp_company_start (employee_id, company_id, start_date),
  KEY idx_emp_range (employee_id, start_date, end_date),
  KEY idx_company_dept (company_id, dept_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工任职区间（决定核算期间的法人主体归属）';

-- -------------------------------------------------------------
-- 工资项字典
-- -------------------------------------------------------------
CREATE TABLE pay_item (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  company_id       BIGINT       NOT NULL DEFAULT 0 COMMENT '0=全局',
  item_code        VARCHAR(32)  NOT NULL,
  item_name        VARCHAR(64)  NOT NULL,
  category         VARCHAR(16)  NOT NULL COMMENT 'FIXED固定 / VARIABLE变动 / COMPUTED计算',
  direction        TINYINT      NOT NULL COMMENT '1增加(收入) 2减少(扣款)',
  data_type        VARCHAR(16)  NOT NULL DEFAULT 'AMOUNT' COMMENT 'AMOUNT金额 / HOURS工时 / DAYS天数 / RATE单价',
  precision_scale  TINYINT      NOT NULL DEFAULT 2,
  include_in_gross TINYINT      NOT NULL DEFAULT 1 COMMENT '是否计入应发合计',
  editable         TINYINT      NOT NULL DEFAULT 1 COMMENT '是否允许手工编辑',
  importable       TINYINT      NOT NULL DEFAULT 1,
  sort_no          INT          NOT NULL DEFAULT 0 COMMENT '工资表列顺序',
  status           TINYINT      NOT NULL DEFAULT 1,
  created_by       BIGINT       NULL,
  created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by       BIGINT       NULL,
  updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted          TINYINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_item_code (company_id, item_code),
  KEY idx_sort (sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工资项字典';

-- -------------------------------------------------------------
-- 期间 / 批次 / 核算
-- -------------------------------------------------------------

CREATE TABLE pay_period (
  id             BIGINT        NOT NULL AUTO_INCREMENT,
  company_id     BIGINT        NOT NULL,
  period_ym      CHAR(7)       NOT NULL COMMENT 'YYYY-MM',
  status         VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/EDITING/LOCKED/ARCHIVED',
  employee_count INT           NOT NULL DEFAULT 0,
  -- 上次生成失败人数：部分成功时 run 状态为 FAILED，前端靠它提示"有人没算"
  last_run_fail_count INT      NOT NULL DEFAULT 0 COMMENT '上次生成失败人数',
  total_gross    DECIMAL(18,4) NOT NULL DEFAULT 0,
  total_net      DECIMAL(18,4) NOT NULL DEFAULT 0,
  hpd            DECIMAL(6,2)  NOT NULL DEFAULT 21.75 COMMENT '期间计薪天数参数',
  compiled_by    BIGINT        NULL COMMENT '最后编制人；用于"锁定人 ≠ 编制人"内控校验（无此字段该规则无法落地）',
  locked_by      BIGINT        NULL,
  locked_at      DATETIME      NULL,
  unlock_reason  VARCHAR(255)  NULL,
  -- 任务租赁：防止两人同时触发生成/导入。长任务不得持有行锁，改为抢占租约
  -- 精度用微秒：租约 TTL 可能只有毫秒级（测试与快速重试），DATETIME 秒级会把过期判断拖到下一秒
  processing_owner     VARCHAR(64) NULL COMMENT '当前持有处理租约的任务标识',
  processing_expire_at DATETIME(6) NULL COMMENT '租约过期时间，过期可被抢占',
  version        INT           NOT NULL DEFAULT 0 COMMENT '乐观锁',
  -- 生成时的工资表列快照（JSON）。导出必须用它而不是当前启用科目：
  -- 明细里可能含已停用科目，按当前字典导列会和合计对不上。
  column_snapshot JSON         NULL COMMENT '生成时的工资表列快照',
  created_by     BIGINT        NULL,
  created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by     BIGINT        NULL,
  updated_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_company_period (company_id, period_ym)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工资期间';

CREATE TABLE pay_run (
  id           BIGINT      NOT NULL AUTO_INCREMENT,
  period_id    BIGINT      NOT NULL,
  run_no       VARCHAR(32) NOT NULL,
  run_type     VARCHAR(16) NOT NULL COMMENT 'INIT首次 / RECALC重算',
  status       VARCHAR(16) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/SUCCESS/FAILED',
  emp_total    INT         NOT NULL DEFAULT 0,
  emp_success  INT         NOT NULL DEFAULT 0,
  emp_failed   INT         NOT NULL DEFAULT 0,
  operator_id  BIGINT      NULL,
  started_at   DATETIME    NULL,
  finished_at  DATETIME    NULL,
  error_msg    TEXT        NULL,
  created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_run_no (run_no),
  KEY idx_period (period_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='核算批次';

-- 生成跳过的明细（可回看/导出）：pay_run.error_msg 会截断，不能作为审计依据
CREATE TABLE pay_run_skip (
  id       BIGINT       NOT NULL AUTO_INCREMENT,
  run_id   BIGINT       NOT NULL,
  emp_no   VARCHAR(32)  NOT NULL,
  emp_name VARCHAR(64)  NULL,
  reason   VARCHAR(255) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生成跳过明细';

CREATE TABLE pay_record (
  id                   BIGINT        NOT NULL AUTO_INCREMENT,
  period_id            BIGINT        NOT NULL,
  period_ym            CHAR(7)       NOT NULL COMMENT '冗余自期间，用于集团级跨主体同月查重',
  company_id           BIGINT        NOT NULL,
  employee_id          BIGINT        NOT NULL,
  -- 快照：人员信息此后变动不影响历史工资表
  emp_no               VARCHAR(32)   NOT NULL,
  emp_name             VARCHAR(64)   NOT NULL,
  dept_id              BIGINT        NOT NULL,
  dept_name            VARCHAR(64)   NOT NULL,
  job_level            VARCHAR(32)   NULL,
  employee_type        VARCHAR(32)   NOT NULL,
  hire_date            DATE          NULL,
  seniority_years      DECIMAL(6,2)  NULL COMMENT '按期间末日计算的工龄',
  base_salary_snapshot DECIMAL(18,4) NOT NULL DEFAULT 0,
  -- 合计
  gross_amount         DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '应发合计',
  deduction_amount     DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '扣款合计',
  net_amount           DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT '实发 = gross - deduction',
  -- 版本
  record_status        VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CONFIRMED',
  version_no           INT           NOT NULL DEFAULT 0,
  superseded_by        BIGINT        NULL COMMENT '被哪条记录取代',
  remark               VARCHAR(255)  NULL,
  created_by           BIGINT        NULL,
  created_at           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by           BIGINT        NULL,
  updated_at           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted              TINYINT       NOT NULL DEFAULT 0,
  active_key           TINYINT AS (IF(deleted = 0, 1, NULL)) STORED,
  PRIMARY KEY (id),
  CONSTRAINT chk_record_period_ym CHECK (period_ym REGEXP '^[0-9]{4}-(0[1-9]|1[0-2])$'),
  -- 需求4（集团级）：同一人在同一年月、跨所有法人主体，只允许一条有效核算记录。
  -- 这是"北京和上海同时给一个人发工资"的唯一兜底，按 period_id 建索引拦不住。
  UNIQUE KEY uk_emp_ym_active (employee_id, period_ym, active_key),
  KEY idx_company_period (company_id, period_ym),
  KEY idx_period_emp (period_id, employee_id),
  KEY idx_period_dept (period_id, dept_id),
  KEY idx_employee (employee_id),
  KEY idx_period_status (period_id, record_status),
  -- 集团级跨期间报表/查重按 period_ym 过滤，避免退化成全表扫
  KEY idx_period_ym (period_ym)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工资核算记录（含人员快照）';

CREATE TABLE pay_record_item (
  id         BIGINT        NOT NULL AUTO_INCREMENT,
  record_id  BIGINT        NOT NULL,
  item_code  VARCHAR(32)   NOT NULL,
  item_name  VARCHAR(64)   NOT NULL COMMENT '冗余，防止科目改名后历史错乱',
  category   VARCHAR(16)   NOT NULL,
  direction  TINYINT       NOT NULL,
  quantity   DECIMAL(12,2) NULL COMMENT '工时/天数（本期不用：变动项直接导入金额）',
  unit_price DECIMAL(18,4) NULL COMMENT '单价/时薪（本期不用）',
  amount     DECIMAL(18,4) NOT NULL DEFAULT 0,
  source     VARCHAR(16)   NOT NULL COMMENT 'FIXED/IMPORT/MANUAL/COMPUTED',
  sort_no    INT           NOT NULL DEFAULT 0,
  created_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_record_item (record_id, item_code),
  KEY idx_record (record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='核算明细（一行一个工资项）';

-- -------------------------------------------------------------
-- 导入
-- -------------------------------------------------------------

CREATE TABLE pay_import_batch (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  period_id    BIGINT       NOT NULL,
  biz_type     VARCHAR(16)  NOT NULL COMMENT 'FIXED / VARIABLE',
  file_name    VARCHAR(255) NOT NULL,
  file_hash    CHAR(64)     NOT NULL COMMENT 'SHA-256，防重复提交',
  file_path    VARCHAR(512) NULL COMMENT 'MinIO 对象路径',
  import_mode  VARCHAR(16)  NOT NULL DEFAULT 'UPSERT' COMMENT 'UPSERT/INSERT_ONLY（APPEND 已下线：绝对金额累加等于重复发薪）',
  total_rows   INT          NOT NULL DEFAULT 0,
  success_rows INT          NOT NULL DEFAULT 0,
  fail_rows    INT          NOT NULL DEFAULT 0,
  status       VARCHAR(16)  NOT NULL COMMENT 'VALIDATING/VALIDATED/COMMITTED/FAILED',
  operator_id  BIGINT       NOT NULL,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finished_at  DATETIME     NULL,
  PRIMARY KEY (id),
  -- 幂等第一步：同一期间 + 同一业务类型 + 同一文件只允许一个批次。
  -- 依赖唯一约束而不是"先查后插"：并发上传同一文件时先查后插会双双通过。
  UNIQUE KEY uk_period_type_hash (period_id, biz_type, file_hash),
  KEY idx_period_status_hash (period_id, status, file_hash),
  KEY idx_period (period_id),
  KEY idx_file_hash (file_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='导入批次';

CREATE TABLE pay_import_error (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  batch_id    BIGINT       NOT NULL,
  row_no      INT          NOT NULL COMMENT 'Excel 行号',
  column_name VARCHAR(64)  NULL,
  raw_value   VARCHAR(512) NULL,
  error_code  VARCHAR(32)  NOT NULL,
  error_msg   VARCHAR(255) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_batch (batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='导入错误行（仅保留前 5000 行）';

-- -------------------------------------------------------------
-- 补发/扣回：不在本期范围（更正通过"解锁期间 → 修改 → 重新锁定"实现）
-- -------------------------------------------------------------

-- 汇总预聚合表 pay_summary_monthly 已删除：
-- 它零写零读，留着只会成为第二个真源；月度/年度汇总改为查询时按 pay_record 聚合。

-- -------------------------------------------------------------
-- 系统参数
-- -------------------------------------------------------------

CREATE TABLE sys_config (
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

-- -------------------------------------------------------------
-- 个税与社保公积金
-- -------------------------------------------------------------

CREATE TABLE pay_tax_bracket (
  id              BIGINT        NOT NULL AUTO_INCREMENT,
  year_min        DECIMAL(18,2) NOT NULL COMMENT '累计应纳税所得额下限（含）',
  year_max        DECIMAL(18,2) NULL COMMENT '上限（不含），NULL=无上限',
  rate            DECIMAL(6,4)  NOT NULL COMMENT '预扣率，如 0.0300',
  quick_deduction DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '速算扣除数',
  sort_no         INT           NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_range (year_min, year_max)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工资薪金预扣率表';

CREATE TABLE pay_insurance_param (
  id             BIGINT        NOT NULL AUTO_INCREMENT,
  city_code      VARCHAR(32)   NOT NULL,
  city_name      VARCHAR(64)   NOT NULL,
  insurance_type VARCHAR(32)   NOT NULL COMMENT 'PENSION/MEDICAL/UNEMPLOYMENT/HOUSING_FUND',
  personal_rate  DECIMAL(6,4)  NOT NULL,
  base_min       DECIMAL(18,2) NOT NULL,
  base_max       DECIMAL(18,2) NOT NULL,
  extra_amount   DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '固定附加，如医疗大病 3 元',
  effective_from DATE          NOT NULL,
  status         TINYINT       NOT NULL DEFAULT 1,
  PRIMARY KEY (id),
  UNIQUE KEY uk_city_type_from (city_code, insurance_type, effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='社保公积金参数';

CREATE TABLE emp_social_insurance (
  id                 BIGINT        NOT NULL AUTO_INCREMENT,
  employee_id        BIGINT        NOT NULL,
  city_code          VARCHAR(32)   NOT NULL,
  social_base        DECIMAL(18,2) NULL COMMENT 'NULL=按规则自动计算',
  housing_fund_rate  DECIMAL(6,4)  NULL COMMENT 'NULL=用城市默认',
  effective_from     DATE          NOT NULL,
  effective_to       DATE          NULL,
  created_by         BIGINT        NULL,
  created_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_emp_from (employee_id, effective_from),
  KEY idx_emp_range (employee_id, effective_from, effective_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工参保信息';

CREATE TABLE emp_special_deduction (
  id             BIGINT        NOT NULL AUTO_INCREMENT,
  employee_id    BIGINT        NOT NULL,
  deduction_type VARCHAR(32)   NOT NULL COMMENT 'CHILD_EDU/INFANT_CARE/CONTINUE_EDU/HOUSING_LOAN/HOUSING_RENT/ELDERLY_CARE',
  monthly_amount DECIMAL(18,2) NOT NULL,
  effective_from DATE          NOT NULL,
  effective_to   DATE          NULL,
  remark         VARCHAR(255)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_emp_type_from (employee_id, deduction_type, effective_from),
  KEY idx_emp_range (employee_id, effective_from, effective_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='专项附加扣除';

CREATE TABLE pay_ytd (
  id                    BIGINT        NOT NULL AUTO_INCREMENT,
  employee_id           BIGINT        NOT NULL,
  tax_year              INT           NOT NULL,
  company_id            BIGINT        NOT NULL,
  cum_income            DECIMAL(18,2) NOT NULL DEFAULT 0,
  cum_social            DECIMAL(18,2) NOT NULL DEFAULT 0,
  cum_fund              DECIMAL(18,2) NOT NULL DEFAULT 0,
  cum_special_deduction DECIMAL(18,2) NOT NULL DEFAULT 0,
  cum_tax               DECIMAL(18,2) NOT NULL DEFAULT 0,
  paid_months           INT           NOT NULL DEFAULT 0 COMMENT '本年度已计薪月份数（不含未锁定期间）',
  last_period_ym        CHAR(7)       NULL,
  updated_at            DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_emp_year (employee_id, tax_year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='个税年度累计台账';

-- -------------------------------------------------------------
-- 审计
-- -------------------------------------------------------------

CREATE TABLE pay_audit_log (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  company_id    BIGINT       NULL,
  biz_type      VARCHAR(32)  NOT NULL COMMENT 'RECORD/PERIOD/EMPLOYEE/IMPORT/EXPORT/VIEW/REPORT/ROLE/USER/DEPT/COMPANY/CONFIG',
  biz_id        BIGINT       NULL,
  action        VARCHAR(32)  NOT NULL COMMENT 'CREATE/UPDATE/DELETE/LOCK/UNLOCK/EXPORT/VIEW/GRANT_REPLACE',
  field_name    VARCHAR(64)  NULL,
  old_value     TEXT         NULL COMMENT '敏感值需加密后写入',
  new_value     TEXT         NULL,
  operator_id   BIGINT       NOT NULL,
  operator_name VARCHAR(64)  NOT NULL,
  ip            VARCHAR(45)  NULL,
  user_agent    VARCHAR(255) NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_biz (biz_type, biz_id),
  KEY idx_operator_time (operator_id, created_at),
  KEY idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审计日志（只增不改）';

-- -------------------------------------------------------------
-- 初始化数据
-- -------------------------------------------------------------

INSERT INTO sys_company (company_code, company_name) VALUES ('DEFAULT', '默认主体');

INSERT INTO pay_item (company_id, item_code, item_name, category, direction, data_type, precision_scale, include_in_gross, editable, importable, sort_no) VALUES
  (0, 'BASE_SALARY',   '基本工资',   'FIXED',    1, 'AMOUNT', 2, 1, 0, 0,  10),
  (0, 'POST_ALLOW',    '岗位津贴',   'FIXED',    1, 'AMOUNT', 2, 1, 0, 1,  20),
  (0, 'SENIORITY_PAY', '工龄工资',   'COMPUTED', 1, 'AMOUNT', 2, 1, 0, 0,  30),
  (0, 'PERF_BONUS',    '绩效奖金',   'VARIABLE', 1, 'AMOUNT', 2, 1, 1, 1,  40),
  (0, 'OT_ALLOW',      '加班工资',   'VARIABLE', 1, 'AMOUNT', 2, 1, 1, 1,  50),
  (0, 'OTHER_BONUS',   '其他奖金',   'VARIABLE', 1, 'AMOUNT', 2, 1, 1, 1,  55),
  (0, 'ATT_DEDUCT',    '考勤扣款',   'VARIABLE', 2, 'AMOUNT', 2, 1, 1, 1,  70),
  (0, 'OTHER_DEDUCT',  '其他扣款',   'VARIABLE', 2, 'AMOUNT', 2, 1, 1, 1,  75),
  (0, 'ADJ_ROUNDING',  '尾差调整',   'COMPUTED', 1, 'AMOUNT', 2, 1, 0, 0, 999),
  (0, 'SOCIAL_INS',    '社保个人',   'COMPUTED', 2, 'AMOUNT', 2, 0, 0, 0,  80),
  (0, 'HOUSING_FUND',  '公积金个人', 'COMPUTED', 2, 'AMOUNT', 2, 0, 0, 0,  82),
  (0, 'TAX',           '个人所得税', 'COMPUTED', 2, 'AMOUNT', 2, 0, 0, 0,  84);

-- 内置角色
-- SYS_ADMIN 是"全新安装后的第一个管理员"：没有它，部署完没有任何人能进入
-- 系统管理页创建用户/分配权限（先有管理员才能建管理员 = 死循环）。
-- 只授予 sys:*：薪酬专员不该能改用户和角色（三权分立）。
INSERT INTO sys_role (company_id, role_code, role_name, data_scope) VALUES
  (1, 'PAYROLL_CLERK',  '薪酬专员',     'ALL'),
  (1, 'PAYROLL_REVIEWER','薪酬复核',    'ALL'),
  (1, 'DEPT_MANAGER',   '部门主管',     'DEPT_SUB'),
  (1, 'EMPLOYEE',       '普通员工',     'SELF'),
  (1, 'SYS_ADMIN',      '系统管理员',   'ALL');

INSERT INTO sys_role_grant (role_id, perm_code) VALUES
  (1, 'payroll:record:view'), (1, 'payroll:record:edit'), (1, 'payroll:import:submit'), (1, 'payroll:export'),
  (1, 'payroll:sensitive:view'),
  (2, 'payroll:record:view'), (2, 'payroll:period:lock'), (2, 'payroll:period:unlock'), (2, 'payroll:export'),
  (3, 'payroll:record:view'),
  (4, 'payroll:record:view');

-- 系统管理员：全部 sys:* 权限点（含授予 ALL 数据范围的 sys:role:grant:all）
INSERT INTO sys_role_grant (role_id, perm_code) VALUES
  (5, 'sys:user:view'), (5, 'sys:user:edit'),
  (5, 'sys:role:view'), (5, 'sys:role:edit'), (5, 'sys:role:grant:all'),
  (5, 'sys:dept:edit'), (5, 'sys:company:edit'),
  (5, 'sys:config:view'), (5, 'sys:config:edit'), (5, 'sys:audit:view');

INSERT INTO pay_tax_bracket (year_min, year_max, rate, quick_deduction, sort_no) VALUES
  (0, 36000, 0.0300, 0, 10),
  (36000, 144000, 0.1000, 2520, 20),
  (144000, 300000, 0.2000, 16920, 30),
  (300000, 420000, 0.2500, 31920, 40),
  (420000, 660000, 0.3000, 52920, 50),
  (660000, 960000, 0.3500, 85920, 60),
  (960000, NULL, 0.4500, 181920, 70);

INSERT INTO pay_insurance_param
  (city_code, city_name, insurance_type, personal_rate, base_min, base_max, extra_amount, effective_from, status) VALUES
  ('BJ', '北京', 'PENSION',      0.0800, 6821, 35283, 0,    '2025-01-01', 1),
  ('BJ', '北京', 'MEDICAL',      0.0200, 6821, 35283, 3,    '2025-01-01', 1),
  ('BJ', '北京', 'UNEMPLOYMENT', 0.0050, 6821, 35283, 0,    '2025-01-01', 1),
  ('BJ', '北京', 'HOUSING_FUND', 0.1200, 2420, 35283, 0,    '2025-01-01', 1),
  ('SH', '上海', 'PENSION',      0.0800, 7384, 36921, 0,    '2025-01-01', 1),
  ('SH', '上海', 'MEDICAL',      0.0200, 7384, 36921, 0,    '2025-01-01', 1),
  ('SH', '上海', 'UNEMPLOYMENT', 0.0050, 7384, 36921, 0,    '2025-01-01', 1),
  ('SH', '上海', 'HOUSING_FUND', 0.0700, 2690, 36921, 0,    '2025-01-01', 1),
  ('SZ', '深圳', 'PENSION',      0.0800, 2360, 38892, 0,    '2025-01-01', 1),
  ('SZ', '深圳', 'MEDICAL',      0.0200, 2360, 38892, 0,    '2025-01-01', 1),
  ('SZ', '深圳', 'UNEMPLOYMENT', 0.0030, 2360, 38892, 0,    '2025-01-01', 1),
  ('SZ', '深圳', 'HOUSING_FUND', 0.0500, 2360, 38892, 0,    '2025-01-01', 1);

INSERT INTO sys_config (config_key, config_value, value_type, description, editable) VALUES
  ('payroll.monthly-payable-days', '21.75', 'DECIMAL',
   '月平均计薪天数：任职不足整月时的折算分母', 1),
  ('payroll.seniority-pay-per-year', '100', 'DECIMAL',
   '工龄工资：每满一年金额（元）', 1),
  ('payroll.calendar.holidays', '', 'DATE_LIST',
   '法定节假日（逗号分隔 ISO 日期）；为空时回退环境变量 PAYROLL_HOLIDAYS', 1),
  ('payroll.system-version', '1', 'TEXT',
   '系统版本号（只读，运维参数）', 0);
