-- gate-svc 自己的库（arg_gate）。
--
-- ★ uk_event 是本项目"幂等"的物理证据：
--   消息系统给的是**至少一次**（Redis Streams 的 pending/claim 会重投），
--   所以"同一件事只做一次"必须由消费端自己保证，靠的就是这一条唯一索引。
--   判据：同一 eventId 投 100 次 ⇒ 这张表只有 1 行。
CREATE TABLE IF NOT EXISTS gate_job (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    release_id BIGINT      NOT NULL,
    event_id   VARCHAR(64) NOT NULL,
    state      VARCHAR(16) NOT NULL DEFAULT 'QUEUED',   -- QUEUED / RUNNING / PASS / FAIL / DEAD
    attempts   INT         NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_event (event_id),
    KEY idx_release (release_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
