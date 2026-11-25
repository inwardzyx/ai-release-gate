package com.arg.gate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * gate-svc：门禁调度。
 *
 * <p>职责：消费"发布已灰度"的事件 → 建一条门禁任务（幂等）→ 后续调用 Python 执行器跑评测。
 * <b>不负责</b>发布状态的写回（那是 release-svc 的事）——它只把结果通过回调交回去，
 * 这样"谁改状态"只有一个地方，状态机不会被两个服务同时改。
 */
@SpringBootApplication
@EnableScheduling
public class GateSvcApplication {

    public static void main(String[] args) {
        SpringApplication.run(GateSvcApplication.class, args);
    }
}
