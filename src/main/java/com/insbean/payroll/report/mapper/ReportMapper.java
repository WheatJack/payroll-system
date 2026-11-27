package com.insbean.payroll.report.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.insbean.payroll.domain.entity.PayRecordEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 报表聚合。
 *
 * <p>聚合条件下推数据库，权限条件由 {@code DataScopeEnforcer.applyRecordFilter} 直接拼进
 * {@code ${ew.customSqlSegment}}——报表是最容易被"先查全表再内存过滤"写坏的地方，
 * 一旦漏掉数据范围，SELF 用户打开报表页就能看到全公司合计。
 */
public interface ReportMapper {

    @Select("""
            SELECT company_id,
                   period_ym,
                   dept_id,
                   MAX(dept_name)              AS dept_name,
                   COUNT(DISTINCT employee_id) AS employee_count,
                   SUM(gross_amount)           AS gross_amount,
                   SUM(deduction_amount)       AS deduction_amount,
                   SUM(net_amount)             AS net_amount
              FROM pay_record ${ew.customSqlSegment}
            """)
    List<Map<String, Object>> monthly(@Param(Constants.WRAPPER) Wrapper<PayRecordEntity> wrapper);

    /** 按部门汇总年度：不同主体的同名部门靠 company_id 分组隔开。 */
    @Select("""
            SELECT company_id,
                   dept_id,
                   MAX(dept_name)              AS dept_name,
                   COUNT(DISTINCT employee_id) AS employee_count,
                   SUM(gross_amount)           AS gross_amount,
                   SUM(deduction_amount)       AS deduction_amount,
                   SUM(net_amount)             AS net_amount
              FROM pay_record ${ew.customSqlSegment}
            """)
    List<Map<String, Object>> annualByDept(@Param(Constants.WRAPPER) Wrapper<PayRecordEntity> wrapper);

    /**
     * 期间内"有明细但未锁定"的月份：报表只统计已锁定期间，这些月份会被排除。
     * 必须显式返回给前端提示，否则用户会把"少了 3500 万"当成系统算错。
     */
    @Select("""
            <script>
            SELECT DISTINCT p.period_ym
              FROM pay_period p
              JOIN pay_record r ON r.period_id = p.id AND r.deleted = 0
             WHERE p.status IN ('DRAFT', 'EDITING')
               AND p.period_ym &gt;= #{from} AND p.period_ym &lt;= #{to}
               <if test="companyId != null">AND p.company_id = #{companyId}</if>
             ORDER BY p.period_ym
            </script>
            """)
    List<String> excludedPeriods(@Param("from") String from,
                                 @Param("to") String to,
                                 @Param("companyId") Long companyId);

    /** 按人汇总年度：一个人跨月跨主体都归到同一行（pay_record 集团级唯一保证同月不重复）。 */
    @Select("""
            SELECT employee_id,
                   MAX(emp_no)                 AS emp_no,
                   MAX(emp_name)               AS emp_name,
                   COUNT(DISTINCT period_ym)   AS month_count,
                   COUNT(DISTINCT employee_id) AS employee_count,
                   SUM(gross_amount)           AS gross_amount,
                   SUM(deduction_amount)       AS deduction_amount,
                   SUM(net_amount)             AS net_amount
              FROM pay_record ${ew.customSqlSegment}
            """)
    List<Map<String, Object>> annualByEmployee(@Param(Constants.WRAPPER) Wrapper<PayRecordEntity> wrapper);
}
