package com.insbean.payroll.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pay_run")
public class PayRunEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long periodId;
    private String runNo;
    /** INIT / RECALC */
    private String runType;
    /** RUNNING / SUCCESS / FAILED */
    private String status;
    private Integer empTotal;
    private Integer empSuccess;
    private Integer empFailed;
    private Long operatorId;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    /** 进度明细（跳过原因 + 错误）以 JSON 存放，供轮询接口读取 */
    private String errorMsg;
}
