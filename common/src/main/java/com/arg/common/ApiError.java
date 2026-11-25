package com.arg.common;

/**
 * 服务之间统一的错误体。
 *
 * <p>★ 为什么要在 {@code common} 里定死它（2026-11 定的）：
 * 三个服务的调用方是彼此（release-svc 调 config-svc，gate-svc 调 executor），
 * 如果每个服务自己发明错误格式，调用方就得写三套解析 —— 而"契约漂移"正是微服务最常见的故障源。
 *
 * <p>★ 字段刻意只有三个：
 * <ul>
 *   <li>{@code code} —— 机器可判断的稳定标识（不随文案变）；</li>
 *   <li>{@code message} —— 人能看懂的一句话；</li>
 *   <li>{@code traceId} —— 跨服务把两个进程的日志串起来（本项目的"可观测"底线）。</li>
 * </ul>
 * 不加 {@code timestamp} / {@code path}：它们由访问日志负责，放这里只会让每个服务都要记得填。
 */
public record ApiError(String code, String message, String traceId) {

    public static ApiError of(String code, String message, String traceId) {
        return new ApiError(code, message, traceId);
    }
}
