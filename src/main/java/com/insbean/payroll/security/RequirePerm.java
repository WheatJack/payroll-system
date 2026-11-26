package com.insbean.payroll.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明访问该处理器所需的权限点（见详细设计 §4.7）。
 * 标注在类上表示该 Controller 下所有方法都需要，方法上的声明优先。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePerm {

    /** 权限点编码，如 payroll:record:edit。多个时要求全部满足。 */
    String[] value();
}
