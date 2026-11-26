package com.insbean.payroll.run.mapper;

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
}
