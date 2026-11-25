package com.arg.gate.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 门禁任务的持久层。
 *
 * <p>★ 状态主线：QUEUED → RUNNING → PASS / FAIL。
 * 回调可能重复到达（至少一次语义），所以 {@link #finish} 只在"还没判定"时生效。
 */
@Repository
public class GateJobRepository {

    private final JdbcTemplate jdbc;

    public GateJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 若该 eventId 还没有任务，就建一条；已存在则什么都不做（幂等）。
     *
     * <p>★ 建任务时把 {@code prompt_key / version} **快照**下来：门禁必须评"发布当时那一版"，
     * 而不是"跑的时候最新那版" —— 否则两次发布之间有人又提了版本，门禁评的就不是这次发布的东西。
     *
     * @return 新建出来的任务 id；已存在返回 empty
     */
    public Optional<Long> insertIfAbsent(long releaseId, String eventId, String promptKey, int version,
                                        BigDecimal threshold) {
        try {
            jdbc.update("""
                            INSERT INTO gate_job (release_id, event_id, prompt_key, version, threshold)
                            VALUES (?, ?, ?, ?, ?)
                            """, releaseId, eventId, promptKey, version, threshold);
        } catch (DuplicateKeyException e) {
            return Optional.empty();      // 重复投递：正常路径
        }
        return Optional.ofNullable(jdbc.queryForObject(
                "SELECT id FROM gate_job WHERE event_id = ?", Long.class, eventId));
    }

    public Optional<Map<String, Object>> findById(long id) {
        return jdbc.queryForList("""
                SELECT id, release_id, event_id, prompt_key, version, state, attempts,
                       threshold, score, passed, detail
                FROM gate_job WHERE id = ?
                """, id).stream().findFirst();
    }

    /** 标记为已提交执行器（只从 QUEUED 迁走，避免重复提交）。 */
    public boolean markRunning(long id) {
        return jdbc.update(
                "UPDATE gate_job SET state='RUNNING', attempts=attempts+1 WHERE id=? AND state='QUEUED'",
                id) == 1;
    }

    /** 扫描器把卡住的任务放回 QUEUED，等下一轮重投（attempts 已计过）。 */
    public int markQueuedAgain(long id) {
        return jdbc.update(
                "UPDATE gate_job SET state='QUEUED' WHERE id=? AND passed IS NULL", id);
    }

    /**
     * 写回门禁结果。<b>幂等</b>：只在"还没判定"（passed IS NULL）时生效。
     *
     * @return true = 这次真的写入了（false = 重复回调，忽略）
     */
    public boolean finish(long id, BigDecimal score, boolean passed, String detail) {
        return jdbc.update("""
                        UPDATE gate_job SET state=?, score=?, passed=?, detail=?
                         WHERE id=? AND passed IS NULL
                        """,
                passed ? "PASS" : "FAIL", score, passed ? 1 : 0,
                detail == null ? null : detail.substring(0, Math.min(detail.length(), 255)), id) == 1;
    }

    /**
     * 需要重投的任务，分两类（**必须分开**，否则长任务会被当成卡死）：
     *
     * <ul>
     *   <li><b>QUEUED 且超过 {@code queuedSeconds} 秒</b>：上次提交就没成功（执行器不在/超时），
     *       5 秒后重投，恢复得快；</li>
     *   <li><b>RUNNING 且超过 {@code runningSeconds} 秒</b>：已提交但没回调，
     *       用远大于任务时长的租约（默认 300 秒）兜底 —— 一次真评测要 1~2 分钟，
     *       租约短了就会把"还在跑"误判成"卡死"，attempts 这个指标也就废了。</li>
     * </ul>
     */
    /** 标成 ERROR（执行器自己报错）：**不动 passed**，保持"未判定"，等扫描器重投。 */
    public boolean markError(long id, String detail) {
        return jdbc.update(
                "UPDATE gate_job SET state='ERROR', detail=? WHERE id=? AND passed IS NULL",
                detail == null ? null : detail.substring(0, Math.min(detail.length(), 255)), id) == 1;
    }

    public List<Map<String, Object>> findStale(int maxAttempts, int queuedSeconds, int runningSeconds) {
        return jdbc.queryForList("""
                SELECT id, release_id, prompt_key, version, threshold, attempts, state
                FROM gate_job
                WHERE passed IS NULL AND attempts < ?
                  AND (
                        (state = 'QUEUED'  AND updated_at < NOW(3) - INTERVAL ? SECOND)
                     OR (state = 'RUNNING' AND updated_at < NOW(3) - INTERVAL ? SECOND)
                      )
                ORDER BY id LIMIT 20
                """, maxAttempts, queuedSeconds, runningSeconds);
    }

    public List<Map<String, Object>> listByRelease(long releaseId, int limit) {
        return jdbc.queryForList("""
                SELECT id, release_id, event_id, prompt_key, version, state, attempts,
                       threshold, score, passed, detail, created_at
                FROM gate_job WHERE release_id = ? ORDER BY id DESC LIMIT ?
                """, releaseId, limit);
    }

    public Map<String, Object> stats() {
        return Map.of(
                "jobs", jdbc.queryForObject("SELECT COUNT(*) FROM gate_job", Long.class),
                "undecided", jdbc.queryForObject(
                        "SELECT COUNT(*) FROM gate_job WHERE passed IS NULL", Long.class),
                "passed", jdbc.queryForObject(
                        "SELECT COUNT(*) FROM gate_job WHERE passed = 1", Long.class),
                "failed", jdbc.queryForObject(
                        "SELECT COUNT(*) FROM gate_job WHERE passed = 0", Long.class));
    }
}
