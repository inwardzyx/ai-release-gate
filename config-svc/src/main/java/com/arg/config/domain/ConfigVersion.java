package com.arg.config.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

/**
 * 一个配置版本（不可变）。
 *
 * @param gateStatus 门禁状态：PENDING / RUNNING / PASS / FAIL / SKIP。
 *                   它挂在**版本**上而不是发布上：同一个版本可以被发布多次（先灰度后回滚再发），
 *                   但"这版好不好"是版本自己的属性，不该跟着发布记录走。
 */
public record ConfigVersion(
        long id,
        String promptKey,
        int version,
        JsonNode payload,
        String gateStatus,
        String createdBy,
        LocalDateTime createdAt) {
}
