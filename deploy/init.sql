-- 本项目自己的库：每个服务一个 schema（物理同实例、逻辑分库）
--   arg_config  —— config-svc（配置版本）
--   arg_release —— release-svc（发布/灰度指针 + outbox）
--   arg_gate    —— gate-svc（门禁任务）
CREATE DATABASE IF NOT EXISTS arg_config  DEFAULT CHARSET utf8mb4;
CREATE DATABASE IF NOT EXISTS arg_release DEFAULT CHARSET utf8mb4;
CREATE DATABASE IF NOT EXISTS arg_gate    DEFAULT CHARSET utf8mb4;

CREATE USER IF NOT EXISTS 'arg'@'%' IDENTIFIED BY 'arg_dev_2026';
GRANT ALL PRIVILEGES ON arg_config.*  TO 'arg'@'%';
GRANT ALL PRIVILEGES ON arg_release.* TO 'arg'@'%';
GRANT ALL PRIVILEGES ON arg_gate.*    TO 'arg'@'%';
FLUSH PRIVILEGES;
