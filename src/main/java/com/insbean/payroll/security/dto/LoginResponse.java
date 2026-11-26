package com.insbean.payroll.security.dto;

/** 契约 components.schemas.LoginResponse。 */
public record LoginResponse(String token, CurrentUserResponse user) {
}
