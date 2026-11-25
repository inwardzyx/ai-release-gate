package com.arg.gate.api;

import com.arg.gate.consumer.GateConsumer;
import com.arg.gate.repo.GateJobRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 门禁服务的查询与运维接口。
 *
 * <p>★ {@code POST /api/gate/consume} 是给**测试**用的显式触发：
 * 生产靠 {@code @Scheduled} 自动消费，但判据不能依赖 sleep 等定时器 ——
 * 那样的测试既慢又容易偶发失败。抽一个"处理一轮"的入口，判据就变成确定性的。
 * 这不是"给测试开后门"：它调用的就是定时任务调用的同一个方法。
 */
@RestController
@RequestMapping("/api/gate")
public class GateController {

    private final GateConsumer consumer;
    private final GateJobRepository repo;
    private final StringRedisTemplate redis;
    private final String stream;

    public GateController(GateConsumer consumer, GateJobRepository repo, StringRedisTemplate redis,
                          @org.springframework.beans.factory.annotation.Value("${arg.stream.release-events}") String stream) {
        this.consumer = consumer;
        this.repo = repo;
        this.redis = redis;
        this.stream = stream;
    }

    @PostMapping("/consume")
    public Map<String, Object> consumeOnce() {
        int n = consumer.consumeOnce();
        return Map.of("consumed", n);
    }

    @GetMapping("/jobs")
    public List<Map<String, Object>> jobs(@RequestParam long releaseId,
                                          @RequestParam(defaultValue = "20") int limit) {
        return repo.listByRelease(releaseId, Math.max(1, Math.min(limit, 100)));
    }

    /**
     * 运维视角：任务数、事件数、**pending 数**。
     * pending 是"已投递但没被 ack"的消息数 —— 它持续涨就说明消费者卡住了，
     * 这是至少一次语义下最该被看见的指标。
     */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> s = new java.util.LinkedHashMap<>(repo.stats());
        try {
            s.put("pending", redis.opsForStream().pending(stream, "gate").getTotalPendingMessages());
        } catch (Exception e) {
            s.put("pending", -1);     // ★ 拿不到就写 -1（缺失），不许补 0
            s.put("pendingError", e.getMessage());
        }
        return s;
    }

    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of("service", "gate-svc", "status", "ok");
    }
}

