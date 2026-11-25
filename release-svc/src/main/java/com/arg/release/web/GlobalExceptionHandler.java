package com.arg.release.web;

import com.arg.common.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 统一错误出口（错误体与 config-svc 完全一致，都是 {@code ApiError}）。
 *
 * <p>★ 状态码映射表（这张表就是"调用方该怎么办"的说明书）：
 * <table>
 *   <tr><td>400</td><td>参数错 / 版本不存在</td><td>改参数，别重试</td></tr>
 *   <tr><td>404</td><td>对象不存在</td><td>刷新列表</td></tr>
 *   <tr><td>409 CONCURRENT_MODIFICATION</td><td>rev 不匹配</td><td>重新 GET 拿最新 rev 再改</td></tr>
 *   <tr><td>409 STATE_NOT_ALLOWED</td><td>状态不允许</td><td>先看当前状态（比如已回滚过）</td></tr>
 *   <tr><td>503 UPSTREAM_UNAVAILABLE</td><td>config-svc 挂了/超时</td><td>稍后重试；期间**不会**发布</td></tr>
 * </table>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiError> onBiz(BizException e) {
        // 4xx 不打堆栈（调用方的问题），5xx 打（我们的问题）
        if (e.status().is5xxServerError()) {
            log.warn("上游/依赖问题 traceId={} code={} msg={}", traceId(), e.code(), e.getMessage());
        }
        return ResponseEntity.status(e.status()).body(e.toApiError(traceId()));
    }

    @ExceptionHandler(com.arg.release.repo.ReleaseRepository.OptimisticLockException.class)
    public ResponseEntity<ApiError> onOptimistic(com.arg.release.repo.ReleaseRepository.OptimisticLockException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("CONCURRENT_MODIFICATION", e.getMessage() + "，请重新拉取后再操作", traceId()));
    }

    @ExceptionHandler(com.arg.release.repo.ReleaseRepository.NotFoundException.class)
    public ResponseEntity<ApiError> onNotFound(com.arg.release.repo.ReleaseRepository.NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", e.getMessage(), traceId()));
    }

    /** 状态机拒绝：不是"程序错了"，是"现在不该这么做" —— 409，不是 500。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> onIllegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("STATE_NOT_ALLOWED", e.getMessage(), traceId()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> onInvalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(ApiError.of("VALIDATION_FAILED", detail, traceId()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> onMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("MISSING_PARAM", "缺少参数: " + e.getParameterName(), traceId()));
    }

    /**
     * 路径不存在 ⇒ 404。
     * ★ 补这条的原因：catch-all 会把 NoResourceFoundException 当 500 并**打 ERROR 堆栈**，
     * 于是探测/扫描请求会把日志灌满，真正的 500 反而被淹没。
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ApiError> onNoResource(
            org.springframework.web.servlet.resource.NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NO_SUCH_PATH", "没有这个接口: " + e.getResourcePath(), traceId()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> onOther(Exception e) {
        log.error("未处理的异常 traceId={}", traceId(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("INTERNAL_ERROR", "服务内部错误，请把 traceId 提供给开发", traceId()));
    }

    private static String traceId() {
        String id = MDC.get("traceId");
        return id != null ? id : "-";
    }
}
