package com.insbean.payroll.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 统一错误出口。
 *
 * <p>body 恒为 {@code {code, msg, data}}；HTTP 状态码与 body.code 保持一致
 * （400/401/403/409/500），这样前端既能用 fetch 的 status 做兜底，
 * 也能按 body.code 做业务分支，代理/监控也能正确统计错误率。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException e) {
        HttpStatus status = HttpStatus.resolve(e.getCode());
        if (status == null) {
            status = HttpStatus.BAD_REQUEST;
        }
        return ResponseEntity.status(status).body(Result.fail(e.getCode(), e.getMessage()));
    }

    /**
     * 乐观锁冲突：HTTP 409 + {@code data.conflicts}。
     * 前端要靠 recordId 清单高亮具体哪几行被他人改过，所以不能只回一个空 data。
     */
    @ExceptionHandler(VersionConflictException.class)
    public ResponseEntity<Result<Map<String, Object>>> handleVersionConflict(VersionConflictException e) {
        List<Map<String, Object>> conflicts = e.getRecordIds().stream()
                .map(id -> Map.<String, Object>of("recordId", String.valueOf(id)))
                .toList();
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new Result<>(409, e.getMessage(), Map.of("conflicts", conflicts)));
    }

    /**
     * 请求本身不合法（缺 body、JSON 解析失败、路径参数类型不对、缺必填查询参数）
     * 属于客户端错误，必须是 400；落到兜底的 500 会让前端和监控把用户输入错误当成系统故障。
     */
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<Result<Void>> handleBadRequest(Exception e) {
        return ResponseEntity.badRequest().body(Result.fail(400, "请求参数格式不正确"));
    }

    /**
     * 不存在的路由必须是 404，不是 500。
     *
     * <p>Spring 6.1 对未匹配的静态资源抛 {@link NoResourceFoundException}；
     * 兜底的 {@code Exception} handler 会把它转成 500，让"客户端打错地址"
     * 在监控里看起来像服务端故障。
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Result<Void>> handleNotFound(Exception e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.fail(404, "接口不存在"));
    }

    /** 方法不被支持必须是 405，不是 500（客户端打错方法不是服务端故障）。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.fail(405, "请求方法不被支持"));
    }

    /**
     * 连接池拿不到连接是"服务暂不可用"（503），不是 500：
     * 配合 3s 连接超时快速失败，避免 DB 故障拖垮整站线程。
     */
    @ExceptionHandler(CannotGetJdbcConnectionException.class)
    public ResponseEntity<Result<Void>> handleDbUnavailable(CannotGetJdbcConnectionException e) {
        log.error("数据库连接不可用", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Result.fail(503, "数据库繁忙，请稍后重试"));
    }

    /**
     * 上传超限必须是 413（Payload Too Large），不是 500。
     * 500 会让人以为是服务端故障去查日志，实际只是文件太大，前端应直接提示用户。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Result.fail(413, "上传文件超过大小上限，请拆分后重试"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(Result.fail(400, msg));
    }

    /**
     * 唯一约束兜底。uk_emp_ym_active 被触发时说明同一人在同一年月已存在有效核算记录，
     * 调用方（服务层）应先做业务预检以给出"在哪家分公司"的定位信息，
     * 这里只负责把并发穿透的漏网之鱼翻译成人话，不暴露数据库细节。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Result<Void>> handleDuplicateKey(DuplicateKeyException e) {
        String raw = e.getMessage() == null ? "" : e.getMessage();
        log.warn("唯一约束冲突: {}", raw);
        if (raw.contains("uk_emp_ym_active")) {
            return conflict("该员工在该月份已存在有效工资核算记录（可能在其他分公司），请先核对");
        }
        if (raw.contains("uk_emp_no")) {
            return conflict("工号已被占用");
        }
        if (raw.contains("uk_id_hash")) {
            return conflict("该证件号码已存在人员记录");
        }
        return conflict("数据重复，请刷新后重试");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(500, "系统异常，请联系管理员"));
    }

    private static ResponseEntity<Result<Void>> conflict(String msg) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Result.fail(409, msg));
    }
}
