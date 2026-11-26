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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
        assertThat(objectMapper.readTree(body(users)).path("data").path("total").asInt()).isEqualTo(2);

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

    // ------------------------------------------------------------------ 工具

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
