package com.insbean.payroll;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insbean.payroll.employee.CompProfileService;
import com.insbean.payroll.employee.EmployeeService;
import com.insbean.payroll.employee.dto.CompProfileSaveReq;
import com.insbean.payroll.employee.dto.EmployeeSaveReq;
import com.insbean.payroll.period.PeriodService;
import com.insbean.payroll.period.dto.PeriodCreateReq;
import com.insbean.payroll.run.GenerateService;
import com.insbean.payroll.support.AbstractDbTest;
import com.insbean.payroll.support.TestDb;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 第二阶段端点的 HTTP 验收：系统管理自我保护、报表查询、二进制导出与导入模板。
 *
 * <p>走真实 MockMvc + 拦截器 + MySQL：权限点、统一响应包、二进制响应的
 * Content-Type/Content-Disposition 都要在这层确认，Service 单测覆盖不到。
 */
@AutoConfigureMockMvc
class SystemOpsFlowIT extends AbstractDbTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private CompProfileService compProfileService;

    @Autowired
    private PeriodService periodService;

    @Autowired
    private GenerateService generateService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void 系统管理需要权限点且不能停用或删除自己() throws Exception {
        String hr01 = login("hr01");
        // hr01 只有薪酬权限，没有 sys:user:view
        assertThat(mvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + hr01))
                .andReturn().getResponse().getStatus()).isEqualTo(403);

        // 授予系统管理权限点后同一个 token 即可访问（权限每次请求现查）
        jdbc.update("""
                INSERT INTO sys_role_grant (role_id, perm_code) VALUES
                  (1, 'sys:user:view'), (1, 'sys:user:edit'), (1, 'sys:role:view'),
                  (1, 'sys:role:edit'), (1, 'sys:role:grant:all')
                """);
        MvcResult users = mvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + hr01))
                .andReturn();
        assertThat(users.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(body(users)).path("data").path("total").asInt()).isEqualTo(3);

        // 停用自己 / 删除自己 → 409
        Map<String, Object> disableSelf = new LinkedHashMap<>();
        disableSelf.put("status", 0);
        assertThat(mvc.perform(put("/api/v1/users/1")
                        .header("Authorization", "Bearer " + hr01)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(disableSelf)))
                .andReturn().getResponse().getStatus()).isEqualTo(409);
        assertThat(mvc.perform(delete("/api/v1/users/1").header("Authorization", "Bearer " + hr01))
                .andReturn().getResponse().getStatus()).isEqualTo(409);

        // 重置他人密码后其旧 token 立即 401
        String hr02 = login("hr02");
        Map<String, Object> reset = Map.of("newPassword", "NewPassw0rd!");
        assertThat(mvc.perform(post("/api/v1/users/2/reset-password")
                        .header("Authorization", "Bearer " + hr01)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reset)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + hr02))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void 系统管理员可以读取部门与主体列表() throws Exception {
        // admin 只有 sys:*，没有 payroll:record:view——组织数据是登录即可读的公共基础数据
        String admin = login("admin");

        JsonNode depts = getJson("/api/v1/depts", admin, "companyId", "1");
        assertThat(depts.path("code").asInt()).isZero();
        assertThat(depts.path("data").size()).isPositive();

        JsonNode companies = getJson("/api/v1/companies", admin);
        assertThat(companies.path("code").asInt()).isZero();
        assertThat(companies.path("data").size()).isPositive();
    }

    @Test
    void 未登录读取组织数据返回401() throws Exception {
        assertThat(mvc.perform(get("/api/v1/depts").param("companyId", "1"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/api/v1/companies"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void 无部门编辑权限不能创建部门() throws Exception {
        String hr01 = login("hr01");
        Map<String, Object> body = Map.of(
                "companyId", "1", "deptCode", "NEW_DEPT", "deptName", "新部门");

        MvcResult result = mvc.perform(post("/api/v1/depts")
                        .header("Authorization", "Bearer " + hr01)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_dept WHERE dept_code = 'NEW_DEPT'", Integer.class)).isZero();
    }

    @Test
    void 全新种子下系统管理员可管理而薪酬专员仍被拒() throws Exception {
        // 全新安装后必须存在一个能进入系统管理页的账号，否则"先有管理员才能建管理员"是死循环
        String admin = login("admin");

        JsonNode users = getJson("/api/v1/users", admin);
        assertThat(users.path("code").asInt()).isZero();
        assertThat(users.path("data").path("total").asInt()).isEqualTo(3);

        JsonNode permissions = getJson("/api/v1/permissions", admin);
        assertThat(permissions.path("data").size()).isEqualTo(15);

        JsonNode roles = getJson("/api/v1/roles", admin);
        assertThat(roles.path("data").size()).isGreaterThanOrEqualTo(4);
        assertThat(roles.path("data").toString()).contains("SYS_ADMIN");

        // 三权分立：薪酬专员没有 sys:* 权限，仍然看不到用户管理
        String hr01 = login("hr01");
        assertThat(mvc.perform(get("/api/v1/users").header("Authorization", "Bearer " + hr01))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void 系统参数接口按权限与只读位保护() throws Exception {
        String admin = login("admin");

        JsonNode list = getJson("/api/v1/configs", admin);
        assertThat(list.path("data").size()).isEqualTo(4);

        MvcResult updated = mvc.perform(put("/api/v1/configs/payroll.seniority-pay-per-year")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"200\"}"))
                .andReturn();
        assertThat(updated.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(body(updated)).path("data").path("value").asText())
                .isEqualTo("200");

        // 只读参数 → 403
        assertThat(mvc.perform(put("/api/v1/configs/payroll.system-version")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"2\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(403);

        // 格式非法 → 400
        assertThat(mvc.perform(put("/api/v1/configs/payroll.seniority-pay-per-year")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"abc\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(400);

        // 薪酬专员没有 sys:config:edit → 403
        String hr01 = login("hr01");
        assertThat(mvc.perform(put("/api/v1/configs/payroll.seniority-pay-per-year")
                        .header("Authorization", "Bearer " + hr01)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"300\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void 系统管理员可给HR建号但不能给自己加薪酬角色() throws Exception {
        String admin = login("admin");

        // payroll 角色与 sys 是两条线：系统管理员可以给 HR 账号分配薪酬角色
        MvcResult assigned = mvc.perform(put("/api/v1/users/2")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleIds\":[\"1\"]}"))
                .andReturn();
        assertThat(assigned.getResponse().getStatus()).isEqualTo(200);
        JsonNode assignedBody = objectMapper.readTree(body(assigned));
        assertThat(assignedBody.path("data").path("roleIds").get(0).asText()).isEqualTo("1");

        // 但不能把薪酬角色挂到自己（admin 是 user 3，保留 SYS_ADMIN 角色 4）
        assertThat(mvc.perform(put("/api/v1/users/3")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleIds\":[\"1\",\"4\"]}"))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        // 自己仍然只有 SYS_ADMIN
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user_role WHERE user_id = 3 AND role_id = 1",
                Integer.class)).isZero();
    }

    @Test
    void 低权管理员不能降级或删除更高权账号() throws Exception {
        // hr01 只有 sys:user:edit 等权限，且数据范围被限为 COMPANY
        jdbc.update("""
                INSERT INTO sys_role_grant (role_id, perm_code) VALUES
                  (1, 'sys:user:view'), (1, 'sys:user:edit')
                """);
        jdbc.update("UPDATE sys_role SET data_scope = 'COMPANY' WHERE id = 1");
        String restricted = login("hr01");

        assertThat(mvc.perform(put("/api/v1/users/3")
                        .header("Authorization", "Bearer " + restricted)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"realName\":\"被降级\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(delete("/api/v1/users/3")
                        .header("Authorization", "Bearer " + restricted))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        // 更高权账号仍在
        assertThat(jdbc.queryForObject(
                "SELECT deleted FROM sys_user WHERE id = 3", Integer.class)).isZero();
    }

    @Test
    void 复核可解锁而编制人不能() throws Exception {
        long periodId = lockedPayroll();

        String compiler = login("hr01");
        assertThat(mvc.perform(post("/api/v1/periods/" + periodId + "/unlock")
                        .header("Authorization", "Bearer " + compiler)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"编制人无权解锁\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(403);

        String reviewer = login("hr02");
        MvcResult unlocked = mvc.perform(post("/api/v1/periods/" + periodId + "/unlock")
                        .header("Authorization", "Bearer " + reviewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"复核确认解锁\"}"))
                .andReturn();
        assertThat(unlocked.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(body(unlocked)).path("data").path("status").asText())
                .isEqualTo("EDITING");
    }

    @Test
    void SELF无关联员工时期间合计为零() throws Exception {
        long periodId = lockedPayroll();
        // hr02 换成 SELF 范围且不关联任何员工
        jdbc.update("DELETE FROM sys_user_role WHERE user_id = 2");
        jdbc.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (2, 3)");

        String self = login("hr02");
        JsonNode periods = getJson("/api/v1/periods", self);
        JsonNode target = null;
        for (JsonNode node : periods.path("data")) {
            if (node.path("id").asText().equals(String.valueOf(periodId))) {
                target = node;
            }
        }
        assertThat(target).isNotNull();
        assertThat(target.path("employeeCount").asInt()).isZero();
        assertThat(target.path("totalGross").asText()).isEqualTo("0.00");
        assertThat(target.path("totalNet").asText()).isEqualTo("0.00");
    }

    @Test
    void 不存在期间导出返回404() throws Exception {
        String reviewer = login("hr02");
        String payload = "{\"reportType\":\"%s\",\"params\":{\"periodYm\":\"2099-01\"},\"format\":\"XLSX\"}";
        for (String type : new String[]{"PERIOD_SHEET", "PAYSLIP"}) {
            MvcResult result = mvc.perform(post("/api/v1/reports/export")
                            .header("Authorization", "Bearer " + reviewer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(String.format(payload, type)))
                    .andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            assertThat(objectMapper.readTree(body(result)).path("code").asInt()).isEqualTo(404);
        }
    }

    @Test
    void 部门挂在不存在主体返回400() throws Exception {
        String admin = login("admin");
        MvcResult result = mvc.perform(post("/api/v1/depts")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyId\":\"999\",\"deptCode\":\"ORPHAN\",\"deptName\":\"孤儿部门\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_dept WHERE dept_code = 'ORPHAN'", Integer.class)).isZero();
    }

    @Test
    void 报表查询与导出走真实HTTP() throws Exception {
        long periodId = lockedPayroll();

        String reviewer = login("hr02");
        MvcResult monthly = mvc.perform(get("/api/v1/reports/monthly")
                        .param("periodYm", "2026-11")
                        .header("Authorization", "Bearer " + reviewer))
                .andReturn();
        assertThat(monthly.getResponse().getStatus()).isEqualTo(200);
        JsonNode monthlyBody = objectMapper.readTree(body(monthly));
        assertThat(monthlyBody.path("code").asInt()).isZero();
        assertThat(monthlyBody.path("data").size()).isEqualTo(1);
        assertThat(monthlyBody.path("data").get(0).path("grossAmount").asText()).isEqualTo("8600.00");

        // 年度 + 按部门但缺 companyId：ALL 账号会看到多主体 → 400
        MvcResult annual = mvc.perform(get("/api/v1/reports/annual")
                        .param("year", "2026").param("groupBy", "DEPT")
                        .header("Authorization", "Bearer " + reviewer))
                .andReturn();
        assertThat(annual.getResponse().getStatus()).isEqualTo(400);

        Map<String, Object> export = Map.of(
                "reportType", "MONTHLY",
                "params", Map.of("periodYm", "2026-11"),
                "format", "XLSX");
        MvcResult exported = mvc.perform(post("/api/v1/reports/export")
                        .header("Authorization", "Bearer " + reviewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(export)))
                .andReturn();
        MockHttpServletResponse response = exported.getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith(XLSX);
        assertThat(response.getHeader("Content-Disposition")).contains("monthly-2026-11").contains(".xlsx");
        // xlsx 是 zip 容器，魔数 PK
        assertThat(response.getContentAsByteArray()).startsWith(new byte[]{'P', 'K'});

        // 没有 payroll:export 的用户拿不到导出（hr01 也没有？它恰好有；用一个无导出权限的角色）
        String clerk = login("hr01");
        jdbc.update("DELETE FROM sys_role_grant WHERE role_id = 1 AND perm_code = 'payroll:export'");
        assertThat(mvc.perform(post("/api/v1/reports/export")
                        .header("Authorization", "Bearer " + clerk)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(export)))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void 导入模板走真实HTTP返回xlsx() throws Exception {
        long periodId = lockedPayroll();
        String clerk = login("hr01");

        MvcResult template = mvc.perform(get("/api/v1/import/template")
                        .param("periodId", String.valueOf(periodId))
                        .param("bizType", "FIXED")
                        .header("Authorization", "Bearer " + clerk))
                .andReturn();
        MockHttpServletResponse response = template.getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith(XLSX);
        assertThat(response.getHeader("Content-Disposition")).contains("import-template-FIXED");
        assertThat(response.getContentAsByteArray()).startsWith(new byte[]{'P', 'K'});
    }

    @Test
    void 部门树includeDisabled走真实HTTP() throws Exception {
        jdbc.update("""
                INSERT INTO sys_dept (id, company_id, parent_id, dept_code, dept_name, ancestors, sort_no, status)
                VALUES (30, 1, 0, 'OLD', '历史部门', '', 50, 0)
                """);
        String token = login("hr01");

        JsonNode defaults = getJson("/api/v1/depts", token, "companyId", "1");
        assertThat(deptIds(defaults)).contains("1").doesNotContain("30");

        JsonNode all = getJson("/api/v1/depts", token, "companyId", "1", "includeDisabled", "true");
        assertThat(deptIds(all)).contains("1", "30");
    }

    @Test
    void 导入validate_commit_errors走真实HTTP() throws Exception {
        long periodId = editablePayroll("E001", TestDb.DEPT_DEFAULT);
        String token = login("hr01");
        byte[] file = importFile("E001", 1500);

        MvcResult validated = mvc.perform(multipart("/api/v1/import/validate")
                        .file(new MockMultipartFile("file", "change.xlsx", XLSX, file))
                        .param("periodId", String.valueOf(periodId))
                        .param("bizType", "FIXED")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(validated.getResponse().getStatus()).isEqualTo(200);
        JsonNode validateBody = objectMapper.readTree(body(validated));
        assertThat(validateBody.path("data").path("successRows").asInt()).isEqualTo(1);
        String batchId = validateBody.path("data").path("batchId").asText();

        JsonNode batch = getJson("/api/v1/import/" + batchId, token);
        assertThat(batch.path("data").path("status").asText()).isEqualTo("VALIDATED");
        assertThat(batch.path("data").path("totalRows").asInt()).isEqualTo(1);

        MvcResult committed = mvc.perform(post("/api/v1/import/" + batchId + "/commit")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(committed.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(body(committed)).path("data").path("status").asText())
                .isEqualTo("COMMITTED");

        // 错误报告即使没有错误也返回合法 xlsx
        MvcResult errors = mvc.perform(get("/api/v1/import/" + batchId + "/errors")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(errors.getResponse().getStatus()).isEqualTo(200);
        assertThat(errors.getResponse().getContentType()).startsWith(XLSX);
        assertThat(errors.getResponse().getContentAsByteArray()).startsWith(new byte[]{'P', 'K'});

        // 同一文件重复上传：唯一键（period_id, biz_type, file_hash）→ 409
        MvcResult duplicate = mvc.perform(multipart("/api/v1/import/validate")
                        .file(new MockMultipartFile("file", "change.xlsx", XLSX, file))
                        .param("periodId", String.valueOf(periodId))
                        .param("bizType", "FIXED")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(duplicate.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void 导入提交失败返回409并带failRows() throws Exception {
        long periodId = editablePayroll("E001", TestDb.DEPT_DEFAULT);
        String token = login("hr01");

        MvcResult validated = mvc.perform(multipart("/api/v1/import/validate")
                        .file(new MockMultipartFile("file", "bad.xlsx", XLSX, importFile("E999", 100)))
                        .param("periodId", String.valueOf(periodId))
                        .param("bizType", "FIXED")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(validated.getResponse().getStatus()).isEqualTo(200);
        JsonNode validateBody = objectMapper.readTree(body(validated));
        assertThat(validateBody.path("data").path("failRows").asInt()).isEqualTo(1);
        String batchId = validateBody.path("data").path("batchId").asText();

        MvcResult committed = mvc.perform(post("/api/v1/import/" + batchId + "/commit")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(committed.getResponse().getStatus()).isEqualTo(409);
        JsonNode body = objectMapper.readTree(body(committed));
        assertThat(body.path("code").asInt()).isEqualTo(409);
        assertThat(body.path("data").path("batchId").asText()).isEqualTo(batchId);
        assertThat(body.path("data").path("failRows").asInt()).isEqualTo(1);
    }

    @Test
    void 导入越权返回403() throws Exception {
        // 部门 20 的员工，不在 DEPT_SUB(部门 1) 的子树里
        jdbc.update("INSERT INTO sys_dept (id, company_id, parent_id, dept_code, dept_name, ancestors, sort_no, status) VALUES (20, 1, 0, 'D020', '财务部', '', 20, 1)");
        long periodId = editablePayroll("E009", 20L);

        // hr02 改挂部门 1、只保留一个 DEPT_SUB 角色（仍持有 import:submit）
        jdbc.update("UPDATE sys_user SET dept_id = 1 WHERE id = 2");
        jdbc.update("INSERT INTO sys_role (id, company_id, role_code, role_name, data_scope, status) VALUES (9, 1, 'DEPT_LEAD', '部门主管', 'DEPT_SUB', 1)");
        jdbc.update("INSERT INTO sys_role_grant (role_id, perm_code) VALUES (9, 'payroll:import:submit'), (9, 'payroll:record:view')");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id = 2");
        jdbc.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (2, 9)");
        String token = login("hr02");

        MvcResult result = mvc.perform(multipart("/api/v1/import/validate")
                        .file(new MockMultipartFile("file", "other.xlsx", XLSX, importFile("E009", 100)))
                        .param("periodId", String.valueOf(periodId))
                        .param("bizType", "FIXED")
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void 导出准备失败返回JSON400() throws Exception {
        lockedPayroll();
        String token = login("hr02");
        Map<String, Object> export = Map.of(
                "reportType", "MONTHLY",
                "params", Map.of(),
                "format", "XLSX");
        MvcResult result = mvc.perform(post("/api/v1/reports/export")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(export)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(objectMapper.readTree(body(result)).path("code").asInt()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ 工具

    /** 可导入的期间：生成但不锁定（导入要求期间可写）。 */
    private long editablePayroll(String empNo, long deptId) {
        Long empId = employeeService.create(new EmployeeSaveReq(
                null, empNo, "员工" + empNo, 1, "ID_CARD",
                "1101011990" + String.format("%08d", Math.abs(empNo.hashCode()) % 100000000),
                null, "REGULAR", "P6", "工程师", deptId,
                LocalDate.of(2020, 3, 1), null, null, 1, null), TestDb.COMPANY_DEFAULT);
        compProfileService.save(new CompProfileSaveReq(null, empId, LocalDate.of(2020, 3, 1),
                new BigDecimal("8000"), "初始", java.util.List.of()));
        long periodId = periodService.create(new PeriodCreateReq(null, "2026-11"), TestDb.COMPANY_DEFAULT).id();
        generateService.startGenerate(periodId, 1L);
        return periodId;
    }

    /** 与真实模板一致：两行表头（名称 + itemCode），金额写成数值单元格。 */
    private static byte[] importFile(String empNo, double amount) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("data");
            Row titles = sheet.createRow(0);
            Row keys = sheet.createRow(1);
            titles.createCell(0).setCellValue("工号");
            titles.createCell(1).setCellValue("姓名");
            titles.createCell(2).setCellValue("岗位津贴");
            keys.createCell(0).setCellValue("empNo");
            keys.createCell(1).setCellValue("empName");
            keys.createCell(2).setCellValue("POST_ALLOW");
            Row data = sheet.createRow(2);
            data.createCell(0).setCellValue(empNo);
            data.createCell(1).setCellValue("员工");
            data.createCell(2).setCellValue(amount);
            workbook.write(out);
            return out.toByteArray();
        }
    }


    private JsonNode getJson(String path, String token, String... params) throws Exception {
        var request = get(path).header("Authorization", "Bearer " + token);
        for (int i = 0; i + 1 < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(body(result));
    }

    private static List<String> deptIds(JsonNode body) {
        List<String> ids = new java.util.ArrayList<>();
        body.path("data").forEach(node -> ids.add(node.path("id").asText()));
        return ids;
    }

    private long lockedPayroll() {
        Long empId = employeeService.create(new EmployeeSaveReq(
                null, "E001", "张三", 1, "ID_CARD", "110101199001011234", null,
                "REGULAR", "P6", "工程师", TestDb.DEPT_DEFAULT,
                LocalDate.of(2020, 3, 1), null, null, 1, null), TestDb.COMPANY_DEFAULT);
        compProfileService.save(new CompProfileSaveReq(null, empId, LocalDate.of(2020, 3, 1),
                new BigDecimal("8000"), "初始", java.util.List.of()));
        long periodId = periodService.create(new PeriodCreateReq(null, "2026-11"), TestDb.COMPANY_DEFAULT).id();
        generateService.startGenerate(periodId, 1L);
        periodService.lock(periodId, 2L);
        return periodId;
    }

    private String login(String username) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + TestDb.PASSWORD + "\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(body(result)).path("data").path("token").asText();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
