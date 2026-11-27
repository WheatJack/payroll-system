package com.insbean.payroll.record.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insbean.payroll.domain.entity.PayRecordItemEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface PayRecordItemMapper extends BaseMapper<PayRecordItemEntity> {

    @Select("""
            <script>
            SELECT * FROM pay_record_item
             WHERE record_id IN
               <foreach collection="recordIds" item="id" open="(" separator="," close=")">#{id}</foreach>
             ORDER BY record_id, sort_no, id
            </script>
            """)
    List<PayRecordItemEntity> findByRecordIds(@Param("recordIds") List<Long> recordIds);

    /** 年度核对报表用：一次取回指定员工全年所有 TAX 明细（按期间区分）。 */
    @Select("""
            <script>
            SELECT i.id, i.record_id, i.item_code, i.amount, r.employee_id, r.period_ym
              FROM pay_record_item i
              JOIN pay_record r ON r.id = i.record_id
             WHERE i.item_code = 'TAX' AND r.deleted = 0
               AND r.period_ym LIKE CONCAT(#{year}, '-%')
               AND r.employee_id IN
               <foreach collection="employeeIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<java.util.Map<String, Object>> findTaxByYearAndEmployees(
            @Param("year") String year, @Param("employeeIds") List<Long> employeeIds);
}
