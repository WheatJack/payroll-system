package com.insbean.payroll.importjob.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insbean.payroll.importjob.ImportErrorEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface ImportErrorMapper extends BaseMapper<ImportErrorEntity> {

    @Delete("DELETE FROM pay_import_error WHERE batch_id = #{batchId}")
    int deleteByBatch(@Param("batchId") Long batchId);
}
