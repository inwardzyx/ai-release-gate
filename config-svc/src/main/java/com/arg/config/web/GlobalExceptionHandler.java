package com.arg.config.web;

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
 * 统一错误出口。
 *
 * <p>★ 两条规则：
 * <ol>
 *   <li><b>错误体必须是 {@link ApiError}</b>：调用方（release-svc / gate-svc）只写一套解析。
 *       如果每个服务自己发明格式，跨服务错误处理就会长出 N 套代码 —— 那是"假拆"的典型症状。</li>
 *   <li><b>4xx 不打堆栈，5xx 必须打</b>：参数错是调用方的问题，打堆栈只会淹掉真正的故障。</li>
 * </ol>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> onInvalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(ApiError.of("VALIDATION_FAILED", detail, traceId()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> onMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("MISSING_PARAM", "缺少参数: " + e.getParameterName(), traceId()));
    }

    @ExceptionHandler(com.arg.config.api.VersionNotFoundException.class)
    public ResponseEntity<ApiError> onNotFound(com.arg.config.api.VersionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("VERSION_NOT_FOUND", e.getMessage(), traceId()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> onOther(Exception e) {
        // ★ 5xx 必须留堆栈：这是唯一能把"线上 500"和"代码哪一行"连起来的东西
        log.error("未处理的异常 traceId={}", traceId(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("INTERNAL_ERROR", "服务内部错误，请把 traceId 提供给开发", traceId()));
    }

    private static String traceId() {
        String id = MDC.get("traceId");
        return id != null ? id : "-";
    }
}

