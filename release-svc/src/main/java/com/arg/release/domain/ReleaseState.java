package com.arg.release.domain;

/**
 * 发布状态机。
 *
 * <pre>
 *   PENDING ──canary()──▶ CANARY ──promote()──▶ FULL
 *      │                    │                    │
 *      └────rollback()──────┴────rollback()──────┘──▶ ROLLED_BACK
 * </pre>
 *
 * <p>★ 为什么用枚举 + 显式迁移，而不是一个 boolean `published`：
 * 状态机是"回滚原子性"和"重复操作幂等"的基础——`rollback` 只能从 CANARY/FULL 出发，
 * 重复回滚会得到 409 而不是"再回滚一次"（那会把指针改到错误的地方）。
 */
public enum ReleaseState {
    PENDING,
    CANARY,
    FULL,
    ROLLED_BACK;

    /** 当前状态是否允许回滚：只有"已经生效过"的才谈得上回滚。 */
    public boolean canRollback() {
        return this == CANARY || this == FULL;
    }

    /** 当前状态是否允许继续放量。 */
    public boolean canPromote() {
        return this == CANARY;
    }
}
