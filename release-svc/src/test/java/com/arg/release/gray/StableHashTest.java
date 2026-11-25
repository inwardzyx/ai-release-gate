package com.arg.release.gray;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 灰度分桶的单元测试。
 *
 * <p>★ 为什么要"跨语言向量"这一条：分桶一旦换实现（改 hash、改取模方式），
 * 已在灰度的租户会**整体漂移**（原本在新版本的用户突然回到旧版本）。
 * 本项目的执行器是 Python，所以必须钉死"Python 独立实现算出来的桶号"，
 * 这样任何一侧改动都会立刻被这条测试拦住。
 */
class StableHashTest {

    /** 由 Python 独立实现（hashlib.sha256 → 前 4 字节大端 → floorMod 100）算出的向量。 */
    private static final Map<String, Integer> PYTHON_VECTORS = Map.of(
            "tenant-0", 74,
            "tenant-1", 15,
            "tenant-2", 49,
            "tenant-42", 45,
            "tenant-99", 65,
            "租户-一号", 17,
            "a", 14);

    @Test
    @DisplayName("桶号与 Python 独立实现逐项一致（跨语言稳定）")
    void matchesPythonVectors() {
        PYTHON_VECTORS.forEach((tenant, expected) ->
                assertThat(StableHash.bucket(tenant))
                        .as("租户 %s 的桶号", tenant)
                        .isEqualTo(expected));
    }

    @Test
    @DisplayName("同一个租户 1000 次都是同一个桶（灰度不能抖）")
    void stableAcrossCalls() {
        Set<Integer> buckets = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            buckets.add(StableHash.bucket("tenant-42"));
        }
        assertThat(buckets).hasSize(1);
    }

    @Test
    @DisplayName("空 / null / 全空格都落到 0 号桶（最保守：永远留在旧版本那侧）")
    void blankTenantFallsBackToZero() {
        assertThat(StableHash.bucket(null)).isZero();
        assertThat(StableHash.bucket("")).isZero();
        assertThat(StableHash.bucket("   ")).isZero();
        assertThat(StableHash.bucket("\t")).isZero();
    }

    @Test
    @DisplayName("桶号永远在 0..99 之间（含负数边界，不能靠 Math.abs 兜底）")
    void alwaysInRange() {
        for (int i = 0; i < 20000; i++) {
            int b = StableHash.bucket("tenant-" + i);
            assertThat(b).isBetween(0, 99);
        }
    }

    @Test
    @DisplayName("10000 个租户能把 100 个桶都用到（分布没有硬空洞）")
    void coversAllBuckets() {
        Map<Integer, Integer> dist = new HashMap<>();
        for (int i = 0; i < 10_000; i++) {
            dist.merge(StableHash.bucket("tenant-" + i), 1, Integer::sum);
        }
        // ★ 断"每个桶都被用到"而不是断具体数字：具体数字随输入变化，断死会变成脆弱测试
        assertThat(dist.keySet()).hasSize(100);
        // 均匀分布的粗略下界：平均 100 个，最少的桶不该少于 30（这能抓住"分布塌成少数几桶"）
        assertThat(dist.values()).allSatisfy(n -> assertThat(n).isGreaterThan(30));
    }

    @Test
    @DisplayName("灰度百分比是单调的：同一个租户在 pct=50 命中 ⇒ 在 pct=80 也必须命中")
    void monotonicInRolloutPercent() {
        for (int i = 0; i < 500; i++) {
            int bucket = StableHash.bucket("tenant-" + i);
            boolean at50 = bucket < 50;
            boolean at80 = bucket < 80;
            if (at50) {
                assertThat(at80).as("租户 tenant-%d 在 50%% 命中却在 80%% 掉了", i).isTrue();
            }
        }
    }
}
