package com.arg.release;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * release-svc：发布、灰度、回滚。
 *
 * <p>与 config-svc 的分工（这条边界是本项目要讲的第一个"为什么拆"）：
 * <ul>
 *   <li>config-svc：**读多写少**——每次请求都要读当前版本，所以它适合加缓存、横向扩；</li>
 *   <li>release-svc：**写少但要事务**——一天几次发布，每次都要"改指针 + 改状态 + 写审计"原子完成。</li>
 * </ul>
 * 混在一个服务里，发布时的事务会锁住读路径的连接池，读高峰时发布会把整个问答拖慢。
 */
@SpringBootApplication
public class ReleaseSvcApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReleaseSvcApplication.class, args);
    }
}
