package com.insbean.payroll.run;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 生成跳过明细（pay_run_skip）。pay_run.error_msg 会截断，完整名单落这张表。 */
@Data
@TableName("pay_run_skip")
public class PayRunSkipEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long runId;
    private String empNo;
    private String empName;
    private String reason;
}
