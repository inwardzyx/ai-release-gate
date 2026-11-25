package com.arg.gate.repo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等的单元测试：跑在 H2（MySQL 兼容模式）上，**用的是真 SQL**，不是 mock。
 *
 * <p>★ 为什么不用 mock：这一层的正确性等于"数据库对我这行 SQL 的行为"。
 * mock 掉 JdbcTemplate 等于把要验证的东西假设成对的（唯一索引冲突会不会抛
 * DuplicateKeyException、UPDATE ... WHERE passed IS NULL 到底改了几行）。
 *
 * <p>★ 为什么不用 MySQL/Testcontainers：这层 SQL 没有用到 MySQL 特有语法，
 * H2 的 MySQL 模式足够；换成容器会让单测从"秒级"变成"几十秒"，单测一旦慢就没人跑。
 * 真正的 MySQL 行为由端到端判据（`scripts/smoke_gate.py` 里投 100 次）兜住。
 */
class GateJobRepositoryTest {

    private GateJobRepository repo;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:gate" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        // 与生产 schema 同列，但不写 ENGINE / ON UPDATE（H2 不认 MySQL 的存储引擎子句）
        jdbc.execute("""
                CREATE TABLE gate_job (
                    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
                    release_id BIGINT       NOT NULL,
                    event_id   VARCHAR(64)  NOT NULL,
                    prompt_key VARCHAR(64)  NOT NULL,
                    version    INT          NOT NULL,
                    state      VARCHAR(16)  NOT NULL DEFAULT 'QUEUED',
                    attempts   INT          NOT NULL DEFAULT 0,
                    threshold  DECIMAL(4,3) NOT NULL DEFAULT 0.500,
                    score      DECIMAL(6,4) NULL,
                    passed     TINYINT      NULL,
                    detail     VARCHAR(255) NULL,
                    updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT uk_event UNIQUE (event_id)
                )
                """);
        repo = new GateJobRepository(jdbc);
    }

    @Test
    @DisplayName("同一 eventId 插两次：第二次返回 empty，表里只有 1 行")
    void insertIfAbsentIsIdempotent() {
        Optional<Long> first = repo.insertIfAbsent(7, "evt-1", "policy_qa", 3, new BigDecimal("0.80"));
        Optional<Long> second = repo.insertIfAbsent(7, "evt-1", "policy_qa", 3, new BigDecimal("0.80"));

        assertThat(first).isPresent();
        assertThat(second).as("重复事件不该再建任务").isEmpty();
        assertThat(repo.stats().get("jobs")).isEqualTo(1L);
    }

    @Test
    @DisplayName("不同 eventId 各自建任务（幂等不能误伤正常消息）")
    void differentEventsCreateSeparateJobs() {
        assertThat(repo.insertIfAbsent(7, "evt-a", "policy_qa", 3, new BigDecimal("0.80"))).isPresent();
        assertThat(repo.insertIfAbsent(7, "evt-b", "policy_qa", 3, new BigDecimal("0.80"))).isPresent();
        assertThat(repo.stats().get("jobs")).isEqualTo(2L);
    }

    @Test
    @DisplayName("同一 job 回调两次：第一次生效，第二次返回 false（不能二次回滚）")
    void finishOnlyOnce() {
        long jobId = repo.insertIfAbsent(7, "evt-1", "policy_qa", 3, new BigDecimal("0.80")).orElseThrow();

        assertThat(repo.finish(jobId, new BigDecimal("0.80"), true, "first")).isTrue();
        assertThat(repo.finish(jobId, new BigDecimal("0.10"), false, "replay"))
                .as("第二次回调必须被挡掉").isFalse();

        var job = repo.findById(jobId).orElseThrow();
        assertThat(job.get("state")).isEqualTo("PASS");
        assertThat(job.get("detail")).isEqualTo("first");
        assertThat(String.valueOf(job.get("score"))).startsWith("0.80");
    }

    @Test
    @DisplayName("markRunning 只从 QUEUED 迁走：重复调用第二次返回 false（不重复提交执行器）")
    void markRunningIsOneShot() {
        long jobId = repo.insertIfAbsent(7, "evt-1", "policy_qa", 3, new BigDecimal("0.80")).orElseThrow();

        assertThat(repo.markRunning(jobId)).isTrue();
        assertThat(repo.markRunning(jobId)).as("已经是 RUNNING 了").isFalse();
        assertThat(repo.findById(jobId).orElseThrow().get("attempts")).isEqualTo(1);
    }

    @Test
    @DisplayName("finish 会截断超长 detail（VARCHAR(255) 不能把整条日志撑爆）")
    void finishTruncatesLongDetail() {
        long jobId = repo.insertIfAbsent(7, "evt-1", "policy_qa", 3, new BigDecimal("0.80")).orElseThrow();
        String longDetail = "x".repeat(600);

        assertThat(repo.finish(jobId, new BigDecimal("0.5"), false, longDetail)).isTrue();
        String stored = (String) repo.findById(jobId).orElseThrow().get("detail");
        assertThat(stored).hasSize(255);
    }
}
