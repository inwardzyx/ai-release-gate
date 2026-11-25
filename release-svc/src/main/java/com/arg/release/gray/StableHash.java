package com.arg.release.gray;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 灰度分桶：把 tenantId 稳定地映射到 0..99。
 *
 * <p>★★ 为什么不用 {@code Math.random()} 或 {@code Math.abs(tenantId.hashCode()) % 100}：
 * <ul>
 *   <li><b>随机</b>：同一个租户每次请求可能落到不同版本 —— 用户会看到"上一句用新 prompt、下一句用旧 prompt"，
 *       这是最典型的灰度事故（也叫"割裂体验"）。测试要能抓：同租户 1000 次必须 100% 落同一侧。</li>
 *   <li><b>String.hashCode</b>：跨 JVM/跨语言都稳定，但只有 32 位且分布差，模板 id 多时容易扎堆。</li>
 * </ul>
 * 用 SHA-256 的前 4 字节做桶：稳定、分布均匀、换语言（比如 Python 版 executor）也一致。
 */
public final class StableHash {

    private static final int BUCKETS = 100;

    private StableHash() {
    }

    public static int bucket(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            // 没有租户标识时按"最保守"处理：落到 0 号桶（永远是"旧版本"那一侧）
            return 0;
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(tenantId.getBytes(StandardCharsets.UTF_8));
            int v = ((d[0] & 0xff) << 24) | ((d[1] & 0xff) << 16) | ((d[2] & 0xff) << 8) | (d[3] & 0xff);
            return Math.floorMod(v, BUCKETS);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须支持的算法；真没有就是运行环境坏了，不能静默降级成随机
            throw new IllegalStateException("JDK 不支持 SHA-256", e);
        }
    }
}
