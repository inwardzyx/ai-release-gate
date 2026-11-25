-- 本项目自己的库：每个服务一个 schema（物理同实例、逻辑分库）
CREATE DATABASE IF NOT EXISTS arg_config  DEFAULT CHARSET utf8mb4;
CREATE DATABASE IF NOT EXISTS arg_release DEFAULT CHARSET utf8mb4;
CREATE USER IF NOT EXISTS 'arg'@'%' IDENTIFIED BY 'arg_dev_2026';
GRANT ALL PRIVILEGES ON arg_config.*  TO 'arg'@'%';
GRANT ALL PRIVILEGES ON arg_release.* TO 'arg'@'%';
FLUSH PRIVILEGES;
