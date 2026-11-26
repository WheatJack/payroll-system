package com.insbean.payroll.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return Result.fail(400, msg);
    }

    /**
     * 唯一约束兜底。uk_emp_ym_active 被触发时说明同一人在同一年月已存在有效核算记录，
     * 调用方（服务层）应先做业务预检以给出"在哪家分公司"的定位信息，
     * 这里只负责把并发穿透的漏网之鱼翻译成人话，不暴露数据库细节。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(DuplicateKeyException e) {
        String raw = e.getMessage() == null ? "" : e.getMessage();
        log.warn("唯一约束冲突: {}", raw);
        if (raw.contains("uk_emp_ym_active")) {
            return Result.fail(409, "该员工在该月份已存在有效工资核算记录（可能在其他分公司），请先核对");
        }
        if (raw.contains("uk_emp_no")) {
            return Result.fail(409, "工号已被占用");
        }
        if (raw.contains("uk_id_hash")) {
            return Result.fail(409, "该证件号码已存在人员记录");
        }
        return Result.fail(409, "数据重复，请刷新后重试");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleOther(Exception e) {
        log.error("未处理异常", e);
        return Result.fail(500, "系统异常，请联系管理员");
    }
}
