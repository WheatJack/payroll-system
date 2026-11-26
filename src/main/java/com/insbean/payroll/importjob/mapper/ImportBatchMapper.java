package com.insbean.payroll.importjob.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insbean.payroll.importjob.ImportBatchEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ImportBatchMapper extends BaseMapper<ImportBatchEntity> {

    /**
     * 同一期间 + 同一文件内容已经存在（任何非失败状态）即视为重复上传。
     * 失败批次不算：用户修不了文件内容，但坏文件重试没有意义，这里只拦"能提交的"。
     */
    @Select("""
            SELECT id FROM pay_import_batch
             WHERE period_id = #{periodId} AND file_hash = #{fileHash}
               AND status IN ('VALIDATING', 'VALIDATED', 'COMMITTED')
             LIMIT 1
            """)
    Long findDuplicate(@Param("periodId") Long periodId, @Param("fileHash") String fileHash);

    /** 提交时锁批次行，防止双击提交并发写两遍。 */
    @Select("SELECT * FROM pay_import_batch WHERE id = #{id} FOR UPDATE")
    ImportBatchEntity selectForUpdate(@Param("id") Long id);
}
