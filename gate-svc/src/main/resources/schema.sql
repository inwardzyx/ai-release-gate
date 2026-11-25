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
    -- ★ 快照：任务建的那一刻，配置版本可能之后又变了；门禁必须评"当时那版"
    prompt_key VARCHAR(64) NOT NULL,
    version    INT         NOT NULL,
    state      VARCHAR(16) NOT NULL DEFAULT 'QUEUED',   -- QUEUED / RUNNING / PASS / FAIL / DEAD
    attempts   INT         NOT NULL DEFAULT 0,
    threshold  DECIMAL(4,3) NOT NULL DEFAULT 0.500,   -- 建任务时从发布记录带过来
    score      DECIMAL(6,4) NULL,                      -- 门禁分；NULL = 还没跑完（缺失不是 0）
    passed     TINYINT(1)   NULL,                      -- NULL = 还没判定
    detail     VARCHAR(255) NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_event (event_id),
    KEY idx_release (release_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


