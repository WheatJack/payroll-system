# 工资核算系统

> 内网私有化部署 · 多法人主体 · 集团级防重复发薪 · 个税社保内置

## 系统简介

这是一套面向集团型企业的内网工资核算系统：各分公司（法人主体）分别编制本主体的月度工资表，集团层面统一维护人员主数据，并在数据库层用集团级唯一约束拦住「同一个人在同一个月份被两家公司各发一次」——这是多法人薪酬场景里最贵、也最难事后发现的事故。系统覆盖人员与薪酬档案、期间与核算生成、变动数据导入、工资表编制、个税与社保公积金计算、月/年度汇总导出和全链路审计；金额按财务口径存储与舍入，历史工资表在调薪后永不改写。审批流不进入系统，系统只负责「编制 → 锁定」，锁定与解锁都是独立高危权限并全程留痕。

当前为 MVP（`0.1.0-SNAPSHOT`），已在约 2000 人真实脱敏数据上完成三轮回归与多角色验收。

## 功能总览

| 模块 | 功能点 |
|---|---|
| **人员管理** | 集团级人员主数据（工号全局唯一）；姓名、性别、证件号、手机号、员工类型、职级、入职日期；证件号 AES-256-GCM 加密存储 + HMAC 盲索引查重；列表脱敏，按 `payroll:sensitive:view` 按需查看完整值；Excel 批量导入（预校验 + 逐行错误报告） |
| **薪酬档案** | 基本工资与固定津贴按生效区间版本化；调薪时间线；修改薪酬只在未来生效，历史工资表永不改写；基本工资独立建模，作为加班费/经济补偿的法定基数 |
| **工资核算** | 法人主体、部门树、工资期间管理；一键生成基础数据（幂等，可重复执行）；编制台动态列 + 批量编辑 + 合计行；批次进度与跳过名单；期间锁定/解锁；锁定人不得是编制人 |
| **变动数据** | 模板列头由工资项字典动态生成；上传后预校验、不落库；支持 `UPSERT`（覆盖）与 `INSERT_ONLY`（跳过）两种模式；错误报告定位到行号/列名/原因/原值；文件 hash 幂等，重复提交直接拒绝 |
| **重复发放防线** | L1 文件内重复、L2 本主体本期重复、L3 集团级跨主体同月重复；L3 由 `(employee_id, period_ym, active_key)` 唯一索引兜底；报错能指出「已在哪家分公司的工资表中」 |
| **权限体系** | 权限点 + 角色 + 数据范围（`ALL` / `COMPANY` / `DEPT_SUB` / `DEPT` / `SELF`）；编制人 ≠ 锁定人；导出与敏感字段查看需单独授权；查看他人工资写审计；所有校验在服务端复核，不依赖前端禁用 |
| **报表导出** | 月度汇总、年度汇总（按人/按部门）、年度个税核对、个人工资条；Excel 导出含法人主体/期间列与合计行；导出动作写审计 |
| **系统管理** | 法人主体、部门树、用户、角色与授权、工资项字典、系统参数（计薪天数、工龄单价、法定节假日）、审计流水 |
| **个税社保** | 个人所得税按累计预扣法计算；预扣率表可配置；社保/公积金按城市参数与缴费基数上下限计算；员工参保信息与专项附加扣除按生效区间维护；`pay_ytd` 年度累计台账；实发 = 应发 − 社保 − 公积金 − 个税 − 其他扣款 |

## 架构与设计

### 技术栈

| 层 | 技术 | 版本 |
|---|---|---|
| 后端 | Java | 17（必须） |
| 后端 | Spring Boot / Spring Security | 3.3.5 |
| 后端 | MyBatis-Plus | 3.5.7 |
| 后端 | MySQL | 8.0 |
| 后端 | EasyExcel（流式读写） | 3.3.4 |
| 后端 | springdoc-openapi | 2.5.0 |
| 前端 | Vue | 3.5 |
| 前端 | TypeScript / Vite | 5.6 / 6.0 |
| 前端 | Element Plus | 2.9 |
| 前端 | vxe-table（编制台虚拟滚动） | 4.9 |
| 前端 | Pinia / Vue Router | 2.3 / 4.5 |
| 前端 | decimal.js（金额运算） | 10.4 |
| 前端 | Vitest | 3.2 |
| 部署 | Docker Compose（MySQL 8，可选 MinIO） | — |

### 分层

```
┌──────────────────────────────────────────────────────┐
│  Vue 3 SPA（人员 / 薪酬档案 / 编制台 / 报表 / 系统管理） │
└───────────────────────┬──────────────────────────────┘
                        │ REST / JSON（统一前缀 /api/v1）
┌───────────────────────▼──────────────────────────────┐
│  Controller      参数校验、@RequirePerm 权限点、审计切面 │
├──────────────────────────────────────────────────────┤
│  Application     用例编排、事务边界、期间状态机、租约     │
├──────────────────────────────────────────────────────┤
│  Domain          计算引擎（纯函数，不依赖 Spring / 数据库）│
├──────────────────────────────────────────────────────┤
│  Infrastructure  Mapper / 加解密 / Excel / 文件存储      │
└──────────────────────────────────────────────────────┘
```

后端按业务能力纵向切包，每个功能包内自带 `Controller / Service / Mapper / dto`：

```
com.insbean.payroll
├── common        统一响应 Result{code,msg,data}、异常、分页、金额工具
├── security      登录、无状态 token、RBAC、数据范围拦截、字段加解密
├── org           法人主体、部门树
├── employee      人员主数据、薪酬档案（生效区间）
├── item          工资项字典（驱动整张工资表的列）
├── period        工资期间与状态机
├── run           核算批次、生成进度、任务租约
├── record        核算记录与明细、编制台 grid + changeset
├── importjob     变动数据导入、人员批量导入、模板与错误报告
├── report        月度/年度/个税报表与 Excel 导出
├── tax           个税、社保公积金、专项附加扣除、YTD 台账
├── admin         用户、角色、主体、部门、参数管理
├── setting       系统参数
├── audit         审计流水（只增不改）
├── calendar      工作日历
├── config        Jackson / Security / MyBatis-Plus 配置
└── domain        entity + payroll 纯计算引擎
```

### 四个关键设计决策

#### 1. 集团级唯一约束：防的是「跨主体重复发薪」

**问题**：工资期间是按法人主体拆的，两家分公司的 `period_id` 必然不同。唯一索引如果建在 `(employee_id, period_id)` 上，张三 2026-11 同时出现在北京和上海的工资表里时，数据库完全拦不住。

**方案**：把 `period_ym` 冗余进核算记录，用生成列把「有效行」映射成 1、「已删除行」映射成 NULL，再把唯一索引建在集团级的三列上：

```sql
period_ym  CHAR(7) NOT NULL,
active_key TINYINT AS (IF(deleted = 0, 1, NULL)) STORED,
UNIQUE KEY uk_emp_ym_active (employee_id, period_ym, active_key)
```

利用「NULL 不参与唯一约束」：有效行互相冲突，逻辑删除后的历史行互不干扰。

配套规则缺一不可：

- 批量生成**禁止** `ON DUPLICATE KEY UPDATE`。撞键意味着命中的是**别家公司**的记录，盲目 upsert 会把别家工资原地改写。正确路径是：先按 `(period_id, company_id)` 查本主体已有记录 → 按主键 UPDATE；缺失才 INSERT；INSERT 撞唯一键则抛异常。
- 捕获 `DuplicateKeyException` 后回查该员工同月有效记录，提示「张三(E001) 2026-11 已在『上海分公司』的工资表中」，而不是只报一句「重复」。
- 解锁更正同样走这三层校验，不能因为解锁就绕过。

#### 2. 薪酬档案按生效区间版本化：历史工资表永不改写

**问题**：需求要求固定项从人员数据自动带出，但基本工资会调。如果人员表只存「当前值」，9 月调薪后重算 8 月的工资表，8 月的数就会变——历史凭证被静默篡改。

**方案**：`emp_comp_profile` 一行代表一段生效区间：

```
employee_id | effective_from | effective_to | base_salary
1           | 2024-03-01     | 2025-06-30   | 8000
1           | 2025-07-01     | NULL         | 9500
```

取数条件固定为：`effective_from <= 期间末日 AND (effective_to IS NULL OR effective_to >= 期间末日)`。

界面上不暴露「版本」概念：人员详情页只显示当前生效薪酬和一条调薪时间线。用户点「修改」时，后端截断旧区间、新建一段，默认次月 1 日生效。操作体验是普通表单编辑，但任何历史期间重新生成，取到的都是当时生效的薪酬。

#### 3. 计算引擎纯函数化：金额算法可以脱离数据库测试

**问题**：薪酬计算一旦和 Spring 容器、Mapper、事务缠在一起，就只能靠起数据库做集成测试；金额规则（折算、工龄、舍入）恰恰是最需要高频、确定性验证的部分。

**方案**：`domain.payroll.PayrollCalculator` 不依赖 Spring、不碰数据库，输入 `EmployeePayContext`，输出 `PayrollResult`。规则全部收敛在纯函数里：

- 日工资 = 月基本工资 ÷ 21.75；
- 入职/离职当月按实际计薪天数折算，固定津贴同比折算；
- 工龄工资 = 工龄整年数 × 100 元/年；
- 应发合计 = Σ 增项，扣款合计 = Σ 减项，实发 = 应发 − 扣款。

这些规则由 `PayrollCalculatorTest` 的黄金用例钉死，改算法先改测试；应用层只负责取数、组装上下文和落库。

#### 4. 权限三层：功能点 + 数据范围 + 服务端硬校验

**问题**：薪酬系统的越权不是「能不能打开页面」，而是「部门主管能不能看到别的部门、普通员工能不能看到别人的数、低权管理员能不能给自己加角色」。

**方案**：三件事分开做，不合并成一个「管理员」角色。

1. **功能权限**：权限点 + 角色，例如 `payroll:record:view`、`payroll:record:edit`、`payroll:period:lock`、`payroll:period:unlock`、`payroll:import:submit`、`payroll:export`、`payroll:sensitive:view`，以及独立的 `sys:*` 管理权限。
2. **数据范围**：挂在角色上，生效时取最宽者——`ALL`（集团全部主体）、`COMPANY`（本公司）、`DEPT_SUB`（本部门及下级，按 `sys_dept.ancestors` 前缀匹配）、`DEPT`（本部门）、`SELF`（仅本人）。
3. **服务端硬校验**：编制人 ≠ 锁定人；导出必须单独授权并写审计；查看他人工资留痕；`SELF` 用户查询期间时合计按可见记录重算，不泄漏全公司金额；跨部门建人返回 403 且不落库。

前端隐藏按钮只是体验优化，所有写接口都会在服务端重新校验权限、数据范围和期间状态。

### 期间状态机

```
DRAFT ──生成──> EDITING ──锁定──> LOCKED ──归档──> ARCHIVED
                    ↑                │
                    └──── 解锁 ───────┘
                       （独立权限 + 必填原因 + 审计）
```

`LOCKED` / `ARCHIVED` 期间的任何写接口一律 409；状态校验和行锁都在服务端完成。当前 MVP 的 `ARCHIVED` 状态已建模但尚无归档入口（见「已知限制」）。

## 金额铁律

这套系统的核心不是功能多，而是金额在任何路径下都对得上。以下五条是硬约束，改代码前必须先读：

1. **存储精度**：金额一律 `DECIMAL(18,4)`，数量 `DECIMAL(12,2)`，DDL 层禁止 `DOUBLE` / `FLOAT`。
2. **传输格式**：接口中所有金额字段是**字符串**（如 `"8600.00"`），所有 `Long` 型 ID 也序列化为字符串；前端用 `decimal.js` 运算，禁止 `Number()` / `parseFloat` 处理金额。
3. **只舍入一次**：每个明细项落库前按各自 `precision_scale` 做一次 `HALF_UP`；中间结果（如日工资）保留足够位数，不在每一步反复舍入。
4. **合计 = 明细加总**：记录级合计直接 `SUM(明细)`，**不做二次舍入**。跨科目按比例分摊产生的尾差，生成一条 `ADJ_ROUNDING` 调整明细，绝不偷偷塞进某一项。
5. **展示口径**：界面统一展示 2 位小数；当展示合计与明细展示值相差 1 分钱时，以合计为准并标注差异。

回归基线中有一组可逐分复算的样例：E200 应发 `8600.00` → 社保 `893.76` / 公积金 `1018.00` / 个税 `50.65` / 实发 `6637.59`。

## 快速开始

### 环境要求

| 依赖 | 要求 |
|---|---|
| JDK | **17（必须）**。JDK 23+ 默认不执行 classpath 上的注解处理器，会导致 Lombok 静默失效 |
| Maven | 3.9+ |
| Node.js | 18+（建议 20 LTS） |
| Docker | Docker Engine + Compose v2 |
| 数据库 | MySQL 8.0（由 `docker-compose.yml` 启动） |

### 1. 准备环境变量

```bash
git clone https://github.com/WheatJack/payroll-system.git
cd payroll-system
cp .env.example .env
```

编辑 `.env`，至少填入：

- `MYSQL_ROOT_PASSWORD`、`MYSQL_PASSWORD`
- `PAYROLL_ID_SALT`、`PAYROLL_AES_KEY`、`PAYROLL_TOKEN_SECRET`（可用 `openssl rand -hex 32` 生成）

`.env` 已加入 `.gitignore`；`docker-compose.yml` 对必填项使用 `${VAR:?}`，缺失时直接报错退出，不提供默认口令。应用启动时同样强制要求这三个 payroll 密钥，缺失即拒绝启动。

> 注意：已运行的旧容器如显示 unhealthy，`docker compose up -d` 重建一次即可应用新版 healthcheck（healthcheck 口令跟随 `.env`）。

### 2. 启动数据库并导入演示数据

```bash
set -a; source .env; set +a
docker compose up -d

# 可选：仅开发/演示环境。创建「综合部」与三个登录账号
docker exec -i payroll-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" \
  --default-character-set=utf8mb4 payroll < db/seed-demo.sql
```

`db/schema-mysql.sql` 只会在数据卷为空时自动执行一次。需要重建：

```bash
docker compose down -v && docker compose up -d
```

MySQL 映射在 `127.0.0.1:3307`，只绑回环，不对局域网暴露（本机 `brew services` 的 mysql@8.0 占用 3306，不冲突）。

### 3. 启动后端

```bash
# macOS 示例；Linux 请指向本机 JDK 17
export JAVA_HOME=$(/usr/libexec/java_home -v 17)

set -a; source .env; set +a
mvn spring-boot:run
```

后端监听 `8080`。默认关闭 Swagger；本地需要接口文档时加 `--spring.profiles.active=dev`。

### 4. 启动前端

```bash
cd frontend
npm install
npm run dev
```

访问 <http://localhost:5174>。Vite 已把 `/api` 代理到 `http://localhost:8080`。

### 种子账号

| 账号 | 密码 | 角色 | 数据范围 |
|---|---|---|---|
| `hr01` | `Passw0rd!` | 薪酬专员（编制） | `ALL` |
| `hr02` | `Passw0rd!` | 薪酬复核（锁定/复核） | `ALL` |
| `admin` | `Passw0rd!` | 系统管理员（`sys:*`） | `ALL` |

> 以上仅用于开发与演示。生产部署必须删除演示账号并重置全部口令。

### 构建与测试

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)

# 后端：250 个单测 + 34 个端到端
# 注意 mvn test 不会跑 *IT，必须用 verify
mvn -B verify

# 前端：类型检查 + 181 个单测 + 构建
cd frontend
npm run type-check
npm run test
npm run build
```

本机若因 SOCKS 代理导致 Maven 下载失败，可在命令前加：

```bash
JAVA_TOOL_OPTIONS="-DsocksProxyHost= -Djava.net.useSystemProxies=false" mvn -B verify
```

### 端口

| 服务 | 地址 |
|---|---|
| 前端（Vite） | `http://localhost:5174` |
| 后端 | `http://localhost:8080` |
| MySQL | `127.0.0.1:3307`，库名 `payroll` |
| MinIO（可选 profile） | `9000`（API）/ `9001`（控制台） |

## 目录结构

```
payroll-system/
├── src/main/java/com/insbean/payroll/
│   ├── common/          统一响应、异常、分页、金额工具
│   ├── security/        登录、token、RBAC、数据范围、字段加解密
│   ├── org/             法人主体与部门树
│   ├── employee/        人员主数据、薪酬档案
│   ├── item/            工资项字典
│   ├── period/          工资期间与状态机
│   ├── run/             核算批次与生成任务
│   ├── record/          核算记录、编制台 grid/changeset
│   ├── importjob/       变动数据导入、人员导入
│   ├── report/          月度/年度/个税报表与导出
│   ├── tax/             个税、社保公积金、专项附加扣除、YTD
│   ├── admin/           用户、角色、主体、部门、参数管理
│   ├── setting/         系统参数
│   ├── audit/           审计流水
│   ├── calendar/        工作日历
│   ├── config/          Jackson / Security / MyBatis-Plus 配置
│   └── domain/          实体 + payroll 纯计算引擎
├── src/main/resources/  application.yml / application-dev.yml
├── src/test/            单元测试与端到端测试（PayrollFlowIT）
├── frontend/            Vue 3 SPA
│   └── src/
│       ├── api/         接口封装与 OpenAPI 生成类型
│       ├── views/       人员、薪酬档案、编制台、报表、系统管理页面
│       ├── components/  金额输入等通用组件
│       ├── composables/ 自动刷新、导入、退出等组合式逻辑
│       ├── stores/      Pinia 状态
│       ├── router/      路由与权限守卫
│       └── utils/       金额、变更集、导入、导出工具
├── db/
│   ├── schema-mysql.sql       25 张表 DDL + 内置角色 + 预扣率/社保参数
│   ├── seed-demo.sql          演示部门与账号（仅开发环境）
│   ├── m1-upgrade-2026-11-26.sql  升级与回填脚本
│   └── init-test-db.sh        测试库初始化
├── docs/                设计、个税社保、接口契约、评审与回归、待办
├── data/import/         导入原始文件默认落盘目录
├── docker-compose.yml   MySQL 8（MinIO 为可选 profile）
├── pom.xml              Spring Boot 3.3.5 / Java 17
└── .env.example         环境变量模板
```

## 文档导航

| 文档 | 内容 |
|---|---|
| [docs/详细设计.md](docs/详细设计.md) | 需求原文与追溯、表结构、重复校验、计算引擎、权限模型、接口清单、分期路线 |
| [docs/个税社保设计.md](docs/个税社保设计.md) | 累计预扣法、社保公积金参数、专项附加扣除、`pay_ytd` 累计台账 |
| [docs/api/openapi.yaml](docs/api/openapi.yaml) | 接口契约；前端可用 `npm run gen:types` 生成 TypeScript 类型 |
| [docs/评审汇总.md](docs/评审汇总.md) | 五个原始角色的评审汇总（31 P0 / 60 P1 / 56 P2） |
| [docs/review/](docs/review/) | 逐轮验收、渗透测试、代码审查、异常恢复、回归报告（01–25） |
| [docs/待办清单.md](docs/待办清单.md) | 本轮明确不做的项、二期范围与待决业务口径 |
| [docs/superpowers/plans/2026-11-26-payroll-mvp-parallel.md](docs/superpowers/plans/2026-11-26-payroll-mvp-parallel.md) | MVP 实施计划、并行轨道与里程碑 |
| 操作手册 / 运维手册 | 当前仓库未包含（属部署交付物） |

## 质量基线

| 项 | 结果 |
|---|---|
| 后端单元测试 | **250** |
| 后端端到端测试（`*IT`，`mvn -B verify`） | **34** |
| 前端单元测试（Vitest） | **181** |
| 接口级回归 | **24 / 24**（主回归 14 + 安全与异常 2 + 新功能 8） |
| 前端性能（1874 行编制台） | 首屏 11,529 ms → **193 ms**；DOM 349,557 → **2,307** |
| 浏览器实测（1821 行） | 首屏 376 ms，实际渲染 48 行，合计金额校验通过 |

验收方法不是只看单测：用约 2010 人的真实脱敏数据跑「生成 → 编制 → 导入 → 锁定 → 报表 → 解锁更正」全链路；由财务审计、安全渗透、后端/前端代码审查、异常恢复、部门主管、HR 总监、资深 HR 顾问、HR 专员分别演练；前两轮累计发现 27 + 40+ 个问题，修复后由控制方独立复跑，第三轮接口回归 24/24。

## 已知限制

如实列出，不做美化：

| 限制 | 影响 |
|---|---|
| **无员工自助**：没有工资条查询与签收确认 | 员工只能收到 HR 导出的文件，无法自助查看/确认 |
| **银行代发不做** | 系统输出工资表，不生成银行代发文件，打款仍需财务在银行侧操作 |
| **无补发/扣回单据** | 更正只能「解锁 → 修改 → 重新锁定」，旧版本不保留；跨期补发需线下处理 |
| **分析层未实现** | 团队薪酬趋势、人均成本、编制 vs 实际、环比、离职率、多维交叉报表均未提供 |
| **历史期间无个税台账** | 个税功能上线前锁定的期间没有 `pay_ytd`；年中升级的客户必须先录期初累计，否则个税从第 1 个月重新累计 |
| **`ARCHIVED` 状态不可达** | 建模了终态但没有归档入口，长期留存目前只能靠 `LOCKED` |
| **社保参数仅预置部分城市** | 当前种子只含北京、上海、深圳；其他城市需按当地基数/比例自行配置 |
| **导入的是金额，不是工时** | 系统不做「工时 × 时薪」反算；`quantity / unit_price` 字段为将来接考勤系统预留 |
| **审批流不在系统内** | 只有「编制 → 锁定」两个动作，审批走线下；系统不提供待办与审批节点 |
| **跨主体调动口径待业务确认** | 当前按期间末日归属主体整月归属，不做按天分割；同月调动需业务签字 |
| **部分业务规则仍偏粗** | 工龄工资对劳务/外包/实习/返聘全员照发；停薪留职照发全薪；月中调薪整月按新薪 |

## 路线图

| 阶段 | 主题 | 主要内容 |
|---|---|---|
| **一期（已交付）** | MVP | 多主体组织、人员与薪酬档案、期间与生成、编制台、金额导入、三层重复校验、解锁更正、权限与审计、月/年报表、个税社保公积金 |
| **二期** | 分析层 | 团队薪酬总额趋势、人均成本、编制 vs 实际、环比、离职率、按职级/岗位聚合、多维交叉、团队报表导出 |
| **三期** | 补发 / 扣回 | 补发单与扣回单（含源记录引用）、离职结算（未休年假、经济补偿、竞业限制、代通知金）、加班费规则、最低工资校验、归档动作 |
| **后续** | 社保参数与外部对接 | 更多城市社保/公积金参数与年度基数调整、员工自助与工资条签收、银行代发文件、考勤/绩效系统对接、跨主体调动单与按天分割 |

---

问题与建议请通过 [GitHub Issues](https://github.com/WheatJack/payroll-system/issues) 反馈。
