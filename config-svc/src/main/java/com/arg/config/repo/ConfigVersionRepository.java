package com.arg.config.repo;

import com.arg.config.domain.ConfigVersion;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 配置版本的持久层。
 *
 * <p>★★ 版本号怎么分配（这段是踩过坑才写成的，别改回去）：
 * <ol>
 *   <li><b>第一版（错）</b>：`SELECT COALESCE(MAX(version),0)+1` + 唯一索引 + 冲突重试 3 次。
 *       2026-11-25 实测 30 并发：**19/30 返回 500**（`Duplicate entry 'conc_key-11'`）。
 *       正确性没丢（库里 11 行、版本号无重复），但可用性丢了 —— 重试次数追不上竞争。</li>
 *   <li><b>现版</b>：先在一张 <b>计数器表</b> 上把版本号"领出来"——
 *       {@code INSERT ... ON DUPLICATE KEY UPDATE version = version + 1}，
 *       同 key 的请求在这一行上串行，天然无竞态，也不需要重试。</li>
 * </ol>
 * 为什么这样是对的：`INSERT ... ON DUPLICATE KEY UPDATE` 是**单语句原子操作**，
 * 它在那一行上加 X 锁到事务结束；随后同一事务里的 `SELECT` 读到的就是我们自己领的号。
 * ★ 所以这个方法**必须** {@code @Transactional}（否则两个 JdbcTemplate 调用可能拿到不同连接，
 * 锁就没覆盖到 SELECT，等于没做）。
 *
 * <p>唯一索引 {@code uk_key_ver} 仍然保留：它是**最后防线**，不是主要手段。
 * 主要手段变了，判据不变 —— 30 并发下 1..30 每个号恰好出现一次。
 */
@Repository
public class ConfigVersionRepository {

    private static final String SELECT_COLS = """
            SELECT id, prompt_key, version, payload, gate_status, created_by, created_at
            FROM cfg_version
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<ConfigVersion> mapper;

    public ConfigVersionRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.mapper = (rs, i) -> new ConfigVersion(
                rs.getLong("id"),
                rs.getString("prompt_key"),
                rs.getInt("version"),
                readJson(rs.getString("payload")),
                rs.getString("gate_status"),
                rs.getString("created_by"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    /**
     * 建一个版本。相同 {@code idemKey} 的重复请求返回**同一个**版本（幂等）。
     */
    @Transactional
    public CreateOutcome create(String promptKey, JsonNode payload, String createdBy, String idemKey) {
        // ① 幂等快路径：同一个 Idempotency-Key 已经建过 ⇒ 直接返回它（200，不是 201）
        if (idemKey != null) {
            Optional<ConfigVersion> existing = findByIdemKey(idemKey);
            if (existing.isPresent()) {
                return new CreateOutcome(existing.get(), true);
            }
        }

        int version = allocateVersion(promptKey);

        try {
            jdbc.update("""
                            INSERT INTO cfg_version (prompt_key, version, payload, created_by, idempotency_key)
                            VALUES (?, ?, ?, ?, ?)
                            """,
                    promptKey, version, payload.toString(), createdBy, idemKey);
        } catch (DuplicateKeyException e) {
            // 走到这里说明有两件事之一：
            //   ① 并发下同一个 idemKey 被两个事务同时放进来（幂等快路径的窗口）—— 返回已存在的那个；
            //   ② 计数器与版本表不一致（理论上不会）—— 抛出去，让人看见，别静默。
            if (idemKey != null) {
                Optional<ConfigVersion> existing = findByIdemKey(idemKey);
                if (existing.isPresent()) {
                    return new CreateOutcome(existing.get(), true);
                }
            }
            throw e;
        }
        return new CreateOutcome(findLatest(promptKey).orElseThrow(), false);
    }

    /**
     * 领一个版本号（原子）。
     *
     * <p>计数器列的含义是"**已分配到的最大版本号**"：
     * 首次插入 1（即第 1 版），之后每次 +1。所以 {@code SELECT} 读到的就是本次领到的号。
     */
    private int allocateVersion(String promptKey) {
        jdbc.update("""
                INSERT INTO cfg_counter (prompt_key, version) VALUES (?, 1)
                ON DUPLICATE KEY UPDATE version = version + 1
                """, promptKey);
        Integer allocated = jdbc.queryForObject(
                "SELECT version FROM cfg_counter WHERE prompt_key = ?", Integer.class, promptKey);
        if (allocated == null) {
            throw new IllegalStateException("计数器读不到刚插入的行: " + promptKey);
        }
        return allocated;
    }

    public Optional<ConfigVersion> findByIdemKey(String idemKey) {
        return jdbc.query(SELECT_COLS + " WHERE idempotency_key = ?", mapper, idemKey)
                .stream().findFirst();
    }

    public Optional<ConfigVersion> findLatest(String promptKey) {
        return jdbc.query(SELECT_COLS + " WHERE prompt_key = ? ORDER BY version DESC LIMIT 1",
                        mapper, promptKey)
                .stream().findFirst();
    }

    public Optional<ConfigVersion> findByVersion(String promptKey, int version) {
        return jdbc.query(SELECT_COLS + " WHERE prompt_key = ? AND version = ?", mapper, promptKey, version)
                .stream().findFirst();
    }

    public List<ConfigVersion> list(String promptKey, int limit) {
        return jdbc.query(SELECT_COLS + " WHERE prompt_key = ? ORDER BY version DESC LIMIT ?",
                mapper, promptKey, limit);
    }

    private JsonNode readJson(String text) {
        try {
            return json.readTree(text);
        } catch (Exception e) {
            // 库里的内容坏掉是"数据损坏"，不是"请求错误"——必须炸出来，不能当成空对象吞掉
            throw new IllegalStateException("cfg_version.payload 不是合法 JSON", e);
        }
    }

    /** 建结果：版本本身 + 是否命中幂等。 */
    public record CreateOutcome(ConfigVersion version, boolean idempotentHit) {
    }
}

