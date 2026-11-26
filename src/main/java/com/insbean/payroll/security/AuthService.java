package com.insbean.payroll.security;

import com.insbean.payroll.audit.AuditService;
import com.insbean.payroll.common.BizException;
import com.insbean.payroll.security.dto.CurrentUserResponse;
import com.insbean.payroll.security.dto.LoginResponse;
import com.insbean.payroll.security.mapper.AuthMapper;
import com.insbean.payroll.security.mapper.AuthUserRow;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;

/**
 * 认证与账号操作。
 *
 * <p>从 Controller 里抽出来，是为了让"改密码 → 旧 token 失效"这条链路可测：
 * 它涉及三张表/两处状态（password_hash + token_version），必须有事务边界。
 */
@Service
public class AuthService {

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
        AuthUserRow user = authMapper.findByUsername(username);
        // 用户名不存在与密码错误返回同一条提示，避免账号枚举
        if (user == null || user.getStatus() == null || user.getStatus() != 1
                || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new BizException(401, "用户名或密码错误");
        }
        authMapper.touchLogin(user.getId());
        return new LoginResponse(tokenService.issue(user.getId()), toResponse(user));
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
