package com.arg.config.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 建版本请求。
 *
 * <p>★ {@code payload} 用 {@link JsonNode} 而不是 String：
 * 契约上它就是"一段 JSON"，用 String 会让调用方把 JSON 再序列化一次（转义地狱），
 * 而 `JsonNode` 直接吃对象、原样入库。
 */
public record CreateVersionRequest(
        @NotBlank(message = "promptKey 不能为空")
        @Size(max = 64, message = "promptKey 最长 64")
        String promptKey,

        @NotNull(message = "payload 不能为空")
        JsonNode payload,

        @NotBlank(message = "createdBy 不能为空")
        @Size(max = 64, message = "createdBy 最长 64")
        String createdBy) {
}
