package com.insbean.payroll.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insbean.payroll.domain.entity.SysUserEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface UserAdminMapper extends BaseMapper<SysUserEntity> {

    @Select("SELECT COUNT(*) FROM sys_user WHERE username = #{username} AND deleted = 0")
    int countByUsername(@Param("username") String username);

    @Select("SELECT COUNT(*) FROM sys_user WHERE employee_id = #{employeeId} AND deleted = 0")
    int countByEmployeeId(@Param("employeeId") Long employeeId);

    @Select("SELECT COUNT(*) FROM sys_user WHERE employee_id = #{employeeId} AND id <> #{excludeId} AND deleted = 0")
    int countByEmployeeIdExcluding(@Param("employeeId") Long employeeId, @Param("excludeId") Long excludeId);

    @Select("SELECT role_id FROM sys_user_role WHERE user_id = #{userId} ORDER BY role_id")
    List<Long> findRoleIds(@Param("userId") Long userId);

    /** 分页列表一次取回全部关联，避免每行两次查询的 N+1。 */
    @Select("""
            <script>
            SELECT user_id, role_id FROM sys_user_role
             WHERE user_id IN
               <foreach collection="userIds" item="id" open="(" separator="," close=")">#{id}</foreach>
             ORDER BY user_id, role_id
            </script>
            """)
    List<UserRoleRow> findRoleLinks(@Param("userIds") List<Long> userIds);

    @Select("""
            <script>
            SELECT DISTINCT ur.user_id, g.perm_code
              FROM sys_user_role ur
              JOIN sys_role r       ON r.id = ur.role_id AND r.status = 1 AND r.deleted = 0
              JOIN sys_role_grant g ON g.role_id = r.id
             WHERE ur.user_id IN
               <foreach collection="userIds" item="id" open="(" separator="," close=")">#{id}</foreach>
             ORDER BY ur.user_id, g.perm_code
            </script>
            """)
    List<UserPermRow> findPermissions(@Param("userIds") List<Long> userIds);

    @Select("""
            <script>
            SELECT DISTINCT ur.user_id, r.data_scope
              FROM sys_user_role ur
              JOIN sys_role r ON r.id = ur.role_id AND r.status = 1 AND r.deleted = 0
             WHERE ur.user_id IN
               <foreach collection="userIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<UserScopeRow> findDataScopes(@Param("userIds") List<Long> userIds);

    /**
     * 当前仍可使用系统管理能力的账号数（启用 + 未删除 + 经启用角色持有 sys:role:edit）。
     * 任何会改变权限的写操作之后都要复查：归零意味着只能改库才能恢复。
     */
    @Select("""
            SELECT COUNT(DISTINCT u.id)
              FROM sys_user u
              JOIN sys_user_role ur ON ur.user_id = u.id
              JOIN sys_role r       ON r.id = ur.role_id AND r.status = 1 AND r.deleted = 0
              JOIN sys_role_grant g ON g.role_id = r.id AND g.perm_code = 'sys:role:edit'
             WHERE u.deleted = 0 AND u.status = 1
            """)
    int countActiveAdmins();

    @Update("DELETE FROM sys_user_role WHERE user_id = #{userId}")
    int deleteUserRoles(@Param("userId") Long userId);

    @Update("INSERT INTO sys_user_role (user_id, role_id) VALUES (#{userId}, #{roleId})")
    int insertUserRole(@Param("userId") Long userId, @Param("roleId") Long roleId);
}
