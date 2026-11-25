package com.arg.gate.consumer;

import com.arg.gate.repo.GateJobRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Redis Streams 消费者（consumer group = gate）。
 *
 * <p>★★ 这段代码要回答三个"面试必问"：
 * <ol>
 *   <li><b>为什么是至少一次，不是恰好一次</b>：Streams 没有"恰好一次"。
 *       消息可能被重复投递（消费者 ack 前崩溃 → 消息留在 pending 里 → 被 claim 重投）。
 *       所以"恰好做一次"必须落在**业务表**上：{@code uk_event} 唯一索引。</li>
 *   <li><b>先建任务再 ack，顺序不能反</b>：反过来的话，ack 成功、处理失败 = 消息永久丢失。
 *       正确顺序是"处理成功 → ack"，最坏情况是"处理成功但没 ack"⇒ 重复投递 ⇒ 被唯一索引挡掉。</li>
 *   <li><b>pending 里卡住的消息怎么办</b>：本 MVP 不自动 claim（那需要定时扫 pending + 超时判断），
 *       而是暴露 {@code /api/gate/stats} 让人能看见 pending 数。
 *       <b>看不见的故障比不会自动恢复更糟</b> —— 先能观测，再谈自愈。</li>
 * </ol>
 *
 * <p>★ 为什么用 {@code @Scheduled} 每 500ms 拉一次而不是阻塞式 read：
 * 阻塞 read 会占住一个线程并且难以优雅停机；MVP 用轮询，代价是 0~500ms 的额外延迟 ——
 * 门禁本来就是分钟级任务，这点延迟不值得为它引入线程模型复杂度。
 */
@Component
public class GateConsumer {

    private static final Logger log = LoggerFactory.getLogger(GateConsumer.class);

    private final StringRedisTemplate redis;
    private final GateJobRepository repo;
    private final ObjectMapper json;
    private final String stream;
    private final String group;
    private final String consumerName;

    public GateConsumer(StringRedisTemplate redis,
                        GateJobRepository repo,
                        ObjectMapper json,
                        @Value("${arg.stream.release-events}") String stream,
                        @Value("${arg.stream.group}") String group,
                        @Value("${arg.stream.consumer}") String consumerName) {
        this.redis = redis;
        this.repo = repo;
        this.json = json;
        this.stream = stream;
        this.group = group;
        this.consumerName = consumerName;
    }

    /** 启动时确保 group 存在（幂等；已存在会抛 BUSYGROUP，忽略即可）。 */
    @PostConstruct
    void ensureGroup() {
        try {
            redis.opsForStream().createGroup(stream, ReadOffset.from("0"), group);
            log.info("已创建 consumer group {} on {}", group, stream);
        } catch (Exception e) {
            // BUSYGROUP = 已存在；其他错误也只记日志，不阻止服务启动
            log.info("consumer group 已存在或创建失败（忽略）：{}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 500)
    public void loop() {
        try {
            consumeOnce();
        } catch (Exception e) {
            log.warn("消费异常（下一轮重试）：{}", e.toString());
        }
    }

    /**
     * 拉一批并处理。抽成 public 是为了让测试能**确定性地**触发一次
     * （不要用 sleep 等定时器 —— 那样的测试会既慢又不稳）。
     *
     * @return 本轮处理的消息条数
     */
    public int consumeOnce() {
        List<MapRecord<String, Object, Object>> records = read();
        if (records == null || records.isEmpty()) {
            return 0;
        }
        for (MapRecord<String, Object, Object> rec : records) {
            handle(rec);
            // ★ ack 必须在处理成功之后：先 ack 后处理 = 处理失败时消息永久丢失
            redis.opsForStream().acknowledge(stream, group, rec.getId());
        }
        return records.size();
    }

    private List<MapRecord<String, Object, Object>> read() {
        // ★ 手动 ack：StreamReadOptions 里 autoAcknowledge(false)，读完自己 ack
        StreamReadOptions opts = StreamReadOptions.empty()
                .noack()
                .block(Duration.ofMillis(100));
        return redis.opsForStream().read(
                Consumer.from(group, consumerName),
                opts,
                StreamOffset.create(stream, ReadOffset.lastConsumed()));
    }

    private void handle(MapRecord<String, Object, Object> rec) {
        String eventId = (String) rec.getValue().get("eventId");
        String payload = (String) rec.getValue().get("payload");
        if (eventId == null) {
            log.warn("消息缺 eventId，跳过 recordId={}", rec.getId());
            return;
        }
        long releaseId = -1;
        try {
            JsonNode node = json.readTree(payload == null ? "{}" : payload);
            releaseId = node.path("releaseId").asLong(-1);
        } catch (Exception e) {
            log.warn("payload 解析失败 eventId={} payload={}", eventId, payload);
        }
        boolean created = repo.insertIfAbsent(releaseId, eventId);
        if (created) {
            log.info("建门禁任务 eventId={} releaseId={}", eventId, releaseId);
        } else {
            // 重复投递是**正常路径**（至少一次的必然结果），用 info 而不是 warn，别制造噪声
            log.info("事件重复，已幂等跳过 eventId={}", eventId);
        }
    }
}


