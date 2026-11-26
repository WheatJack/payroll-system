package com.insbean.payroll.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insbean.payroll.admin.RoleRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface RoleAdminMapper extends BaseMapper<RoleRow> {

    @Select("SELECT COUNT(*) FROM sys_role WHERE company_id = #{companyId} AND role_code = #{roleCode} AND deleted = 0")
    int countByCode(@Param("companyId") Long companyId, @Param("roleCode") String roleCode);

    @Select("""
            SELECT COUNT(*) FROM sys_role
             WHERE company_id = #{companyId} AND role_code = #{roleCode} AND id <> #{excludeId} AND deleted = 0
            """)
    int countByCodeExcluding(@Param("companyId") Long companyId,
                             @Param("roleCode") String roleCode,
                             @Param("excludeId") Long excludeId);

    @Select("SELECT perm_code FROM sys_role_grant WHERE role_id = #{roleId} ORDER BY perm_code")
    List<String> findPermissions(@Param("roleId") Long roleId);

    @Select("""
            <script>
            SELECT role_id, perm_code FROM sys_role_grant
             WHERE role_id IN
               <foreach collection="roleIds" item="id" open="(" separator="," close=")">#{id}</foreach>
             ORDER BY role_id, perm_code
            </script>
            """)
    List<RolePermRow> findPermissionsByRoleIds(@Param("roleIds") List<Long> roleIds);

    @Delete("DELETE FROM sys_role_grant WHERE role_id = #{roleId}")
    int deleteGrants(@Param("roleId") Long roleId);

    @Insert("INSERT INTO sys_role_grant (role_id, perm_code) VALUES (#{roleId}, #{permCode})")
    int insertGrant(@Param("roleId") Long roleId, @Param("permCode") String permCode);

    /** 仍有用户关联该角色时禁止删除（契约 409）。 */
    @Select("""
            SELECT COUNT(*) FROM sys_user_role ur
              JOIN sys_user u ON u.id = ur.user_id AND u.deleted = 0
             WHERE ur.role_id = #{roleId}
            """)
    int countLinkedUsers(@Param("roleId") Long roleId);
}
