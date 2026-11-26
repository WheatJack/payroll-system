package com.insbean.payroll.security.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 认证与鉴权的数据访问。表结构见 db/schema-mysql.sql 的组织与权限部分。 */
public interface AuthMapper {

    @Select("""
            SELECT id, company_id, username, password_hash, real_name, employee_id, status
              FROM sys_user
             WHERE username = #{username} AND deleted = 0
            """)
    AuthUserRow findByUsername(@Param("username") String username);

    @Select("""
            SELECT id, company_id, username, real_name, employee_id, status
              FROM sys_user
             WHERE id = #{id} AND deleted = 0
            """)
    AuthUserRow findById(@Param("id") Long id);

    /** 用户通过角色拿到的全部权限点。 */
    @Select("""
            SELECT DISTINCT g.perm_code
              FROM sys_user_role ur
              JOIN sys_role r       ON r.id = ur.role_id AND r.status = 1 AND r.deleted = 0
              JOIN sys_role_grant g ON g.role_id = r.id
             WHERE ur.user_id = #{userId}
            """)
    List<String> findPermissions(@Param("userId") Long userId);

    /** 用户所有角色的数据范围，生效时取最宽者。 */
    @Select("""
            SELECT DISTINCT r.data_scope
              FROM sys_user_role ur
              JOIN sys_role r ON r.id = ur.role_id AND r.status = 1 AND r.deleted = 0
             WHERE ur.user_id = #{userId}
            """)
    List<String> findDataScopes(@Param("userId") Long userId);

    @Update("UPDATE sys_user SET last_login_at = NOW() WHERE id = #{id}")
    void touchLogin(@Param("id") Long id);
}
