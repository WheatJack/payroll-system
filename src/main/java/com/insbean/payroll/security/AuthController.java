package com.insbean.payroll.security;

import com.insbean.payroll.common.BizException;
import com.insbean.payroll.common.Result;
import com.insbean.payroll.security.dto.CurrentUserResponse;
import com.insbean.payroll.security.dto.LoginRequest;
import com.insbean.payroll.security.dto.LoginResponse;
import com.insbean.payroll.security.mapper.AuthMapper;
import com.insbean.payroll.security.mapper.AuthUserRow;
import jakarta.validation.Valid;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.List;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthMapper authMapper;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;

    public AuthController(AuthMapper authMapper, TokenService tokenService, PasswordEncoder passwordEncoder) {
        this.authMapper = authMapper;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
    }

    @PublicApi
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        AuthUserRow user = authMapper.findByUsername(req.username());
        // 用户名不存在与密码错误返回同一条提示，避免账号枚举
        if (user == null || user.getStatus() == null || user.getStatus() != 1
                || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new BizException(401, "用户名或密码错误");
        }
        authMapper.touchLogin(user.getId());
        String token = tokenService.issue(user.getId());
        return Result.ok(new LoginResponse(token, toResponse(user)));
    }

    @GetMapping("/me")
    public Result<CurrentUserResponse> me() {
        CurrentUser current = CurrentUser.get();
        if (current == null) {
            throw new BizException(401, "未认证或登录已过期");
        }
        AuthUserRow user = authMapper.findById(current.userId());
        if (user == null) {
            throw new BizException(401, "未认证或登录已过期");
        }
        return Result.ok(new CurrentUserResponse(
                user.getId(),
                user.getUsername(),
                user.getRealName(),
                user.getCompanyId(),
                user.getEmployeeId(),
                current.dataScope(),
                current.permissions()));
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
