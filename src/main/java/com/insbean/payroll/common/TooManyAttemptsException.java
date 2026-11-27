package com.insbean.payroll.common;

/**
 * 登录尝试过于频繁（429）。
 *
 * <p>与直接拒绝不同：请求已经按"渐进延迟"等待过，仍然失败才返回 429，
 * 并把下次建议等待秒数带给前端（data.retryAfter）。
 */
public class TooManyAttemptsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyAttemptsException(long retryAfterSeconds) {
        super("登录尝试过于频繁，请 " + retryAfterSeconds + " 秒后再试");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
