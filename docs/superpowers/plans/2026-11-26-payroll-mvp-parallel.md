# 工资核算系统 MVP 实施计划（前后端并行）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付一条可用的完整链路——建人员与薪酬 → 建工资期间 → 生成基础数据 → 在编制台改数 → 重复校验 → 锁定；前后端对着同一份接口契约并行开发。

**Architecture:** 单体模块化后端（Spring Boot 3.3）+ 独立 Vue 3 SPA。后端 `domain.payroll` 是纯计算层，已完成并用单元测试固化；服务层只负责取数、编排、落库。前端不写死任何工资项，列由接口返回的 `columns` 驱动。

**Tech Stack:** Java 17 / Spring Boot 3.3.5 / MyBatis-Plus 3.5.7 / MySQL 8 / Vue 3 + Vite + TypeScript / Element Plus / vxe-table v4 / axios / Pinia / decimal.js

**Spec:** `docs/详细设计.md`（v0.3，权威来源）+ `docs/评审汇总.md`（31 条 P0 及处置）

---

## Global Constraints

- 金额一律 `DECIMAL(18,4)` 存储；**接口中所有金额字段为字符串**（如 `"8600.00"`），前端用 decimal.js 运算，禁止 `Number()` / `parseFloat`
- 后端 `Long` 统一序列化为字符串（`JacksonConfig` 已实现），因此契约中所有 `id` 为 string
- 每个明细项只舍入一次；**合计 = 明细直接加总，不做二次舍入**
- 期间状态 `DRAFT | EDITING | LOCKED | ARCHIVED`；写操作必须在服务端校验状态，不能只靠前端禁用
- 同一人同一月份**只允许一条**有效核算记录（跨所有法人主体）
- 凭证只从环境变量读取，禁止写入代码或配置文件
- 构建必须用 JDK 17：`export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.15/libexec/openjdk.jdk/Contents/Home`
- 提交前必须通过：`mvn -B test`（后端）、`npm run type-check && npm run test`（前端）

## Review Focus

以下五类问题最容易在上线后咬人，且都不会被"正常路径"的测试覆盖，必须在对应任务里显式加测试：

1. **金额精度** —— 用户输入 `0.1 + 0.2` 类数值、合计与明细差一分钱。期望：任何操作后合计恒等于明细加总。
2. **并发编辑** —— 两人同时改同一期间的同一条记录。期望：后提交者收到 409 而不是静默覆盖。
3. **跨主体重复** —— 张三已在北京发过 2026-11，上海再生成。期望：报错能指出"在北京分公司"，而不是一句"重复"。
4. **锁定后写入** —— 已 `LOCKED` 或 `ARCHIVED` 的期间被直接调用接口修改。期望：409，且不产生任何数据变更。
5. **数据缺失** —— 员工无薪酬档案 / 无工龄 / 科目被停用。期望：生成时跳过并记录原因，不抛 NPE、不产生 0 元记录。

---

## 并行规则（两个轨道同时开工）

```
Task 1（契约冻结）  ← 唯一的前置依赖，必须先完成
        │
        ├── 轨道 A：后端 Task 2–9
        └── 轨道 B：前端 Task 10–17
                    │
                    └── Task 18：联调与验收
```

- **Task 1 完成前，两个轨道都不许动笔**。契约变更必须回到 Task 1 改，两边同步重新生成类型。
- 两个轨道**不得修改对方目录**：后端只动 `src/main/java`、`src/test`、`db/`；前端只动 `frontend/`。
- 前端在 Task 10 完成后即可用契约 mock 全量开发，不必等后端。
- 每个任务结束必须提交（commit），提交信息格式 `feat(backend): ...` / `feat(frontend): ...`。

---

## Task 1: 冻结接口契约

**Files:**
- Create: `docs/api/openapi.yaml`
- Create: `docs/api/examples/grid-response.json`
- Create: `docs/api/examples/changeset-request.json`

**Interfaces:**
- Consumes: `docs/详细设计.md` §5 接口清单
- Produces: 全部端点定义；后端的 Controller 与前端 `src/api/types.ts` 都由此生成

- [ ] **Step 1: 写 `openapi.yaml`，覆盖以下端点**

| 方法 | 路径 | 说明 | 里程碑 |
|---|---|---|---|
| POST | `/api/v1/auth/login` | `{username,password}` → `{token,user}` | M1 |
| GET | `/api/v1/auth/me` | 当前用户 + `permissions[]` + `dataScope` | M1 |
| GET | `/api/v1/companies` | 主体列表 | M1 |
| GET | `/api/v1/depts?companyId=` | 部门树（扁平，含 `ancestors`） | M1 |
| GET | `/api/v1/employees` | 分页；筛选 `keyword/deptId/employeeType/status` | M1 |
| GET | `/api/v1/employees/{id}` | 详情 | M1 |
| POST | `/api/v1/employees` | 新增 | M1 |
| PUT | `/api/v1/employees/{id}` | 修改 | M1 |
| GET | `/api/v1/employees/{id}/comp-profiles` | 薪酬档案时间线 | M1 |
| POST | `/api/v1/comp-profiles` | 新增生效区间 | M1 |
| PUT | `/api/v1/comp-profiles/{id}` | 修改（自动截断旧区间） | M1 |
| GET | `/api/v1/items` | 工资项字典 | M1 |
| POST/PUT | `/api/v1/items` `/api/v1/items/{id}` | 新增/修改 | M1 |
| GET | `/api/v1/periods` | 期间列表 | M1 |
| POST | `/api/v1/periods` | `{periodYm:"2026-11"}` | M1 |
| POST | `/api/v1/periods/{id}/generate` | → `{runId}`，异步 | M1 |
| GET | `/api/v1/runs/{runId}` | 进度 `{status,total,success,failed,errors[]}` | M1 |
| GET | `/api/v1/periods/{id}/grid` | **全量工资表**，见 Step 2 | M1 |
| PATCH | `/api/v1/periods/{id}/records` | **changeset 批量保存**，见 Step 3 | M1 |
| POST | `/api/v1/periods/{id}/lock` | 锁定 | M1 |
| POST | `/api/v1/periods/{id}/unlock` | 解锁，body 必填 `reason` | M1 |
| GET/POST | `/api/v1/import/...` | 模板/校验/提交/错误报告 | M2 |
| GET | `/api/v1/reports/monthly` `/annual` | 汇总 | M2 |

统一响应包：`{code:number, msg:string, data:T}`，`code=0` 为成功。
分页参数：`current`（从 1 开始）、`size`。

- [ ] **Step 2: 固化 grid 响应结构（写入 `examples/grid-response.json`）**

```json
{
  "code": 0,
  "data": {
    "period": { "id": "3", "periodYm": "2026-11", "status": "EDITING" },
    "columns": [
      { "itemCode": "BASE_SALARY", "itemName": "基本工资", "category": "FIXED", "direction": 1, "scale": 2, "editable": false },
      { "itemCode": "PERF_BONUS", "itemName": "绩效奖金", "category": "VARIABLE", "direction": 1, "scale": 2, "editable": true }
    ],
    "rows": [
      {
        "recordId": "101", "version": 0,
        "employeeId": "1", "empNo": "E001", "empName": "张三",
        "deptId": "1", "deptName": "北京研发部", "employeeType": "REGULAR", "jobLevel": "P6",
        "seniorityYears": "6.00",
        "grossAmount": "8600.00", "deductionAmount": "0.00", "netAmount": "8600.00",
        "items": { "BASE_SALARY": "8000.00", "SENIORITY_PAY": "600.00" }
      }
    ],
    "total": 1
  }
}
```

`items` 的键是 `itemCode`，值是**字符串金额**。前端据此渲染单元格，不依赖固定列。

- [ ] **Step 3: 固化 changeset 请求结构（写入 `examples/changeset-request.json`）**

```json
{
  "changes": [
    { "recordId": "101", "version": 0,
      "items": [ { "itemCode": "PERF_BONUS", "amount": "2000.00" } ] }
  ]
}
```

响应：成功返回受影响行；版本冲突返回 `code=409`，`data.conflicts[].recordId` 列出冲突项。

- [ ] **Step 4: 验证契约可被工具消费**

Run: `npx --yes openapi-typescript@7 docs/api/openapi.yaml -o /tmp/api-types.ts && head -5 /tmp/api-types.ts`
Expected: 生成成功且无 YAML 解析错误

- [ ] **Step 5: 提交**

```bash
git add docs/api && git commit -m "docs: freeze M1 API contract for parallel development"
```

---

## 轨道 A：后端（Task 2–9）

## Task 2: 认证与权限骨架

**Files:**
- Create: `src/main/java/com/insbean/payroll/security/AuthController.java`
- Create: `src/main/java/com/insbean/payroll/security/TokenService.java`
- Create: `src/main/java/com/insbean/payroll/security/PermissionInterceptor.java`
- Create: `src/main/java/com/insbean/payroll/security/CurrentUser.java`
- Create: `src/main/java/com/insbean/payroll/security/mapper/AuthMapper.java`
- Test: `src/test/java/com/insbean/payroll/security/PermissionInterceptorTest.java`

**Interfaces:**
- Consumes: `sys_user` / `sys_role` / `sys_user_role` / `sys_role_grant` 表
- Produces:
  - `CurrentUser get()` → `{Long userId, Long companyId, Set<String> permissions, String dataScope, Long employeeId}`
  - 注解 `@RequirePerm("payroll:record:edit")`，由拦截器校验
  - `POST /api/v1/auth/login`、`GET /api/v1/auth/me`

- [ ] **Step 1: 写失败测试**

```java
@Test
void 缺少权限点时返回403() {
    // 用户仅有 payroll:record:view，访问需要 payroll:period:lock 的处理器
    assertThat(interceptor.preHandle(reqWithPerm("payroll:record:view"), resp, handlerRequire("payroll:period:lock")))
            .isFalse();
    assertThat(resp.getStatus()).isEqualTo(403);
}

@Test
void 拥有权限点时放行() { ... }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -B test -Dtest=PermissionInterceptorTest`
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现 `TokenService`（HMAC 签名的无状态 token）与 `PermissionInterceptor`**

`TokenService` 暴露 `String issue(Long userId)` 与 `Long parse(String token)`；密钥从 `payroll.security.token-secret` 读，无默认值。
`PermissionInterceptor` 实现 `HandlerInterceptor`，解析 `Authorization: Bearer`，把权限塞进 `ThreadLocal`，在 `afterCompletion` 清理。

- [ ] **Step 4: 运行确认通过**

Run: `mvn -B test -Dtest=PermissionInterceptorTest`
Expected: PASS

- [ ] **Step 5: 实现 `AuthController`（login / me）并提交**

```bash
git add src/main/java/com/insbean/payroll/security src/test/java/com/insbean/payroll/security
git commit -m "feat(backend): auth and permission interceptor"
```

---

## Task 3: 组织与工资项字典

**Files:**
- Create: `org/{CompanyController,DeptController,DeptService}.java`、`org/mapper/{CompanyMapper,DeptMapper}.java`
- Create: `item/{ItemController,ItemService}.java`、`item/mapper/PayItemMapper.java`
- Create: `item/dto/PayItemDefDTO.java`
- Test: `src/test/java/com/insbean/payroll/item/ItemServiceTest.java`

**Interfaces:**
- Produces: `List<DeptDTO> tree(Long companyId)`；`List<PayItemDefDTO> listEnabled(Long companyId)`
- Produces: `Map<String, PayItemDef> toDomainDefs(Long companyId)` —— **供 Task 7 直接传给 `PayrollCalculator`**

- [ ] **Step 1: 写失败测试**

```java
@Test
void 科目字典转换为领域对象时保留方向与类别() {
    Map<String, PayItemDef> defs = service.toDomainDefs(1L);
    assertThat(defs.get("ATT_DEDUCT").direction()).isEqualTo(PayDirection.DECREASE);
    assertThat(defs.get("SENIORITY_PAY").category()).isEqualTo(PayCategory.COMPUTED);
}
```

- [ ] **Step 2: 运行确认失败** → `mvn -B test -Dtest=ItemServiceTest`，Expected: FAIL
- [ ] **Step 3: 实现 Mapper + Service + Controller**，科目查询需合并 `company_id = 0`（集团通用）与 `company_id = 本主体`
- [ ] **Step 4: 运行确认通过**
- [ ] **Step 5: 提交** `feat(backend): org and pay item dictionary`

---

## Task 4: 人员主数据（加密 + 盲索引）

**Files:**
- Create: `employee/{EmployeeController,EmployeeService}.java`
- Create: `employee/mapper/EmployeeMapper.java`
- Create: `employee/dto/{EmployeeDTO,EmployeeSaveReq,EmployeeQuery}.java`
- Create: `security/CryptoService.java`（AES-256-GCM 加解密 + HMAC-SHA256 盲索引）
- Test: `src/test/java/com/insbean/payroll/security/CryptoServiceTest.java`
- Test: `src/test/java/com/insbean/payroll/employee/EmployeeServiceTest.java`

**Interfaces:**
- Produces: `byte[] encrypt(String plain)` / `String decrypt(byte[] cipher)` / `String blindIndex(String plain)` —— **blindIndex 必须对输入做归一化**（去空格、证件号字母大写）
- Produces: `EmployeeDTO` 含 `idNoMask`、`mobileMask`，**不含明文**
- Produces: `POST/PUT/GET /api/v1/employees`

- [ ] **Step 1: 写失败测试（三条，对应 Review Focus 第 5 条与安全 P0-3）**

```java
@Test
void 同一证件号不同写法产生相同盲索引() {
    assertThat(crypto.blindIndex("11010119900101123x"))
            .isEqualTo(crypto.blindIndex(" 11010119900101123X "));
}

@Test
void 加解密可往返且密文每次不同() {
    byte[] a = crypto.encrypt("110101199001011234");
    byte[] b = crypto.encrypt("110101199001011234");
    assertThat(a).isNotEqualTo(b);                       // GCM 随机 IV
    assertThat(crypto.decrypt(a)).isEqualTo("110101199001011234");
}

@Test
void 接口返回的人员不含证件号明文() {
    EmployeeDTO dto = service.get(1L);
    assertThat(JsonPath.of(dto)).doesNotContain("110101199001011234");
}
```

- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 实现 `CryptoService`**

AES/GCM/NoPadding，12 字节随机 IV 前置拼接密文；密钥与盲索引密钥**分别**从 `PAYROLL_AES_KEY`、`PAYROLL_ID_SALT` 读取，缺失即启动失败。

- [ ] **Step 4: 运行确认通过** → `mvn -B test -Dtest=CryptoServiceTest`
- [ ] **Step 5: 实现 Service/Controller**：保存时先查 `blindIndex` 是否已存在，冲突抛 `BizException("该证件号码已存在人员记录")`
- [ ] **Step 6: 提交** `feat(backend): employee master data with encrypted PII`

---

## Task 5: 薪酬档案（生效区间）

**Files:**
- Create: `employee/{CompProfileController,CompProfileService}.java`
- Create: `employee/mapper/CompProfileMapper.java`
- Create: `employee/dto/{CompProfileDTO,CompProfileSaveReq}.java`
- Test: `src/test/java/com/insbean/payroll/employee/CompProfileServiceTest.java`

**Interfaces:**
- Produces: `CompProfileDTO effectiveAt(Long employeeId, LocalDate date)` —— **Task 7 生成时用它取薪酬**
- Produces: `POST/PUT /api/v1/comp-profiles`

- [ ] **Step 1: 写失败测试（对应 Review Focus 第 5 条）**

```java
@Test
void 调薪后旧区间被自动截断且不重叠() {
    service.save(req(empId, LocalDate.of(2026,12,1), new BigDecimal("9500")));
    List<CompProfileDTO> all = service.timeline(empId);
    assertThat(all).hasSize(2);
    assertThat(all.get(0).effectiveTo()).isEqualTo(LocalDate.of(2026,11,30));
    assertThat(all.get(1).effectiveFrom()).isEqualTo(LocalDate.of(2026,12,1));
}

@Test
void 查询某日生效的档案() {
    assertThat(service.effectiveAt(empId, LocalDate.of(2026,11,30)).baseSalary())
            .isEqualByComparingTo("8000");
}

@Test
void 无档案时返回空而不是抛异常() {
    assertThat(service.effectiveAt(999L, LocalDate.of(2026,11,30))).isNull();
}
```

- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 实现**：新增强调"截断上一条 + 插入新条"在一个事务内完成，并对区间重叠做应用层校验
- [ ] **Step 4: 运行确认通过**
- [ ] **Step 5: 提交** `feat(backend): effective-dated compensation profile`

---

## Task 6: 工资期间、状态机与任务租约

**Files:**
- Create: `period/{PeriodController,PeriodService}.java`
- Create: `period/mapper/PeriodMapper.java`
- Create: `period/PeriodStatus.java`（枚举）
- Create: `period/dto/{PeriodDTO,PeriodCreateReq}.java`
- Test: `src/test/java/com/insbean/payroll/period/PeriodServiceTest.java`

**Interfaces:**
- Produces: `void assertWritable(Long periodId)` —— **所有写接口必须先调用**
- Produces: `boolean tryAcquire(Long periodId, String owner, Duration ttl)` / `void release(...)`
- Produces: `POST/GET /api/v1/periods`、`POST /periods/{id}/lock|unlock`

- [ ] **Step 1: 写失败测试（对应 Review Focus 第 4 条）**

```java
@Test
void 已锁定期间拒绝写操作() {
    periodService.lock(periodId, userId);
    assertThatThrownBy(() -> periodService.assertWritable(periodId))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("已锁定");
}

@Test
void 租约被占用时第二个任务无法获取() {
    assertThat(periodService.tryAcquire(periodId, "task-1", Duration.ofMinutes(10))).isTrue();
    assertThat(periodService.tryAcquire(periodId, "task-2", Duration.ofMinutes(10))).isFalse();
}

@Test
void 租约过期后可被抢占() {
    periodService.tryAcquire(periodId, "task-1", Duration.ofMillis(1));
    Thread.sleep(5);
    assertThat(periodService.tryAcquire(periodId, "task-2", Duration.ofMinutes(10))).isTrue();
}
```

- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 实现**
  - 状态流转仅允许 `DRAFT→EDITING→LOCKED→ARCHIVED` 与 `LOCKED→EDITING`（解锁需 `reason`）
  - 锁定校验 `compiled_by != 当前用户`（C4 内控），违反抛 `BizException`
  - 租约用 `UPDATE pay_period SET processing_owner=?, processing_expire_at=? WHERE id=? AND (processing_owner IS NULL OR processing_expire_at < NOW())`，以 `affectedRows` 判定
- [ ] **Step 4: 运行确认通过**
- [ ] **Step 5: 提交** `feat(backend): pay period state machine and task lease`

---

## Task 7: 核算生成（幂等 + 三层重复校验）

**Files:**
- Create: `run/{GenerateService,RunController}.java`
- Create: `run/mapper/{PayRunMapper,PayRecordMapper,PayRecordItemMapper}.java`
- Create: `record/RecordAssembler.java`（领域结果 → 实体）
- Test: `src/test/java/com/insbean/payroll/run/GenerateServiceTest.java`

**Interfaces:**
- Consumes: `PayrollCalculator.calculate(EmployeePayContext) → PayrollResult`（已完成，见 `domain/payroll`）
- Consumes: `CompProfileService.effectiveAt(...)`、`ItemService.toDomainDefs(...)`
- Produces: `Long startGenerate(Long periodId, Long operatorId)` → runId；`RunProgressDTO progress(Long runId)`

- [ ] **Step 1: 写失败测试（对应 Review Focus 第 3、5 条）**

```java
@Test
void 重复生成不产生第二条记录() {
    Long run1 = service.startGenerate(periodId, op);
    Long run2 = service.startGenerate(periodId, op);
    assertThat(recordMapper.countActive(periodId, companyId)).isEqualTo(employeeCount);
}

@Test
void 跨主体同月重复时报错能指出是哪一家() {
    // 北京已生成 2026-11，上海再生成同一员工
    assertThatThrownBy(() -> service.startGenerate(shanghaiPeriodId, op))
            .isInstanceOf(BizException.class)
            .hasMessageContaining("北京");
}

@Test
void 无薪酬档案的员工被跳过并记录原因() {
    RunProgressDTO p = runAndWait(periodId);
    assertThat(p.skipped()).containsEntry("E999", "未配置薪酬档案");
    assertThat(recordMapper.countActive(periodId, companyId)).isEqualTo(employeeCount - 1);
}
```

- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 实现生成流程**

```
获取租约 → 写 pay_run(RUNNING) → 分批(500)：
  查本主体本期间已有记录（FOR UPDATE）
  对每个应生成员工：取薪酬档案 → 构造 EmployeePayContext → PayrollCalculator.calculate
    已有 → 按主键 UPDATE（version_no+1）
    无   → INSERT；捕获 DuplicateKeyException → 回查冲突记录所属主体 → 拼接错误信息
→ 写 pay_run(SUCCESS) → 释放租约
```

**禁止使用 `ON DUPLICATE KEY UPDATE`**（会把其他主体的记录静默改写）。

- [ ] **Step 4: 运行确认通过**
- [ ] **Step 5: 提交** `feat(backend): idempotent payroll generation with cross-company duplicate detection`

---

## Task 8: 编制台接口（grid + changeset）

**Files:**
- Create: `record/{RecordController,RecordService}.java`
- Create: `record/dto/{GridResponse,GridRow,GridColumn,ChangesetReq,ConflictDTO}.java`
- Test: `src/test/java/com/insbean/payroll/record/RecordServiceTest.java`

**Interfaces:**
- Produces: `GridResponse grid(Long periodId)` —— 结构见 Task 1 Step 2
- Produces: `ChangesetResult applyChangeset(Long periodId, ChangesetReq req)`

- [ ] **Step 1: 写失败测试（对应 Review Focus 第 1、2、4 条）**

```java
@Test
void 保存后应发与扣款分别等于对应方向的明细加总() {
    service.applyChangeset(periodId, oneChange("101", 0, Map.of("PERF_BONUS", "2000.00")));
    service.applyChangeset(periodId, oneChange("101", 1, Map.of("ATT_DEDUCT", "300.00")));

    GridResponse grid = service.grid(periodId);
    GridRow row = grid.rows().get(0);
    Map<String, Integer> dir = grid.columns().stream()
            .collect(Collectors.toMap(GridColumn::itemCode, GridColumn::direction));

    // 明细恒非负，必须按 direction 分别汇总；直接对全部 items 求和会把扣款项算进应发
    BigDecimal gross = sumByDirection(row, dir, 1);
    BigDecimal deduction = sumByDirection(row, dir, 2);

    assertThat(new BigDecimal(row.grossAmount())).isEqualByComparingTo(gross);
    assertThat(new BigDecimal(row.deductionAmount())).isEqualByComparingTo(deduction);
    assertThat(new BigDecimal(row.netAmount())).isEqualByComparingTo(gross.subtract(deduction));
}

@Test
void 版本冲突返回409且不覆盖他人修改() {
    service.applyChangeset(periodId, oneChange("101", 0, Map.of("PERF_BONUS", "1.00")));
    assertThatThrownBy(() -> service.applyChangeset(periodId, oneChange("101", 0, Map.of("PERF_BONUS", "2.00"))))
            .isInstanceOf(VersionConflictException.class);
}

@Test
void 锁定期间拒绝保存() {
    periodService.lock(periodId, otherUser);
    assertThatThrownBy(() -> service.applyChangeset(periodId, oneChange("101", 0, Map.of("PERF_BONUS", "1.00"))))
            .isInstanceOf(BizException.class);
}
```

- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 实现**

- `grid`：一次查询记录 + 一次查询全部明细，在内存组装 `Map<itemCode, String>`，避免 N+1
- `applyChangeset`：先 `assertWritable`；逐条按 `(recordId, version)` 乐观锁更新；任一冲突则整体失败并返回全部冲突项；成功后重算该条合计并 `version+1`
- **版本冲突必须抛 `VersionConflictException`**，由 `GlobalExceptionHandler` 映射为 **HTTP 409**，body 形如 `{code:409, msg:"数据已被他人修改", data:{conflicts:[{recordId:"101"}]}}`。前端据此高亮冲突行——不能只返回一个空 `Result<Void>`

- [ ] **Step 4: 运行确认通过**
- [ ] **Step 5: 提交** `feat(backend): payroll grid and changeset save`

---

## Task 9: 后端端到端集成测试

**Files:**
- Create: `src/test/resources/application-test.yml`
- Create: `src/test/java/com/insbean/payroll/PayrollFlowIT.java`

**Interfaces:**
- Consumes: 全部 Task 2–8 的接口
- Produces: 可重复执行的验收基线

- [ ] **Step 1: 写端到端测试**

```java
@Test
void 完整链路_建人员_配薪_建期间_生成_改数_锁定() {
    String token = login("hr01");
    long empId = createEmployee(token, "E001", "110101199001011234", "8000");
    createCompProfile(token, empId, "8000", LocalDate.of(2020, 3, 1));
    long periodId = createPeriod(token, "2026-11");
    waitRun(generate(token, periodId));
    GridResponse grid = grid(token, periodId);
    assertThat(grid.rows()).hasSize(1);
    assertThat(grid.rows().get(0).items().get("BASE_SALARY")).isEqualTo("8000.00");
    assertThat(grid.rows().get(0).items().get("SENIORITY_PAY")).isEqualTo("600.00");
    applyChangeset(token, periodId, "PERF_BONUS", "2000.00");
    lock(token, periodId, "hr02");
    // 锁定后写入被拒
    assertThatThrownBy(() -> applyChangeset(token, periodId, "PERF_BONUS", "1.00"))
            .hasMessageContaining("已锁定");
}
```

- [ ] **Step 2: 用 Testcontainers 或本地 3307 库运行，确认通过**

Run: `mvn -B test -Dtest=PayrollFlowIT`
Expected: PASS

- [ ] **Step 3: 提交** `test(backend): end-to-end payroll flow`

---

## 轨道 B：前端（Task 10–17）

## Task 10: 前端脚手架与基础设施

**Files:**
- Create: `frontend/package.json`、`vite.config.ts`、`tsconfig.json`、`index.html`
- Create: `frontend/src/main.ts`、`App.vue`
- Create: `frontend/src/api/client.ts`（axios 实例 + 统一响应解包 + 401 跳登录）
- Create: `frontend/src/api/types.ts`（由 `openapi.yaml` 生成，**不手写**）
- Create: `frontend/src/utils/money.ts`（decimal.js 封装）
- Create: `frontend/src/components/MoneyInput.vue`
- Test: `frontend/src/utils/__tests__/money.spec.ts`

**Interfaces:**
- Produces: `money.add(a,b)` / `money.sub` / `money.mul` / `money.round2` / `money.eq` / `money.isZero`，**全部接受并返回 string**
- Produces: `MoneyInput` 组件，`v-model` 为 string

- [ ] **Step 1: 写失败测试（对应 Review Focus 第 1 条）**

```ts
import { add, sub, eq, round2 } from '../money'

test('两位小数相加不产生浮点误差', () => {
  expect(add('0.10', '0.20')).toBe('0.30')
})

test('大数相加不丢精度', () => {
  expect(add('123456789012345.67', '0.01')).toBe('123456789012345.68')
})

test('合计等于明细加总', () => {
  const items = ['5517.24', '689.66', '600.00']
  const sum = items.reduce((a, b) => add(a, b), '0')
  expect(round2(sum)).toBe('6806.90')
})

test('浮点陷阱用例', () => {
  expect(eq(add('0.1', '0.2'), '0.3')).toBe(true)
  expect(sub('8600.00', '300.00')).toBe('8300.00')
})
```

- [ ] **Step 2: 运行确认失败** → `cd frontend && npm run test`，Expected: FAIL
- [ ] **Step 3: 实现 `money.ts`（decimal.js）+ `MoneyInput.vue`**

`MoneyInput` 规则：只允许数字与一个小数点；失焦时格式化为两位小数；`v-model` 始终是字符串；**内部禁止 `Number()`/`parseFloat`**。

- [ ] **Step 4: 运行确认通过**
- [ ] **Step 5: 生成类型并提交**

```bash
npx openapi-typescript ../../docs/api/openapi.yaml -o src/api/types.ts
npm run type-check
git add frontend && git commit -m "feat(frontend): scaffold, api client, money utilities"
```

---

## Task 11: 登录、权限路由与主布局

**Files:**
- Create: `frontend/src/views/LoginView.vue`
- Create: `frontend/src/stores/auth.ts`
- Create: `frontend/src/router/index.ts`
- Create: `frontend/src/layouts/MainLayout.vue`

**Interfaces:**
- Consumes: `POST /auth/login`、`GET /auth/me`
- Produces: `useAuthStore()` 含 `permissions: string[]`、`has(perm: string): boolean`
- Produces: 路由 `meta.perm`，导航守卫拦截

- [ ] **Step 1: 写失败测试**

```ts
test('没有权限点时 has 返回 false', () => {
  const store = useAuthStore()
  store.permissions = ['payroll:record:view']
  expect(store.has('payroll:period:lock')).toBe(false)
  expect(store.has('payroll:record:view')).toBe(true)
})
```

- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 实现登录页、Pinia store、路由守卫**；token 存 `localStorage`，401 自动登出
- [ ] **Step 4: 运行确认通过** → `npm run test && npm run type-check`
- [ ] **Step 5: 提交** `feat(frontend): auth, permission routing and layout`

---

## Task 12: 人员管理页

**Files:**
- Create: `frontend/src/views/employee/EmployeeListView.vue`
- Create: `frontend/src/views/employee/EmployeeFormDialog.vue`
- Create: `frontend/src/api/employee.ts`

**Interfaces:**
- Consumes: `GET/POST/PUT /employees`（类型来自 `types.ts`）

- [ ] **Step 1: 手工验收清单**（本任务无复杂逻辑，用验收清单代替单测）
  - 列表支持按姓名/工号模糊、部门、员工类型筛选
  - 证件号显示掩码，不可反查
  - 工号重复时展示后端返回的"工号已被占用"
  - 证件号重复时展示"该证件号码已存在人员记录"

- [ ] **Step 2: 实现并逐条核对清单**
- [ ] **Step 3: 提交** `feat(frontend): employee management`

---

## Task 13: 薪酬档案页（调薪时间线）

**Files:**
- Create: `frontend/src/views/employee/CompProfilePanel.vue`
- Create: `frontend/src/api/compProfile.ts`

**Interfaces:**
- Consumes: `GET /employees/{id}/comp-profiles`、`POST/PUT /comp-profiles`

- [ ] **Step 1: 手工验收清单**
  - 展示"当前生效薪酬"卡片 + 调薪历史时间线
  - 修改时默认生效日期为次月 1 日，可改
  - 保存成功旧区间自动显示为已结束
  - 金额输入走 `MoneyInput`

- [ ] **Step 2: 实现并逐条核对** → **Step 3: 提交** `feat(frontend): compensation profile timeline`

---

## Task 14: 工资项与期间管理页

**Files:**
- Create: `frontend/src/views/item/ItemListView.vue`
- Create: `frontend/src/views/period/PeriodListView.vue`
- Create: `frontend/src/api/{item,period}.ts`

- [ ] **Step 1: 手工验收清单**
  - 科目列表可拖拽排序（影响工资表列顺序）
  - 已使用的科目禁止修改类别与方向
  - 期间列表显示状态标签；`LOCKED` 行的"生成""解锁"按钮按权限显隐
  - 生成时显示进度（轮询 `GET /runs/{runId}` 直到 `SUCCESS/FAILED`）

- [ ] **Step 2: 实现并逐条核对** → **Step 3: 提交** `feat(frontend): pay items and period management`

---

## Task 15: vxe-table 编制台 PoC（**决策点**）

**Files:**
- Create: `frontend/src/views/period/PayrollGridPoC.vue`

**Interfaces:**
- Consumes: `GET /periods/{id}/grid` 的真实响应（或 mock 的 `examples/grid-response.json`）

- [ ] **Step 1: 用 500 行 × 12 个动态列跑通四项能力**
  1. 列头由 `columns` 动态生成
  2. 可编辑单元格（仅 `editable=true`）
  3. 底部合计行（用 `money.add` 计算，不用组件内置求和）
  4. 从 Excel 复制多行数据粘贴进选中区域

- [ ] **Step 2: 记录结论**

若四项均通过 → 采纳 vxe-table，进入 Task 16。
若任一不通过 → 停止并向发起人报告，附不通过项与截图，**不要自行换方案**。

- [ ] **Step 3: 提交** `feat(frontend): vxe-table poc for payroll grid`

---

## Task 16: 编制台正式页（dirty + changeset）

**Files:**
- Create: `frontend/src/views/period/PayrollGridView.vue`
- Create: `frontend/src/utils/changeset.ts`
- Create: `frontend/src/api/grid.ts`
- Test: `frontend/src/utils/__tests__/changeset.spec.ts`

**Interfaces:**
- Consumes: `GET /periods/{id}/grid`、`PATCH /periods/{id}/records`
- Produces: `buildChangeset(original, current): ChangesetReq`

- [ ] **Step 1: 写失败测试（对应 Review Focus 第 2 条）**

```ts
test('未修改的单元格不进入 changeset', () => {
  expect(buildChangeset({ a: '100.00' }, { a: '100.00' }).changes).toHaveLength(0)
})

test('修改后的单元格带上原始 version', () => {
  const r = buildChangeset({ a: '100.00' }, { a: '120.00' }, 7)
  expect(r.changes[0].version).toBe(7)
  expect(r.changes[0].items[0].amount).toBe('120.00')
})

test('未保存的编辑在切换筛选后仍然存在', () => {
  const store = useGridStore()
  store.edit('101', 'PERF_BONUS', '2000.00')
  store.setFilter({ deptId: '2' })
  store.clearFilter()
  expect(store.valueOf('101', 'PERF_BONUS')).toBe('2000.00')
})
```

- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 实现**：dirty 状态保存在 store 里，**与筛选视图完全解耦**；保存失败时保留 dirty 并高亮冲突行
- [ ] **Step 4: 运行确认通过**
- [ ] **Step 5: 提交** `feat(frontend): payroll grid with dirty state and changeset save`

---

## Task 17: 前端联调准备（mock 切换）

**Files:**
- Create: `frontend/src/api/mock/`（MSW handlers，响应取自 `docs/api/examples/`）
- Modify: `frontend/src/api/client.ts`

**Interfaces:**
- Consumes: Task 1 的全部示例响应

- [ ] **Step 1: 用 MSW 拦截全部 M1 端点，响应直接复用 `docs/api/examples/*.json`**
- [ ] **Step 2: 通过 `VITE_USE_MOCK=true` 切换**，默认关闭
- [ ] **Step 3: 验证**：`VITE_USE_MOCK=true npm run dev` 下可走完 登录 → 期间 → 编制台 → 保存
- [ ] **Step 4: 提交** `feat(frontend): msw mocks driven by frozen contract`

---

## Task 18: 联调验收

**Files:**
- Create: `docs/联调验收清单.md`

- [ ] **Step 1: 关闭 mock，前后端同时启动，逐条走 Task 1 的 M1 端点**
- [ ] **Step 2: 逐条验证 Review Focus 的五条**

| # | 场景 | 期望 |
|---|---|---|
| 1 | 在编制台输入 `0.1` 和 `0.2`，看合计 | 合计与单元格一致，无 `0.30000000000000004` |
| 2 | 两个浏览器同时改同一条并先后保存 | 后保存者收到"数据已被他人修改，请刷新" |
| 3 | 北京已发 2026-11，上海生成 | 报错含"北京"，不是泛泛的"重复" |
| 4 | 锁定后再调 PATCH 接口（用 curl 绕过前端） | 409，数据未变 |
| 5 | 建一个没有薪酬档案的员工后生成 | 跳过并列出原因，无 0 元记录、无报错中断 |

- [ ] **Step 3: 记录结果并提交** `docs: integration acceptance checklist`

---

## 里程碑

| 里程碑 | 完成标志 | 涉及任务 |
|---|---|---|
| M0 契约冻结 | `openapi.yaml` 可生成 TS 类型 | Task 1 |
| M1a 后端链路 | `PayrollFlowIT` 通过 | Task 2–9 |
| M1b 前端链路 | mock 下可走完编制流程 | Task 10–17 |
| M1 联调 | Task 18 五条全部通过 | 全部 |
| M2 | 导入管道、报表与导出 | 另行规划 |

---

# 第二阶段：需求 5 + 导入 + 系统管理（M1.5）

> 用户确认「三块一起补」。M1 已交付：人员、薪酬档案、期间、生成、编制台、重复检测。
> 本阶段补齐需求 3 的导入、需求 5 的查询与导出，以及系统自带的管理功能。

## 第二阶段约束（在 Global Constraints 之上追加）

- 汇总只统计 `LOCKED` 与 `ARCHIVED` 期间；`EDITING` 及之前不计入
- 查询与导出都必须按数据范围过滤：`SELF` 只能看本人，`DEPT`/`DEPT_SUB` 看本部门及下级，`ALL` 看全部
- 导出必须校验 `payroll:export` 权限，写 `pay_audit_log`（`action=EXPORT`），记录条件与行数
- 导入的模板列由 `pay_item` 动态生成，不得写死
- 系统管理新增权限点：`sys:user:*`、`sys:role:*`、`sys:dept:*`、`sys:company:*`
- 金额导出到 Excel 时写**文本格式**，避免 Excel 把 18 位数字转科学计数法

## Task 18: 扩展接口契约

**Files:**
- Modify: `docs/api/openapi.yaml`
- Create: `docs/api/examples/monthly-report.json`

**Interfaces:**
- Consumes: 现有 `/reports/monthly`、`/reports/annual`、`/import/*` 定义
- Produces: 新增 `POST /reports/export`、系统管理端点；所有新端点去掉 `x-milestone: M2`

- [ ] **Step 1: 新增 `POST /reports/export`**
  body `{reportType:"MONTHLY"|"ANNUAL", params:{...}, format:"XLSX"}` → `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` 二进制流，`Content-Disposition: attachment`
- [ ] **Step 2: 新增系统管理端点**（均为 M1.5）
  `GET/POST /users`、`PUT /users/{id}`、`POST /users/{id}/reset-password`、
  `GET /roles`、`POST /roles`、`PUT /roles/{id}`、`PUT /roles/{id}/grants`、
  `POST /depts`、`PUT /depts/{id}`、`GET/POST /companies`、`PUT /companies/{id}`
- [ ] **Step 3: 把 `/reports/*` 与 `/import/*` 的 `x-milestone` 从 M2 改为 M1.5**
- [ ] **Step 4: 验证** `npx --yes @redocly/cli@1 lint docs/api/openapi.yaml` 通过，`openapi-typescript` 能生成
- [ ] **Step 5: 提交** `docs: extend contract for reports, import and system admin`

## Task 19: 月度/年度汇总查询（后端）

**Files:**
- Create: `report/{ReportController,ReportService}.java`、`report/mapper/ReportMapper.java`
- Create: `report/dto/{MonthlyReportRow,AnnualReportRow,ReportQuery}.java`
- Test: `src/test/java/com/insbean/payroll/report/ReportServiceTest.java`

**Interfaces:**
- Consumes: `pay_summary_monthly`、`pay_record`、`DataScope` 当前用户上下文
- Produces: `List<MonthlyReportRow> monthly(ReportQuery)`、`List<AnnualReportRow> annual(ReportQuery)`

- [ ] **Step 1: 写失败测试**

```java
@Test
void 只统计已锁定期间() {
    // 2026-11 锁定、2026-12 仍在编辑
    assertThat(service.monthly(q("2026-12")).rows()).isEmpty();
}

@Test
void SELF范围只能看到本人() {
    assertThat(service.annual(q("2026").selfUser(empId)).rows()).hasSize(1);
}

@Test
void 年度按部门汇总的合计等于各月相加() { ... }
```

- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 实现**：月份过滤 `IN ('LOCKED','ARCHIVED')`；数据范围用 `data_scope` + 部门 `ancestors` 过滤
- [ ] **Step 4: 运行确认通过**
- [ ] **Step 5: 提交** `feat(backend): monthly and annual payroll reports`

## Task 20: 导出（后端）

**Files:**
- Create: `report/{ExportService,ExcelWriter}.java`
- Create: `audit/{AuditService,AuditMapper}.java`
- Test: `src/test/java/com/insbean/payroll/report/ExportServiceTest.java`

**Interfaces:**
- Consumes: Task 19 的查询
- Produces: `byte[] export(ReportQuery)`；写 `pay_audit_log`

- [ ] **Step 1: 写失败测试**

```java
@Test
void 无导出权限时抛403() {
    assertThatThrownBy(() -> service.export(q, userWithout("payroll:export")))
            .isInstanceOf(BizException.class);
}

@Test
void 导出写审计且金额为文本格式() {
    byte[] xlsx = service.export(q, clerk);
    assertThat(auditMapper.lastAction()).isEqualTo("EXPORT");
    assertThat(new String(xlsx)).doesNotContain("8.0E3"); // 不能被转成科学计数法
}
```

- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 实现**：EasyExcel 写出；金额列设 `@ExcelProperty` + 文本格式；文件名带期间与时间戳
- [ ] **Step 4: 运行确认通过**
- [ ] **Step 5: 提交** `feat(backend): report export with audit`

## Task 21: 导入管道（后端）

**Files:**
- Create: `importjob/{ImportController,ImportService,ImportValidator}.java`、`importjob/mapper/{ImportBatchMapper,ImportErrorMapper}.java`
- Create: `importjob/dto/{ImportPreview,ImportErrorRow}.java`
- Test: `src/test/java/com/insbean/payroll/importjob/ImportServiceTest.java`

**Interfaces:**
- Consumes: `pay_item`（动态列）、`pay_record`/`pay_record_item`
- Produces: `Long validate(MultipartFile)` → batchId（不落库）；`void commit(Long batchId)`；`byte[] errorReport(Long batchId)`

- [ ] **Step 1: 写失败测试**

```java
@Test
void 预校验不落库() {
    long batchId = service.validate(fileWith(3, "绩效"));
    assertThat(recordItemMapper.count()).isZero();
    assertThat(service.batch(batchId).successRows()).isEqualTo(3);
}

@Test
void 同一文件重复提交被拒绝() {
    service.validate(f);
    assertThatThrownBy(() -> service.validate(f)).isInstanceOf(BizException.class);
}

@Test
void 文件内重复工号被拒并列出冲突行() {
    ImportPreview p = service.validate(fileWithDup());
    assertThat(p.errors()).anyMatch(e -> e.msg().contains("第 2 行"));
}
```

- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 实现**：EasyExcel 流式读；按 `(batch_no, row_no)` 幂等；`UPSERT/APPEND/INSERT_ONLY` 三种模式
- [ ] **Step 4: 运行确认通过**
- [ ] **Step 5: 提交** `feat(backend): payroll change data import pipeline`

## Task 22: 系统管理接口（后端）

**Files:**
- Create: `security/{UserAdminController,RoleAdminController,UserAdminService,RoleAdminService}.java`
- Create: `org/{DeptAdminController,CompanyAdminController}.java`
- Test: `src/test/java/com/insbean/payroll/security/UserAdminServiceTest.java`

**Interfaces:**
- Produces: 用户 CRUD + 重置密码、角色 CRUD + 授权、部门 CRUD、主体 CRUD
- Produces: 新增权限点 `sys:user:*` 等，并写入 `sys_role_grant` 种子

- [ ] **Step 1: 写失败测试**

```java
@Test
void 无sys权限点不能管理用户() {
    assertThatThrownBy(() -> service.create(req, clerkUser))
            .isInstanceOf(BizException.class);
}

@Test
void 重置密码后旧密码失效新密码可用() { ... }

@Test
void 不能停用自己账号() { ... }
```

- [ ] **Step 2: 运行确认失败** → **Step 3: 实现** → **Step 4: 通过**
- [ ] **Step 5: 提交** `feat(backend): system administration endpoints`

## Task 23: 报表页（前端）

**Files:**
- Create: `frontend/src/views/report/{MonthlyReportView,AnnualReportView}.vue`
- Create: `frontend/src/api/report.ts`
- Test: `frontend/src/utils/__tests__/report.spec.ts`

- [ ] **Step 1: 手工验收清单**
  - 月度按部门汇总，年度可切「按人 / 按部门」
  - 金额全部走 `money` 工具，合计行按列累加
  - 无 `payroll:export` 权限时导出按钮不渲染
  - 导出触发浏览器下载，文件名含期间
  - `SELF` 用户进入报表页只看到自己一行

- [ ] **Step 2: 实现并逐条核对** → **Step 3: 提交** `feat(frontend): monthly and annual reports`

## Task 24: 导入向导（前端）

**Files:**
- Create: `frontend/src/views/period/ImportWizard.vue`
- Create: `frontend/src/api/import.ts`

- [ ] **Step 1: 手工验收清单**
  - 下载模板（列头由接口返回）
  - 上传 → 预校验 → 显示「成功 N / 失败 M」
  - 错误报告可下载
  - 部分失败时**禁止提交**（产品红线）
  - 同一文件重复上传被拒并给出提示

- [ ] **Step 2: 实现并逐条核对** → **Step 3: 提交** `feat(frontend): import wizard`

## Task 25: 系统管理页（前端）

**Files:**
- Create: `frontend/src/views/system/{UserListView,RoleListView,DeptTreeView,CompanyListView}.vue`
- Create: `frontend/src/api/system.ts`

- [ ] **Step 1: 手工验收清单**
  - 用户：新增/编辑/停用/重置密码
  - 角色：勾选权限点、设置数据范围
  - 部门：树形增删改
  - 无 `sys:*` 权限时菜单不显示

- [ ] **Step 2: 实现并逐条核对** → **Step 3: 提交** `feat(frontend): system administration pages`

## Task 26: 全量联调验收

- [ ] 关闭 mock，前后端同起
- [ ] 走通：建人员 → 配薪 → 建期间 → 生成 → 导入变动数据 → 编制台改数 → 锁定 → 月度报表 → 导出
- [ ] 逐条验证 M1 的五条 Review Focus，加上：SELF 用户看不到他人、导出文件金额未被转成科学计数法

