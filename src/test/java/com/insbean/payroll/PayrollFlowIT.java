package com.insbean.payroll;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insbean.payroll.common.BizException;
import com.insbean.payroll.support.AbstractDbTest;
import com.insbean.payroll.support.TestDb;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 后端验收基线：走真实 HTTP 栈（MockMvc + 拦截器 + 真实 MySQL 3307 测试库），
 * 覆盖 Task 2–8 的全部接口，按财务的真实操作顺序跑一遍。
 *
 * <p>这里不 mock 任何东西：无状态 token、权限点、乐观锁、跨主体唯一索引、
 * 期间状态机都必须真的生效，否则这条链路就没有验收意义。
 */
@AutoConfigureMockMvc
class PayrollFlowIT extends AbstractDbTest {

    private static final String ID_NO = "110101199001011234";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void 完整链路_建人员_配薪_建期间_生成_改数_锁定() throws Exception {
        String token = login("hr01");
        long empId = createEmployee(token, "E001", ID_NO);
        createCompProfile(token, empId, "8000.00", LocalDate.of(2020, 3, 1));
        long periodId = createPeriod(token, "2026-11");
        waitRun(token, generate(token, periodId));

        JsonNode grid = grid(token, periodId);
        assertThat(grid.path("rows").size()).isEqualTo(1);
        assertThat(grid.path("total").asInt()).isEqualTo(1);
        assertThat(grid.path("rows").get(0).path("items").path("BASE_SALARY").asText()).isEqualTo("8000.00");
        assertThat(grid.path("rows").get(0).path("items").path("SENIORITY_PAY").asText()).isEqualTo("600.00");
        assertThat(grid.path("rows").get(0).path("grossAmount").asText()).isEqualTo("8600.00");

        applyChangeset(token, periodId, "PERF_BONUS", "2000.00");
        assertThat(grid(token, periodId).path("rows").get(0).path("items").path("PERF_BONUS").asText())
                .isEqualTo("2000.00");

        lock(token, periodId, "hr02");

        // 锁定后写入被拒（真实 HTTP 409 + 中文原因）
        assertThatThrownBy(() -> applyChangeset(token, periodId, "PERF_BONUS", "1.00"))
                .hasMessageContaining("已锁定");
    }

    @Test
    void 未登录访问被拒绝且登录后可取当前用户() throws Exception {
        MvcResult anonymous = mvc.perform(get("/api/v1/auth/me")).andReturn();
        assertThat(anonymous.getResponse().getStatus()).isEqualTo(401);
        assertThat(objectMapper.readTree(body(anonymous)).path("code").asInt()).isEqualTo(401);

        String token = login("hr01");
        JsonNode me = getJson("/api/v1/auth/me", token);
        assertThat(me.path("username").asText()).isEqualTo("hr01");
        assertThat(me.path("dataScope").asText()).isEqualTo("ALL");
        assertThat(me.path("permissions").toString()).contains("payroll:record:edit");
        // 明文证件号从不回显：详情接口只有掩码
        long empId = createEmployee(token, "E002", "110101199001015678");
        JsonNode employee = getJson("/api/v1/employees/" + empId, token);
        assertThat(employee.path("idNoMask").asText()).isEqualTo("****5678");
        assertThat(employee.toString()).doesNotContain("110101199001015678");
    }

    @Test
    void 缺少权限点的账号返回403() throws Exception {
        String clerkToken = login("hr01");
        long periodId = createPeriod(clerkToken, "2026-12");

        // hr02 是复核角色，只有 view/lock/export，没有 record:edit
        String reviewerToken = login("hr02");
        MvcResult result = mvc.perform(post("/api/v1/periods/" + periodId + "/generate")
                        .header("Authorization", "Bearer " + reviewerToken))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(objectMapper.readTree(body(result)).path("code").asInt()).isEqualTo(403);
    }

    @Test
    void 版本冲突通过HTTP返回409并带出冲突行() throws Exception {
        String token = login("hr01");
        long empId = createEmployee(token, "E001", ID_NO);
        createCompProfile(token, empId, "8000.00", LocalDate.of(2020, 3, 1));
        long periodId = createPeriod(token, "2026-11");
        waitRun(token, generate(token, periodId));

        JsonNode row = grid(token, periodId).path("rows").get(0);
        String recordId = row.path("recordId").asText();
        int version = row.path("version").asInt();

        // 第一次用 version=0 保存成功
        patchJson("/api/v1/periods/" + periodId + "/records", token,
                changeset(recordId, version, "PERF_BONUS", "1.00"));

        // 第二次仍用 version=0 → 409，且 data.conflicts 点名了那一行
        MvcResult conflict = mvc.perform(patch("/api/v1/periods/" + periodId + "/records")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                changeset(recordId, version, "PERF_BONUS", "2.00"))))
                .andReturn();

        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        JsonNode body = objectMapper.readTree(body(conflict));
        assertThat(body.path("code").asInt()).isEqualTo(409);
        assertThat(body.path("data").path("conflicts").get(0).path("recordId").asText()).isEqualTo(recordId);

        // 他人修改未被覆盖
        assertThat(grid(token, periodId).path("rows").get(0).path("items").path("PERF_BONUS").asText())
                .isEqualTo("1.00");
    }

    // ------------------------------------------------------------------ 客户端动作

    private String login(String username) throws Exception {
        JsonNode data = postJson("/api/v1/auth/login", null, Map.of(
                "username", username,
                "password", TestDb.PASSWORD));
        String token = data.path("token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private long createEmployee(String token, String empNo, String idNo) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("empNo", empNo);
        body.put("name", "张三");
        body.put("gender", 1);
        body.put("idType", "ID_CARD");
        body.put("idNo", idNo);
        body.put("mobile", "13800138000");
        body.put("employeeType", "REGULAR");
        body.put("jobLevel", "P6");
        body.put("positionName", "工程师");
        body.put("deptId", String.valueOf(TestDb.DEPT_DEFAULT));
        body.put("hireDate", "2020-03-01");
        body.put("status", 1);

        JsonNode data = postJson("/api/v1/employees", token, body);
        return Long.parseLong(data.path("id").asText());
    }

    private void createCompProfile(String token, long empId, String baseSalary, LocalDate from) throws Exception {
        postJson("/api/v1/comp-profiles", token, Map.of(
                "employeeId", String.valueOf(empId),
                "effectiveFrom", from.toString(),
                "baseSalary", baseSalary,
                "changeReason", "入职定薪",
                "items", List.of()));
    }

    private long createPeriod(String token, String periodYm) throws Exception {
        JsonNode data = postJson("/api/v1/periods", token, Map.of("periodYm", periodYm));
        return Long.parseLong(data.path("id").asText());
    }

    private long generate(String token, long periodId) throws Exception {
        JsonNode data = postJson("/api/v1/periods/" + periodId + "/generate", token, Map.of());
        return Long.parseLong(data.path("runId").asText());
    }

    private void waitRun(String token, long runId) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            JsonNode progress = getJson("/api/v1/runs/" + runId, token);
            String status = progress.path("status").asText();
            if ("SUCCESS".equals(status) || "FAILED".equals(status)) {
                assertThat(status).as("批次状态: " + progress).isEqualTo("SUCCESS");
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("核算批次在 5 秒内没有结束");
    }

    private JsonNode grid(String token, long periodId) throws Exception {
        return getJson("/api/v1/periods/" + periodId + "/grid", token);
    }

    /** 只提交一个单元格的改动；version 从 grid 现取，模拟前端编辑器的行为。 */
    private void applyChangeset(String token, long periodId, String itemCode, String amount) throws Exception {
        JsonNode row = grid(token, periodId).path("rows").get(0);
        patchJson("/api/v1/periods/" + periodId + "/records", token,
                changeset(row.path("recordId").asText(), row.path("version").asInt(), itemCode, amount));
    }

    private void lock(String token, long periodId, String reviewer) throws Exception {
        postJson("/api/v1/periods/" + periodId + "/lock", login(reviewer), Map.of());
    }

    private static Map<String, Object> changeset(String recordId, int version, String itemCode, String amount) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("recordId", recordId);
        change.put("version", version);
        change.put("items", List.of(Map.of("itemCode", itemCode, "amount", amount)));
        return Map.of("changes", List.of(change));
    }

    // ------------------------------------------------------------------ HTTP 管道

    private JsonNode getJson(String path, String token) throws Exception {
        return perform(get(path).header("Authorization", "Bearer " + token));
    }

    private JsonNode postJson(String path, String token, Object body) throws Exception {
        MockHttpServletRequestBuilder request = post(path).contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        request.content(objectMapper.writeValueAsString(body));
        return perform(request);
    }

    private JsonNode patchJson(String path, String token, Object body) throws Exception {
        MockHttpServletRequestBuilder request = patch(path).contentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        request.content(objectMapper.writeValueAsString(body));
        return perform(request);
    }

    /**
     * 解开统一响应包：成功返回 data，失败抛带中文消息的 BizException，
     * 这样断言读起来和调用 Service 一样直接，也能顺带校验 R8 的
     * "HTTP 状态码 == body.code"。
     */
    private JsonNode perform(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        MockHttpServletResponse response = result.getResponse();
        JsonNode root = objectMapper.readTree(body(result));
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            assertThat(response.getStatus())
                    .as("HTTP 状态码必须与 body.code 一致: " + root)
                    .isEqualTo(code);
            throw new BizException(code, root.path("msg").asText());
        }
        assertThat(response.getStatus()).isEqualTo(200);
        return root.path("data");
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
