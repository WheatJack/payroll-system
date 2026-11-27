package com.insbean.payroll.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import static org.assertj.core.api.Assertions.assertThat;

/** 连接池耗尽必须映射成 503（服务暂不可用），而不是 500。 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void 数据库连接不可用返回503() {
        ResponseEntity<Result<Void>> response = handler.handleDbUnavailable(
                new CannotGetJdbcConnectionException("pool exhausted"));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(503);
        assertThat(response.getBody().msg()).contains("数据库繁忙");
    }
}
