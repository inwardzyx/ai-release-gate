package com.arg.gate.consumer;

import com.arg.gate.client.ReleaseClient;
import com.arg.gate.repo.GateJobRepository;
import com.arg.gate.service.GateService;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Redis Streams 消费者（consumer group = gate）。
 *
 * <p>★★ 这段代码要回答三个"面试必问"：
 * <ol>
 *   <li><b>为什么是至少一次，不是恰好一次</b>：Streams 没有"恰好一次"。
 *       消费者 ack 前崩溃 ⇒ 消息留在 pending ⇒ 被重投。所以"恰好做一次"必须落在**业务表**上：
 *       {@code uk_event} 唯一索引。</li>
 *   <li><b>先处理成功再 ack，顺序不能反</b>：先 ack 后处理，一旦处理失败消息就永久丢了。
 *       反过来最坏只是重复投递 ⇒ 被唯一索引挡掉。</li>
 *   <li><b>处理失败就不 ack</b>：消息留在 pending 里等人处理（本版靠 {@code /api/gate/stats}
 *       暴露 pending 数，不自动 claim —— 先能看见，再谈自愈）。</li>
 * </ol>
 */
@Component
public class GateConsumer {

    private static final Logger log = LoggerFactory.getLogger(GateConsumer.class);

    private final StringRedisTemplate redis;
    private final GateJobRepository repo;
    private final GateService gateService;
    private final ReleaseClient releaseClient;
    private final ObjectMapper json;
    private final String stream;
    private final String group;
    private final String consumerName;

    public GateConsumer(StringRedisTemplate redis,
                        GateJobRepository repo,
                        GateService gateService,
                        ReleaseClient releaseClient,
                        ObjectMapper json,
                        @Value("${arg.stream.release-events}") String stream,
                        @Value("${arg.stream.group}") String group,
                        @Value("${arg.stream.consumer}") String consumerName) {
        this.redis = redis;
        this.repo = repo;
        this.gateService = gateService;
        this.releaseClient = releaseClient;
        this.json = json;
        this.stream = stream;
        this.group = group;
        this.consumerName = consumerName;
    }

    /**
     * 启动时确保 group 存在。
     *
     * <p>★ 踩过的坑（2026-11-25）：Redis 的 {@code XGROUP CREATE} 在**stream 还不存在**时会失败
     * （除非带 MKSTREAM）。Spring Data Redis 的 {@code createGroup} 不带 MKSTREAM，
     * 于是"服务先起、第一条发布后起"这种最正常的启动顺序下，消费组根本没建成 ——
     * 之后每次 read 都是 NOGROUP。修法：先写一条占位消息把 stream 建出来，建完组再删掉占位。
     */
    @PostConstruct
    void ensureGroup() {
        try {
            if (Boolean.FALSE.equals(redis.hasKey(stream))) {
                var placeholder = redis.opsForStream().add(
                        org.springframework.data.redis.connection.stream.StreamRecords.newRecord()
                                .in(stream).ofMap(java.util.Map.of("eventId", "__bootstrap__", "payload", "{}")));
                try {
                    redis.opsForStream().createGroup(stream, ReadOffset.from("0"), group);
                } finally {
                    if (placeholder != null) {
                        redis.opsForStream().delete(stream, placeholder);   // 占位消息用完就删
                    }
                }
            } else {
                redis.opsForStream().createGroup(stream, ReadOffset.from("0"), group);
            }
            log.info("已创建 consumer group {} on {}", group, stream);
        } catch (Exception e) {
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
     * （不要用 sleep 等定时器 —— 那样的测试又慢又容易偶发失败）。
     *
     * @return 本轮成功处理（并 ack）的消息条数
     */
    public int consumeOnce() {
        List<MapRecord<String, Object, Object>> records = read();
        if (records == null || records.isEmpty()) {
            return 0;
        }
        int done = 0;
        for (MapRecord<String, Object, Object> rec : records) {
            try {
                handle(rec);
                redis.opsForStream().acknowledge(stream, group, rec.getId());
                done++;
            } catch (Exception e) {
                // ★ 不 ack：留在 pending，等人工/后续 claim。宁可重复，不可丢失。
                log.warn("处理失败，保持 pending recordId={} err={}", rec.getId(), e.toString());
            }
        }
        return done;
    }

    private List<MapRecord<String, Object, Object>> read() {
        StreamReadOptions opts = StreamReadOptions.empty()
                .noack()                       // ★ 手动 ack
                .block(Duration.ofMillis(100));
        return redis.opsForStream().read(
                Consumer.from(group, consumerName), opts,
                StreamOffset.create(stream, ReadOffset.lastConsumed()));
    }

    private void handle(MapRecord<String, Object, Object> rec) {
        String eventId = (String) rec.getValue().get("eventId");
        String payload = (String) rec.getValue().get("payload");
        if (eventId == null) {
            log.warn("消息缺 eventId，跳过 recordId={}", rec.getId());
            return;
        }
        JsonNode node;
        try {
            node = json.readTree(payload == null ? "{}" : payload);
        } catch (Exception e) {
            // payload 坏了：重投也不会好 ⇒ 记 error 但 ack，避免无限重投（由人处理）
            log.error("payload 解析失败，丢弃 eventId={} payload={}", eventId, payload);
            return;
        }
        long releaseId = node.path("releaseId").asLong(-1);
        String promptKey = node.path("promptKey").asText("");
        int version = node.path("version").asInt(-1);
        if (releaseId < 0 || promptKey.isBlank() || version < 0) {
            log.error("事件缺字段，丢弃 eventId={} payload={}", eventId, payload);
            return;
        }

        // 门槛由发布方声明；拿不到就抛 ⇒ 不 ack ⇒ 留在 pending 等重试（不猜默认值）
        BigDecimal threshold = releaseClient.thresholdOf(releaseId);

        Optional<Long> jobId = repo.insertIfAbsent(releaseId, eventId, promptKey, version, threshold);
        if (jobId.isPresent()) {
            log.info("建门禁任务 id={} eventId={} releaseId={}", jobId.get(), eventId, releaseId);
            gateService.dispatch(jobId.get(), releaseId);
        } else {
            log.info("事件重复，已幂等跳过 eventId={}", eventId);
        }
    }
}

