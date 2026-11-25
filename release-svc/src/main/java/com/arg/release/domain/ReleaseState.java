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
        return canGoTo(ROLLED_BACK);
    }

    /** 当前状态是否允许继续放量。 */
    public boolean canPromote() {
        return canGoTo(FULL);
    }

    /**
     * 状态机唯一的定义处：从 this 能不能走到 next。
     *
     * <pre>
     *   PENDING     → CANARY
     *   CANARY      → FULL | ROLLED_BACK
     *   FULL        → ROLLED_BACK
     *   ROLLED_BACK → （终态，不能再动）
     * </pre>
     *
     * ★ 为什么要有这个方法：原来三条迁移路径各自写 `state != PENDING` 之类的判断，
     * 知识分散在三处，改一处漏两处。收在这里之后，单测覆盖它就是覆盖了三条路径。
     * ★ 为什么 ROLLED_BACK 是终态：回滚过的发布不能再被"复活"——
     * 否则 `prev_release_id` 的语义会乱（它记的是"回滚目标"，复活后指向谁？）。
     * 要重发就新建一条发布。
     */
    public boolean canGoTo(ReleaseState next) {
        return switch (this) {
            case PENDING -> next == CANARY;
            case CANARY -> next == FULL || next == ROLLED_BACK;
            case FULL -> next == ROLLED_BACK;
            case ROLLED_BACK -> false;
        };
    }
}
