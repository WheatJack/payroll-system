package com.insbean.payroll.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记不需要登录即可访问的端点。默认所有 /api/** 端点都要求 Bearer token
 * （契约 §全局约定 8），只有登录接口例外。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PublicApi {
}
