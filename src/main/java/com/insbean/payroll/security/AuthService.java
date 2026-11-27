package com.insbean.payroll.security;

import com.insbean.payroll.audit.AuditService;
import com.insbean.payroll.common.BizException;
import com.insbean.payroll.security.dto.CurrentUserResponse;
import com.insbean.payroll.security.dto.LoginResponse;
import com.insbean.payroll.security.mapper.AuthMapper;
import com.insbean.payroll.security.mapper.AuthUserRow;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认证与账号操作。
 *
 * <p>从 Controller 里抽出来，是为了让"改密码 → 旧 token 失效"这条链路可测：
 * 它涉及三张表/两处状态（password_hash + token_version），必须有事务边界。
 */
@Service
public class AuthService {

    /** 登录失败限流：同一用户名 + IP 在 5 分钟内失败 5 次即暂时拒绝。 */
    private static final int MAX_LOGIN_FAILURES = 5;
    private static final Duration LOGIN_FAILURE_WINDOW = Duration.ofMinutes(5);

    /** 用户名不存在时也对它跑一次 bcrypt，避免用响应时间枚举账号。 */
    private static final String DUMMY_HASH =
            "$2a$10$pzrZzng3ntQtPyi3xD61jO1OVdmPerxACrcsKKsipKXgkuWp0mFKK";

    private final Map<String, Deque<Long>> loginFailures = new ConcurrentHashMap<>();

    private final AuthMapper authMapper;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public AuthService(AuthMapper authMapper,
                       TokenService tokenService,
                       PasswordEncoder passwordEncoder,
                       AuditService auditService) {
        this.authMapper = authMapper;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    public LoginResponse login(String username, String rawPassword) {
        String attemptKey = username + "|" + clientIp();
        if (isBlocked(attemptKey)) {
            throw new BizException(429, "登录尝试过于频繁，请稍后再试");
        }

        AuthUserRow user = authMapper.findByUsername(username);
        // 用户不存在时也对固定假 hash 跑一次 bcrypt：否则"不存在"会快一个数量级，
        // 攻击者足以据此枚举账号（实测 8.8ms vs 74ms）
        boolean passwordMatches = passwordEncoder.matches(rawPassword,
                user == null ? DUMMY_HASH : user.getPasswordHash());
        // 用户名不存在与密码错误返回同一条提示，避免账号枚举
        if (user == null || user.getStatus() == null || user.getStatus() != 1 || !passwordMatches) {
            registerFailure(attemptKey, username);
            throw new BizException(401, "用户名或密码错误");
        }
        loginFailures.remove(attemptKey);
        authMapper.touchLogin(user.getId());
        return new LoginResponse(tokenService.issue(user.getId()), toResponse(user));
    }

    private boolean isBlocked(String key) {
        Deque<Long> failures = loginFailures.get(key);
        if (failures == null) {
            return false;
        }
        synchronized (failures) {
            prune(failures);
            return failures.size() >= MAX_LOGIN_FAILURES;
        }
    }

    private void registerFailure(String key, String username) {
        Deque<Long> failures = loginFailures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (failures) {
            prune(failures);
            failures.addLast(System.currentTimeMillis());
            if (failures.size() == MAX_LOGIN_FAILURES) {
                // 触发限流是安全事件，留一条审计（操作人取当前上下文，登录失败时为 system）
                auditService.record("USER", null, "UPDATE", "login_blocked", null,
                        username + "@" + clientIp());
            }
        }
    }

    private static void prune(Deque<Long> failures) {
        long threshold = System.currentTimeMillis() - LOGIN_FAILURE_WINDOW.toMillis();
        while (!failures.isEmpty() && failures.peekFirst() < threshold) {
            failures.removeFirst();
        }
    }

    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
            return request.getRemoteAddr();
        }
        return "unknown";
    }

    public CurrentUserResponse currentUser() {
        CurrentUser current = CurrentUser.get();
        if (current == null) {
            throw new BizException(401, "未认证或登录已过期");
        }
        AuthUserRow user = authMapper.findById(current.userId());
        if (user == null) {
            throw new BizException(401, "未认证或登录已过期");
        }
        return new CurrentUserResponse(user.getId(), user.getUsername(), user.getRealName(),
                user.getCompanyId(), user.getEmployeeId(), current.dataScope(), current.permissions());
    }

    /**
     * 重置密码（管理员/本人改密都走这里）。
     *
     * <p>除了换 hash，还必须把 token_version +1：无状态 token 无法单独吊销，
     * 不 bump 的话攻击者手里的旧 token 在过期前仍然有效——改密码就等于没改。
     */
    @Transactional
    public void resetPassword(Long userId, String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new BizException(400, "新密码不能为空");
        }
        AuthUserRow user = authMapper.findById(userId);
        if (user == null) {
            throw new BizException(404, "用户不存在");
        }
        authMapper.updatePassword(userId, passwordEncoder.encode(rawPassword));
        authMapper.bumpTokenVersion(userId);
        // 审计只记"发生了改密"，不记密码本身
        auditService.record("USER", userId, "UPDATE", "password", null, "******");
    }

    private CurrentUserResponse toResponse(AuthUserRow user) {
        List<String> scopes = authMapper.findDataScopes(user.getId());
        return new CurrentUserResponse(
                user.getId(),
                user.getUsername(),
                user.getRealName(),
                user.getCompanyId(),
                user.getEmployeeId(),
                PermissionInterceptor.widestScope(scopes),
                new HashSet<>(authMapper.findPermissions(user.getId())));
    }
}
