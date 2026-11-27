package com.insbean.payroll.setting;

import com.insbean.payroll.common.Result;
import com.insbean.payroll.security.RequirePerm;
import com.insbean.payroll.setting.dto.ConfigItemDTO;
import com.insbean.payroll.setting.dto.ConfigUpdateReq;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/configs")
public class ConfigController {

    private final ConfigService configService;

    public ConfigController(ConfigService configService) {
        this.configService = configService;
    }

    @RequirePerm("sys:config:view")
    @GetMapping
    public Result<List<ConfigItemDTO>> list() {
        return Result.ok(configService.list());
    }

    @RequirePerm("sys:config:edit")
    @PutMapping("/{key}")
    public Result<ConfigItemDTO> update(@PathVariable String key,
                                        @RequestBody ConfigUpdateReq req) {
        return Result.ok(configService.update(key, req == null ? null : req.value()));
    }
}
