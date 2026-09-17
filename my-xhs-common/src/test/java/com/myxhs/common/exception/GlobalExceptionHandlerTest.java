package com.myxhs.common.exception;

import com.myxhs.common.response.ResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GlobalExceptionHandler 单测（A3：限流语义规范化——业务限流返回 HTTP 429）
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("限流异常 → HTTP 429")
    void rateLimitShouldReturn429() {
        var response = handler.handleBizException(
                new BizException(ResultCode.RATE_LIMIT_REJECT, "请求过于频繁"),
                new MockHttpServletRequest());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ResultCode.RATE_LIMIT_REJECT.getCode());
    }

    @Test
    @DisplayName("普通业务异常 → HTTP 200（保持兼容）")
    void normalBizExceptionShouldReturn200() {
        var response = handler.handleBizException(
                new BizException(ResultCode.PARAM_MISSING, "缺少参数"),
                new MockHttpServletRequest());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ResultCode.PARAM_MISSING.getCode());
    }
}
