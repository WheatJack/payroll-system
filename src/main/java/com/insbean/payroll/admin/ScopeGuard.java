package com.insbean.payroll.admin;

import com.insbean.payroll.common.BizException;
import com.insbean.payroll.security.CurrentUser;

import java.util.Collection;
import java.util.Set;

/**
 * 防自我提权（契约 PUT /roles/{id}、PUT /roles/{id}/grants）。
 *
 * <p>系统管理接口最大的风险不是"没权限的人进不来"，而是"有半截权限的人把权限给自己加满"。
 * 因此三条不变量必须由服务端硬校验，前端隐藏控件只是体验优化：
 * <ul>
 *   <li>新数据范围不得宽于操作者自身；</li>
 *   <li>把范围改成 {@code ALL} 需要额外持有 {@code sys:role:grant:all}；</li>
 *   <li>授予/分配的权限点必须是操作者自身权限点的子集。</li>
 * </ul>
 */
public final class ScopeGuard {

    /** 范围宽度：数值越大看得越多。 */
    public static final String PERM_GRANT_ALL = "sys:role:grant:all";

    private ScopeGuard() {
    }

    public static int rank(String scope) {
        return switch (scope == null ? "" : scope) {
            case "ALL" -> 5;
            case "COMPANY" -> 4;
            case "DEPT_SUB" -> 3;
            case "DEPT" -> 2;
            case "SELF" -> 1;
            default -> 0;
        };
    }

    /** 请求的数据范围不得宽于操作者当前的数据范围。 */
    public static void assertScopeNotWider(String requested, CurrentUser current) {
        if (rank(requested) > rank(current.dataScope())) {
            throw new BizException(403, "不能授予超出自身的数据范围：" + requested);
        }
    }

    /** 把范围改成 ALL 需要 sys:role:grant:all；已经是 ALL 的角色改其它字段不受影响。 */
    public static void assertAllGrantAllowed(String requested, String existing, CurrentUser current) {
        boolean changedToAll = "ALL".equals(requested) && !"ALL".equals(existing);
        if (changedToAll && !current.has(PERM_GRANT_ALL)) {
            throw new BizException(403, "授予集团数据范围需要 sys:role:grant:all 权限点");
        }
    }

    /** 授予的权限点必须是操作者自身权限点的子集。 */
    public static void assertPermissionsWithinOperator(Collection<String> requested, CurrentUser current) {
        if (requested == null || requested.isEmpty()) {
            return;
        }
        Set<String> own = current.permissions();
        for (String code : requested) {
            if (!own.contains(code)) {
                throw new BizException(403, "不能授予自己未持有的权限点：" + code);
            }
        }
    }
}
