package com.arg.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * config-svc：配置版本服务。
 *
 * <p>职责边界（写死在这里，免得后面手滑把发布逻辑塞进来）：
 * <ul>
 *   <li><b>只做</b>：版本号分配、版本内容存储、按 key 查询/最新版本；</li>
 *   <li><b>不做</b>：发布、灰度比例、回滚 —— 那些是 release-svc 的事。</li>
 * </ul>
 * 为什么能这样切：配置的读频率是"每次请求一次"，发布的写频率是"一天几次"，
 * 两者的缓存策略、事务边界、扩缩容需求完全不同。
 */
@SpringBootApplication
public class ConfigSvcApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConfigSvcApplication.class, args);
    }
}
