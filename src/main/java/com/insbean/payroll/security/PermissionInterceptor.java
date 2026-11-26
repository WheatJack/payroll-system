package com.insbean.payroll.security;

import com.insbean.payroll.common.BizException;
import com.insbean.payroll.security.mapper.AuthMapper;
import com.insbean.payroll.security.mapper.AuthUserRow;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 认证 + 功能权限拦截器。
 *
 * <p>职责边界刻意收窄：只负责"这个请求是谁发的、有没有这个权限点"。
 * 数据范围（ALL/COMPANY/DEPT/SELF）由各 Service 在查询条件里落实，
 * 内控约束（编制人 ≠ 锁定人）由 PeriodService 硬校验，都不放在这里。
 *
 * <p>失败时直接写响应体并返回 false：拦截器抛出的异常不会进入
 * {@code @RestControllerAdvice}，必须自己输出统一响应包。
 */
public class PermissionInterceptor implements AsyncHandlerInterceptor {

    private static final List<String> SCOPE_ORDER = List.of("ALL", "COMPANY", "DEPT_SUB", "DEPT", "SELF");

    private final TokenService tokenService;
    private final AuthMapper authMapper;

    public PermissionInterceptor(TokenService tokenService, AuthMapper authMapper) {
        this.tokenService = tokenService;
        this.authMapper = authMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        if (isPublic(handlerMethod)) {
            return true;
        }

        Long userId;
        try {
            userId = tokenService.parse(bearerToken(request));
        } catch (BizException e) {
            writeError(response, 401, e.getMessage());
            return false;
        }

        AuthUserRow user = authMapper.findById(userId);
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            writeError(response, 401, "未认证或登录已过期");
            return false;
        }

        Set<String> permissions = new HashSet<>(authMapper.findPermissions(userId));
        for (String perm : requiredPerms(handlerMethod)) {
            if (!permissions.contains(perm)) {
                writeError(response, 403, "无权限执行该操作");
                return false;
            }
        }

        CurrentUser.set(new CurrentUser(user.getId(), user.getCompanyId(), permissions,
                widestScope(authMapper.findDataScopes(userId)), user.getEmployeeId()));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        CurrentUser.clear();
    }

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response,
                                               Object handler) {
        CurrentUser.clear();
    }

    private static boolean isPublic(HandlerMethod handlerMethod) {
        return handlerMethod.getMethodAnnotation(PublicApi.class) != null
                || handlerMethod.getBeanType().isAnnotationPresent(PublicApi.class);
    }

    private static String[] requiredPerms(HandlerMethod handlerMethod) {
        RequirePerm onMethod = handlerMethod.getMethodAnnotation(RequirePerm.class);
        if (onMethod != null) {
            return onMethod.value();
        }
        RequirePerm onClass = handlerMethod.getBeanType().getAnnotation(RequirePerm.class);
        return onClass == null ? new String[0] : onClass.value();
    }

    /** 多角色时取最宽的数据范围；没有任何角色时退化为 SELF（最小权限）。 */
    static String widestScope(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return "SELF";
        }
        for (String candidate : SCOPE_ORDER) {
            if (scopes.contains(candidate)) {
                return candidate;
            }
        }
        return "SELF";
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null) {
            return null;
        }
        String prefix = "Bearer ";
        String trimmed = header.trim();
        return trimmed.regionMatches(true, 0, prefix, 0, prefix.length())
                ? trimmed.substring(prefix.length()).trim()
                : null;
    }

    private static void writeError(HttpServletResponse response, int code, String msg) throws IOException {
        response.setStatus(code);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":" + code + ",\"msg\":\"" + msg + "\",\"data\":null}");
    }
}
