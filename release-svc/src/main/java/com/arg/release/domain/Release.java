package com.arg.release.domain;

import java.time.LocalDateTime;

/**
 * 一次发布。
 *
 * @param rev      乐观锁版本号：每次状态变更 +1。客户端带着它来改，不匹配就是 409（有人抢先改过）。
 * @param prevReleaseId 本次发布**之前**生效的那个 release（回滚目标）；null 表示这是第一个。
 */
public record Release(
        long id,
        String env,
        String promptKey,
        int version,
        ReleaseState state,
        int rolloutPct,
        Long prevReleaseId,
        int rev,
        String operator,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
