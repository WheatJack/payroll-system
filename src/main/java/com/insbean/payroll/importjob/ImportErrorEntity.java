package com.insbean.payroll.importjob;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 导入错误行（pay_import_error），只保留前 5000 条。 */
@Data
@TableName("pay_import_error")
public class ImportErrorEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long batchId;
    /** Excel 行号（1 起） */
    private Integer rowNo;
    private String columnName;
    private String rawValue;
    private String errorCode;
    private String errorMsg;
}
