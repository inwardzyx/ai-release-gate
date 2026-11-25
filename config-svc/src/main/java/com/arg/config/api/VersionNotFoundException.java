package com.arg.config.api;

/**
 * 查不到版本。
 *
 * <p>★ 为什么用异常而不是在 controller 里拼 404 响应：
 * 错误体必须统一成 {@code ApiError}（调用方只写一套解析），而"拼响应"会让每个接口都复制一遍
 * 错误体构造 —— 那正是"错误格式慢慢漂移"的起点。异常 + 统一 handler 只有一个出口。
 */
public class VersionNotFoundException extends RuntimeException {

    public VersionNotFoundException(String promptKey) {
        super("这个 promptKey 还没有任何版本: " + promptKey);
    }
}
