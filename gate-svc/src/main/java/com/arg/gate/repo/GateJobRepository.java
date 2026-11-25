package com.arg.gate.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 门禁任务的持久层。核心只有一个方法：**幂等建任务**。
 */
@Repository
public class GateJobRepository {

    private final JdbcTemplate jdbc;

    public GateJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 若该 eventId 还没有任务，就建一条；已存在则什么都不做。
     *
     * @return true = 这次真的新建了（用来区分"处理了"和"重复，跳过"）
     */
    public boolean insertIfAbsent(long releaseId, String eventId) {
        try {
            jdbc.update("INSERT INTO gate_job (release_id, event_id) VALUES (?, ?)", releaseId, eventId);
            return true;
        } catch (DuplicateKeyException e) {
            // 重复投递：这是**正常路径**，不是错误。唯一的判断依据是 uk_event 唯一索引。
            return false;
        }
    }

    public List<Map<String, Object>> listByRelease(long releaseId, int limit) {
        return jdbc.queryForList("""
                SELECT id, release_id, event_id, state, attempts, created_at
                FROM gate_job WHERE release_id = ? ORDER BY id DESC LIMIT ?
                """, releaseId, limit);
    }

    public Map<String, Object> stats() {
        return Map.of(
                "jobs", jdbc.queryForObject("SELECT COUNT(*) FROM gate_job", Long.class),
                "events", jdbc.queryForObject("SELECT COUNT(DISTINCT event_id) FROM gate_job", Long.class));
    }
}
