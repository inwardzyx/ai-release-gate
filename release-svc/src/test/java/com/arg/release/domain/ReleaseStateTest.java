package com.arg.release.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 状态机的单元测试：合法迁移、非法迁移都要断。
 *
 * <p>★ 只测合法路径是不够的。真实事故里最常见的是"以为不会发生的迁移发生了"
 * —— 重复回滚把指针又推一格、回滚过的发布被复活、FULL 状态重新灰度。
 * 所以这里对**每一对 (from, to)** 都断言，包括不该通的。
 */
class ReleaseStateTest {

    @Test
    @DisplayName("合法迁移：PENDING→CANARY、CANARY→FULL/ROLLED_BACK、FULL→ROLLED_BACK")
    void legalTransitions() {
        assertThat(ReleaseState.PENDING.canGoTo(ReleaseState.CANARY)).isTrue();
        assertThat(ReleaseState.CANARY.canGoTo(ReleaseState.FULL)).isTrue();
        assertThat(ReleaseState.CANARY.canGoTo(ReleaseState.ROLLED_BACK)).isTrue();
        assertThat(ReleaseState.FULL.canGoTo(ReleaseState.ROLLED_BACK)).isTrue();
    }

    @Test
    @DisplayName("非法迁移必须一律 false（16 种组合逐个断，防止以后加状态留下空洞）")
    void illegalTransitions() {
        for (ReleaseState from : ReleaseState.values()) {
            for (ReleaseState to : ReleaseState.values()) {
                boolean expected = switch (from) {
                    case PENDING -> to == ReleaseState.CANARY;
                    case CANARY -> EnumSet.of(ReleaseState.FULL, ReleaseState.ROLLED_BACK).contains(to);
                    case FULL -> to == ReleaseState.ROLLED_BACK;
                    case ROLLED_BACK -> false;
                };
                assertThat(from.canGoTo(to))
                        .as("%s → %s 应为 %s", from, to, expected)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    @DisplayName("ROLLED_BACK 是终态：不能再灰度、不能放量、不能再回滚")
    void rolledBackIsTerminal() {
        assertThat(ReleaseState.ROLLED_BACK.canGoTo(ReleaseState.CANARY)).isFalse();
        assertThat(ReleaseState.ROLLED_BACK.canGoTo(ReleaseState.FULL)).isFalse();
        assertThat(ReleaseState.ROLLED_BACK.canRollback()).isFalse();
        assertThat(ReleaseState.ROLLED_BACK.canPromote()).isFalse();
        // 自身的"迁移"也不算合法：重复回滚必须被拒（否则指针会被再推一格）
        assertThat(ReleaseState.ROLLED_BACK.canGoTo(ReleaseState.ROLLED_BACK)).isFalse();
    }

    @Test
    @DisplayName("canRollback / canPromote 与 canGoTo 同源（不是各写一份）")
    void helpersAgreeWithTransitionTable() {
        for (ReleaseState s : ReleaseState.values()) {
            assertThat(s.canRollback())
                    .as("%s.canRollback()", s)
                    .isEqualTo(s.canGoTo(ReleaseState.ROLLED_BACK));
            assertThat(s.canPromote())
                    .as("%s.canPromote()", s)
                    .isEqualTo(s.canGoTo(ReleaseState.FULL));
        }
    }

    @Test
    @DisplayName("PENDING 的发布不能回滚（还没生效，谈不上退）")
    void pendingCannotRollback() {
        assertThat(ReleaseState.PENDING.canRollback()).isFalse();
        assertThat(ReleaseState.PENDING.canPromote()).isFalse();
    }
}
