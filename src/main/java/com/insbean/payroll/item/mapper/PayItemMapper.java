package com.insbean.payroll.item.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insbean.payroll.domain.entity.PayItemEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface PayItemMapper extends BaseMapper<PayItemEntity> {

    /**
     * 该科目是否已被任何一条工资明细使用过。
     * 用过之后不允许再改类别与方向，否则历史工资表的合计口径会被追溯篡改。
     */
    @Select("SELECT COUNT(*) FROM pay_record_item WHERE item_code = #{itemCode}")
    int countUsedRecords(@Param("itemCode") String itemCode);
}
