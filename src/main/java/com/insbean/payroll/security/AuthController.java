package com.insbean.payroll.security;

import com.insbean.payroll.common.Result;
import com.insbean.payroll.security.dto.CurrentUserResponse;
import com.insbean.payroll.security.dto.LoginRequest;
import com.insbean.payroll.security.dto.LoginResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PublicApi
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return Result.ok(authService.login(req.username(), req.password()));
    }

    @GetMapping("/me")
    public Result<CurrentUserResponse> me() {
        return Result.ok(authService.currentUser());
    }
}
