package com.arg.release.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一次发布。
 *
 * @param rev           乐观锁版本号：每次状态变更 +1。客户端带着它来改，不匹配就是 409。
 * @param prevReleaseId 本次发布**之前**生效的那个 release（回滚目标）；null 表示这是第一个。
 * @param gateThreshold 门禁标准差：**发布方自己声明**"这版要多好才允许放量"。
 *                      写死一个全局阈值是错的——改 prompt 与换模型的风险不一样大。
 * @param gateScore     门禁跑出来的分；null = 还没跑过（缺失，不是 0）。
 * @param gatePassed    null = 还没判定；true/false = 判定结果。
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
        BigDecimal gateThreshold,
        BigDecimal gateScore,
        Boolean gatePassed,
        String operator,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
