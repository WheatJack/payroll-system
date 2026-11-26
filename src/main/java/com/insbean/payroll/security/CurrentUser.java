package com.insbean.payroll.security;

import java.util.Collections;
import java.util.Set;

/**
 * 已认证用户的上下文。
 *
 * <p>由 {@link PermissionInterceptor} 在 preHandle 阶段写入 ThreadLocal，
 * afterCompletion 阶段清理，避免线程池复用导致的身份串号。
 */
public record CurrentUser(
        Long userId,
        Long companyId,
        Set<String> permissions,
        String dataScope,
        Long employeeId
) {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    public CurrentUser {
        permissions = permissions == null ? Set.of() : Collections.unmodifiableSet(permissions);
    }

    public static CurrentUser get() {
        return HOLDER.get();
    }

    public static void set(CurrentUser user) {
        if (user == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(user);
        }
    }

    public static void clear() {
        HOLDER.remove();
    }

    public boolean has(String perm) {
        return permissions.contains(perm);
    }
}
