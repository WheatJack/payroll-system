package com.insbean.payroll.run.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.insbean.payroll.run.PayRunSkipEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface RunSkipMapper extends BaseMapper<PayRunSkipEntity> {

    @Delete("DELETE FROM pay_run_skip WHERE run_id = #{runId}")
    int deleteByRun(@Param("runId") Long runId);
}
