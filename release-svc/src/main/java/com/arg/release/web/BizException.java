package com.arg.release.web;

import com.arg.common.ApiError;
import org.springframework.http.HttpStatus;

/**
 * 业务异常：带"给人看的 message"和"给机器判断的 code"。
 *
 * <p>★ 为什么不用返回 null / false 表达"版本不合法"这类错误：
 * 调用方（门禁、前端、脚本）需要区分"参数错 / 状态不允许 / 上游不可用"，
 * 这三种的处理方式完全不同（改参数、刷新状态、重试）。
 * 用 HTTP 状态码 + 稳定的 code 表达，比让调用方解析中文文案可靠。
 */
public class BizException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public BizException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public ApiError toApiError(String traceId) {
        return ApiError.of(code, getMessage(), traceId);
    }

    // ---- 常用工厂：把"哪种错"写进方法名，比 new BizException(...) 好读 ----

    public static BizException notFound(String message) {
        return new BizException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static BizException conflict(String code, String message) {
        return new BizException(HttpStatus.CONFLICT, code, message);
    }

    public static BizException badRequest(String code, String message) {
        return new BizException(HttpStatus.BAD_REQUEST, code, message);
    }

    /** 上游（config-svc）不可用：**fail closed** —— 不知道版本存不存在，就不许发布。 */
    public static BizException upstreamUnavailable(String message) {
        return new BizException(HttpStatus.SERVICE_UNAVAILABLE, "UPSTREAM_UNAVAILABLE", message);
    }
}
