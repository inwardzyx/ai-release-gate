-- release-svc 自己的库（arg_release）。与 config-svc 的库**不共享表**。
--
-- ★ 两张表各管一件事，这是"指针切换式回滚"的基础：
--   release        —— 事件流水（每次发布/灰度/回滚各一行状态）
--   current_release —— **当前生效的指针**（每个 env+promptKey 只有一行）
--   回滚 = 把指针改回上一个版本（一次 UPDATE），而不是"重新发一遍"。
--   这样回滚是 O(1)：不碰配置内容、不重启、不改代码。

CREATE TABLE IF NOT EXISTS `release` (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    env             VARCHAR(16)  NOT NULL,
    prompt_key      VARCHAR(64)  NOT NULL,
    version         INT          NOT NULL,
    state           VARCHAR(16)  NOT NULL,          -- PENDING / CANARY / FULL / ROLLED_BACK
    rollout_pct     TINYINT      NOT NULL DEFAULT 0,
    prev_release_id BIGINT       NULL,              -- 回滚目标（本次发布前生效的那个）
    rev             INT          NOT NULL DEFAULT 0,-- ★ 乐观锁
    idempotency_key VARCHAR(120) NULL,
    operator        VARCHAR(64)  NOT NULL,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_idem (idempotency_key),
    KEY idx_env_key (env, prompt_key, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS current_release (
    env        VARCHAR(16) NOT NULL,
    prompt_key VARCHAR(64) NOT NULL,
    release_id BIGINT      NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (env, prompt_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
