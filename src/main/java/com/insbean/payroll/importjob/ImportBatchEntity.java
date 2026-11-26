package com.insbean.payroll.importjob;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 导入批次（pay_import_batch）。本表无 deleted 列：批次是流水，不做软删除。 */
@Data
@TableName("pay_import_batch")
public class ImportBatchEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long periodId;
    /** FIXED / VARIABLE */
    private String bizType;
    private String fileName;
    /** SHA-256，同一期间同一文件不允许重复上传 */
    private String fileHash;
    private String filePath;
    /** UPSERT / APPEND / INSERT_ONLY */
    private String importMode;
    private Integer totalRows;
    private Integer successRows;
    private Integer failRows;
    /** VALIDATING / VALIDATED / COMMITTED / FAILED */
    private String status;
    private Long operatorId;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
}
