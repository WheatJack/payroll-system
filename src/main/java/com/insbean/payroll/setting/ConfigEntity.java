package com.insbean.payroll.setting;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 系统参数（sys_config）。本表无 deleted 列：参数只在线修改，不软删除。 */
@Data
@TableName("sys_config")
public class ConfigEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String configKey;
    private String configValue;
    /** TEXT / DECIMAL / INT / DATE_LIST */
    private String valueType;
    private String description;
    /** 0 = 只读（运维参数），PUT 一律 403 */
    private Integer editable;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
