-- config-svc 自己的库（arg_config）。物理同实例、逻辑分库。
--
-- ★ 为什么不用 Flyway（至少 W1 不用）：本机 ~/.m2 里没有 flyway-core，
--   引它要联网下载；而 W1 的目标是"表 + 接口能跑"。生产化的迁移工具放到 W3 再换，
--   现在用 `CREATE TABLE IF NOT EXISTS` + spring.sql.init 保持幂等，够用且零依赖。
--
-- ★ 每条约束都对应一个**并发/幂等**的判据（不是"随手加的索引"）：
--   uk_key_ver —— 同一 promptKey 的版本号不会重复（`MAX+1` 竞态的最后防线）
--   uk_idem    —— 同一幂等键只落一行（客户端重试不会建出两个版本）

CREATE TABLE IF NOT EXISTS cfg_version (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    prompt_key      VARCHAR(64)  NOT NULL,
    version         INT          NOT NULL,
    payload         JSON         NOT NULL,
    gate_status     VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_by      VARCHAR(64)  NOT NULL,
    idempotency_key VARCHAR(120) NULL,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_key_ver (prompt_key, version),
    UNIQUE KEY uk_idem (idempotency_key),
    KEY idx_key_created (prompt_key, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;

-- ★ 发号器（2026-11-25 加，起因是一次真实的失败）：
--   第一版用 `SELECT COALESCE(MAX(version),0)+1` + 唯一索引 + 重试 3 次。
--   30 并发下标版本接口 **19/30 返回 500**（Duplicate entry 'conc_key-11'）——
--   正确性没丢（库里有 11 个版本、无重复），但可用性丢了。
--   改成"一行一 key 的计数器"：同 key 的请求在这一行上串行，天然无竞态、不需要重试。
--   唯一索引 uk_key_ver 仍然保留 —— 它是最后防线，不是主要手段。
CREATE TABLE IF NOT EXISTS cfg_counter (
    prompt_key    VARCHAR(64) NOT NULL,
    version       INT         NOT NULL,   -- 语义：**已分配到的最大版本号**（不是下一个）
    PRIMARY KEY (prompt_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
