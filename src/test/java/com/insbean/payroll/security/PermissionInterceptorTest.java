package com.insbean.payroll.security;

import com.insbean.payroll.security.mapper.AuthMapper;
import com.insbean.payroll.security.mapper.AuthUserRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权限拦截器单元测试：不连数据库，用内存版 AuthMapper 替代。
 */
class PermissionInterceptorTest {

    private static final String SECRET = "unit-test-token-secret";
    private static final long USER_ID = 1L;

    private TokenService tokenService;
    private FakeAuthMapper authMapper;
    private PermissionInterceptor interceptor;

    @BeforeEach
    void setUp() {
        authMapper = new FakeAuthMapper();
        tokenService = new TokenService(SECRET, 60, authMapper);
        interceptor = new PermissionInterceptor(tokenService, authMapper);
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    @Test
    void 缺少权限点时返回403() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(
                reqWithPerm("payroll:record:view"),
                resp,
                handlerRequire("payroll:period:lock")))
                .isFalse();
        assertThat(resp.getStatus()).isEqualTo(403);
        assertThat(CurrentUser.get()).isNull();
    }

    @Test
    void 拥有权限点时放行() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(
                reqWithPerm("payroll:record:view", "payroll:period:lock"),
                resp,
                handlerRequire("payroll:period:lock")))
                .isTrue();
        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(CurrentUser.get()).isNotNull();
        assertThat(CurrentUser.get().userId()).isEqualTo(USER_ID);
        assertThat(CurrentUser.get().permissions()).contains("payroll:period:lock");
    }

    @Test
    void 没有token时返回401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/periods/1/lock");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(req, resp, handlerRequire("payroll:period:lock"))).isFalse();
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    void 伪造token返回401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/periods/1/lock");
        req.addHeader("Authorization", "Bearer 这是伪造的token");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(req, resp, handlerRequire("payroll:period:lock"))).isFalse();
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    void 请求结束后清理ThreadLocal() throws Exception {
        MockHttpServletRequest req = reqWithPerm("payroll:period:lock");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        HandlerMethod handler = handlerRequire("payroll:period:lock");

        interceptor.preHandle(req, resp, handler);
        assertThat(CurrentUser.get()).isNotNull();

        interceptor.afterCompletion(req, resp, handler, null);
        assertThat(CurrentUser.get()).isNull();
    }

    // ------------------------------------------------------------------

    private MockHttpServletRequest reqWithPerm(String... perms) {
        authMapper.permissions = Set.of(perms);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/periods/1/lock");
        req.addHeader("Authorization", "Bearer " + tokenService.issue(USER_ID));
        return req;
    }

    private HandlerMethod handlerRequire(String perm) throws NoSuchMethodException {
        String methodName = switch (perm) {
            case "payroll:period:lock" -> "lock";
            case "payroll:record:view" -> "view";
            default -> throw new IllegalArgumentException("测试未覆盖的权限点: " + perm);
        };
        Method method = SampleHandler.class.getMethod(methodName);
        return new HandlerMethod(new SampleHandler(), method);
    }

    static class SampleHandler {

        @RequirePerm("payroll:record:view")
        public void view() {
        }

        @RequirePerm("payroll:period:lock")
        public void lock() {
        }
    }

    /** 内存版 AuthMapper，避免单元测试依赖数据库。 */
    static class FakeAuthMapper implements AuthMapper {

        Set<String> permissions = Set.of();
        int tokenVersion = 0;

        private AuthUserRow user() {
            AuthUserRow row = new AuthUserRow();
            row.setId(USER_ID);
            row.setCompanyId(1L);
            row.setUsername("hr01");
            row.setRealName("薪酬专员");
            row.setStatus(1);
            return row;
        }

        @Override
        public AuthUserRow findByUsername(String username) {
            return user();
        }

        @Override
        public AuthUserRow findById(Long id) {
            return user();
        }

        @Override
        public List<String> findPermissions(Long userId) {
            return permissions.stream().sorted().collect(Collectors.toList());
        }

        @Override
        public List<String> findDataScopes(Long userId) {
            return List.of("ALL");
        }

        @Override
        public Long findDeptId(Long id) {
            return 1L;
        }

        @Override
        public String findRealName(Long id) {
            return "薪酬专员";
        }

        @Override
        public Integer findTokenVersion(Long id) {
            return tokenVersion;
        }

        @Override
        public int bumpTokenVersion(Long id) {
            tokenVersion++;
            return 1;
        }

        @Override
        public int updatePassword(Long id, String passwordHash) {
            return 1;
        }

        @Override
        public void touchLogin(Long id) {
        }
    }
}
