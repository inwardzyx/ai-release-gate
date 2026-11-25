package com.arg.release.repo;

import com.arg.release.domain.Release;
import com.arg.release.domain.ReleaseState;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 发布的持久层。三件事全在事务里：**改状态 + 改指针 + 递增 rev**。
 *
 * <p>★★ 乐观锁怎么用（`WHERE id=? AND rev=?`）：
 * <pre>
 *   UPDATE `release` SET state='CANARY', rollout_pct=?, rev=rev+1
 *    WHERE id=? AND rev=? AND state='PENDING'
 * </pre>
 * 返回 0 行有两种可能："别人先改了"（rev 不匹配）或"状态不允许"（不是 PENDING）。
 * <b>这两种必须报不同的 409 code</b>，否则前端只能提示"操作失败"，
 * 而实际要做的事完全不同（刷新重试 vs 这个状态不能这么操作）。
 *
 * <p>★★ 为什么回滚是"改指针"而不是"重新发布一遍旧版本"：
 * 重新发布会产生新的 release 行、要重新过门禁、要重新灰度；而用户要的是"立刻回到上一个状态"。
 * 改指针是一次 UPDATE，O(1)，且天然幂等（连点两次回滚结果一致）。
 */
@Repository
public class ReleaseRepository {

    private static final String COLS = """
            SELECT id, env, prompt_key, version, state, rollout_pct, prev_release_id, rev,
                   gate_threshold, gate_score, gate_passed, operator, created_at, updated_at
            FROM `release`
            """;

    private final JdbcTemplate jdbc;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final RowMapper<Release> mapper = (rs, i) -> new Release(
            rs.getLong("id"),
            rs.getString("env"),
            rs.getString("prompt_key"),
            rs.getInt("version"),
            ReleaseState.valueOf(rs.getString("state")),
            rs.getInt("rollout_pct"),
            (Long) rs.getObject("prev_release_id"),
            rs.getInt("rev"),
            rs.getBigDecimal("gate_threshold"),
            rs.getBigDecimal("gate_score"),
            (Boolean) rs.getObject("gate_passed"),
            rs.getString("operator"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime());

    public ReleaseRepository(JdbcTemplate jdbc, com.fasterxml.jackson.databind.ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** 建一条 PENDING 发布；`prev_release_id` 记录"发布前生效的那个"，供回滚用。 */
    @Transactional
    public CreateOutcome create(String env, String promptKey, int version, String operator, String idemKey) {
        if (idemKey != null) {
            Optional<Release> existing = findByIdemKey(idemKey);
            if (existing.isPresent()) {
                return new CreateOutcome(existing.get(), true);
            }
        }
        Long prev = currentReleaseId(env, promptKey).orElse(null);
        try {
            jdbc.update("""
                            INSERT INTO `release`
                                (env, prompt_key, version, state, rollout_pct, prev_release_id, operator, idempotency_key)
                            VALUES (?, ?, ?, 'PENDING', 0, ?, ?, ?)
                            """,
                    env, promptKey, version, prev, operator, idemKey);
        } catch (DuplicateKeyException e) {
            if (idemKey != null) {
                return findByIdemKey(idemKey)
                        .map(r -> new CreateOutcome(r, true))
                        .orElseThrow(() -> e);
            }
            throw e;
        }
        Long id = jdbc.queryForObject(
                "SELECT id FROM `release` WHERE idempotency_key <=> ? ORDER BY id DESC LIMIT 1",
                Long.class, idemKey);
        return new CreateOutcome(findById(id).orElseThrow(), false);
    }

    /**
     * 开始灰度：PENDING → CANARY，并把指针切到这条发布。
     *
     * @throws OptimisticLockException rev 不匹配（有人抢先改过）
     * @throws IllegalStateException   状态不允许
     */
    @Transactional
    public Release canary(long id, int rolloutPct, java.math.BigDecimal gateThreshold, int expectedRev) {
        Release cur = findById(id).orElseThrow(() -> new NotFoundException(id));
        if (expectedRev != cur.rev()) {
            throw new OptimisticLockException(id, expectedRev, cur.rev());
        }
        if (!cur.state().canGoTo(ReleaseState.CANARY)) {
            throw new IllegalStateException("只有 PENDING 的发布能开始灰度，当前是 " + cur.state());
        }
        int rows = jdbc.update("""
                        UPDATE `release` SET state='CANARY', rollout_pct=?, gate_threshold=?, rev=rev+1
                         WHERE id=? AND rev=? AND state='PENDING'
                        """,
                rolloutPct, gateThreshold, id, expectedRev);
        if (rows == 0) {
            // 竞态：读完到现在，别人改了（这里再报 409，不能"乐观地当成功"）
            Release now = findById(id).orElseThrow(() -> new NotFoundException(id));
            throw new OptimisticLockException(id, expectedRev, now.rev());
        }
        pointTo(cur.env(), cur.promptKey(), id);
        // ★ 同事务写 outbox：发布生效与"门禁该跑了"这件事必须一起成功或一起失败
        String eventId = java.util.UUID.randomUUID().toString();
        // ★ traceId 必须写进事件：门禁由消息触发，那条链路上没有 HTTP 请求的头可传。
        //   "trace 跟着请求走"在同步调用里成立，在事件驱动里是错的 —— 它必须跟着**事件**走。
        String traceId = org.slf4j.MDC.get("traceId");
        String payload = String.format(
                "{\"releaseId\":%d,\"env\":\"%s\",\"promptKey\":\"%s\",\"version\":%d,\"rolloutPct\":%d,\"traceId\":\"%s\"}",
                id, cur.env(), cur.promptKey(), cur.version(), rolloutPct,
                traceId == null ? "-" : traceId);
        jdbc.update("""
                        INSERT INTO outbox_event (event_id, type, payload) VALUES (?, 'RELEASE_CANARY', ?)
                        """,
                eventId, payload);
        return findById(id).orElseThrow();
    }

    /** 放量到 100%：CANARY → FULL。 */
    @Transactional
    public Release promote(long id, int expectedRev) {
        Release cur = findById(id).orElseThrow(() -> new NotFoundException(id));
        if (expectedRev != cur.rev()) {
            throw new OptimisticLockException(id, expectedRev, cur.rev());
        }
        if (!cur.state().canPromote()) {
            throw new IllegalStateException("只有 CANARY 能放量，当前是 " + cur.state());
        }
        int rows = jdbc.update("""
                        UPDATE `release` SET state='FULL', rollout_pct=100, rev=rev+1
                         WHERE id=? AND rev=? AND state='CANARY'
                        """,
                id, expectedRev);
        if (rows == 0) {
            Release now = findById(id).orElseThrow(() -> new NotFoundException(id));
            throw new OptimisticLockException(id, expectedRev, now.rev());
        }
        return findById(id).orElseThrow();
    }

    /**
     * 回滚：把指针改回 {@code prev_release_id}，本条标记 ROLLED_BACK。
     *
     * <p>幂等：已经 ROLLED_BACK 的再回滚一次会抛状态异常（409），不会把指针改坏。
     */
    @Transactional
    public Release rollback(long id, int expectedRev) {
        Release cur = findById(id).orElseThrow(() -> new NotFoundException(id));
        if (expectedRev != cur.rev()) {
            throw new OptimisticLockException(id, expectedRev, cur.rev());
        }
        if (!cur.state().canRollback()) {
            throw new IllegalStateException("只有 CANARY/FULL 能回滚，当前是 " + cur.state());
        }
        int rows = jdbc.update("""
                        UPDATE `release` SET state='ROLLED_BACK', rev=rev+1
                         WHERE id=? AND rev=? AND state IN ('CANARY','FULL')
                        """,
                id, expectedRev);
        if (rows == 0) {
            Release now = findById(id).orElseThrow(() -> new NotFoundException(id));
            throw new OptimisticLockException(id, expectedRev, now.rev());
        }
        if (cur.prevReleaseId() != null) {
            pointTo(cur.env(), cur.promptKey(), cur.prevReleaseId());
        } else {
            // 这是第一个发布，回滚 = 回到"没有生效版本"的状态
            jdbc.update("DELETE FROM current_release WHERE env=? AND prompt_key=?",
                    cur.env(), cur.promptKey());
        }
        return findById(id).orElseThrow();
    }

    /**
     * 回写门禁结果。<b>不通过就自动回滚</b>。
     *
     * <p>★ 幂等：已经判定过的发布再来一次结果，直接返回当前状态，不重复回滚。
     * 理由：回调可能重复（至少一次），而"重复回滚"会把指针再往前推一格 —— 那是数据损坏。
     *
     * <p>★ 为什么自动回滚放在 release-svc 而不是 gate-svc：
     * 状态机只能有一个写者。gate-svc 通过 HTTP 告诉 release-svc "这版没过"，
     * 由 release-svc 自己执行回滚 —— 这样"谁改状态"永远只有一处。
     */
    @Transactional
    public Release applyGateResult(long id, java.math.BigDecimal score, boolean passed) {
        Release cur = findById(id).orElseThrow(() -> new NotFoundException(id));
        if (cur.gatePassed() != null) {
            return cur;                     // 已判定过：幂等返回
        }
        jdbc.update("UPDATE `release` SET gate_score=?, gate_passed=? WHERE id=?",
                score, passed ? 1 : 0, id);
        if (passed) {
            return findById(id).orElseThrow();   // 通过：保持 CANARY，等人放量
        }
        // 不通过 ⇒ 自动回滚（状态机 + 指针都在同一个事务里）
        return rollback(id, cur.rev());
    }

    public Optional<Release> findById(long id) {
        return jdbc.query(COLS + " WHERE id = ?", mapper, id).stream().findFirst();
    }

    public Optional<Release> findByIdemKey(String idemKey) {
        return jdbc.query(COLS + " WHERE idempotency_key = ?", mapper, idemKey).stream().findFirst();
    }

    public List<Release> list(String env, String promptKey, int limit) {
        return jdbc.query(COLS + " WHERE env=? AND prompt_key=? ORDER BY id DESC LIMIT ?",
                mapper, env, promptKey, limit);
    }

    public Optional<Long> currentReleaseId(String env, String promptKey) {
        return jdbc.query("SELECT release_id FROM current_release WHERE env=? AND prompt_key=?",
                        (rs, i) -> rs.getLong(1), env, promptKey)
                .stream().findFirst();
    }

    public Optional<Release> current(String env, String promptKey) {
        return currentReleaseId(env, promptKey).flatMap(this::findById);
    }

    private void pointTo(String env, String promptKey, long releaseId) {
        jdbc.update("""
                INSERT INTO current_release (env, prompt_key, release_id) VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE release_id = VALUES(release_id)
                """, env, promptKey, releaseId);
    }

    public record CreateOutcome(Release release, boolean idempotentHit) {
    }

    public static class OptimisticLockException extends RuntimeException {
        public OptimisticLockException(long id, int expected, int actual) {
            super("发布 " + id + " 已被别人修改：你拿到的 rev=" + expected + "，当前 rev=" + actual);
        }
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(long id) {
            super("发布不存在: id=" + id);
        }
    }
}




