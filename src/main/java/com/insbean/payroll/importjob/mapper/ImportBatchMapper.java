package com.insbean.payroll.importjob.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insbean.payroll.importjob.ImportBatchEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ImportBatchMapper extends BaseMapper<ImportBatchEntity> {

    /** 提交时锁批次行，防止双击提交并发写两遍。 */
    @Select("SELECT * FROM pay_import_batch WHERE id = #{id} FOR UPDATE")
    ImportBatchEntity selectForUpdate(@Param("id") Long id);
}
