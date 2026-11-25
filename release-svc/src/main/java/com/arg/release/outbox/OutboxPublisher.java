package com.arg.release.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 把 outbox 里未投递的事件推到 Redis Stream。
 *
 * <p>★ 语义是**至少一次**，取这个而不是"恰好一次"，是因为恰好一次需要分布式事务；
 * 而"重复由消费端按 event_id 去重"便宜得多。两条判据各自证明一半：
 * <ul>
 *   <li>投递：这里失败不丢事件（state 还是 NEW，下一轮再试）；</li>
 *   <li>去重：gate-svc 的 {@code uk_event} 唯一索引 + 幂等插入，重复 100 次也只建 1 个任务。</li>
 * </ul>
 *
 * <p>★ 为什么用"定时扫表"而不是"写完立刻 XADD"：
 * 立刻发看起来更快，但它把"事务提交"和"消息投递"绑在一起 —— 事务回滚时消息已经出去了（幽灵事件），
 * 而事务提交后进程崩了消息又丢了。扫表可以在任何时刻重试，代价是最多 300ms 的延迟（门禁本来就是异步的，不差这点）。
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final String stream;

    public OutboxPublisher(JdbcTemplate jdbc,
                           StringRedisTemplate redis,
                           @Value("${arg.stream.release-events}") String stream) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.stream = stream;
    }

    @Scheduled(fixedDelay = 300)
    public void flush() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, event_id, type, payload
                FROM outbox_event
                WHERE state = 'NEW'
                ORDER BY id
                LIMIT 20
                """);
        for (Map<String, Object> row : rows) {
            String eventId = (String) row.get("event_id");
            try {
                redis.opsForStream().add(StreamRecords.newRecord()
                        .in(stream)
                        .ofMap(Map.of(
                                "eventId", eventId,
                                "type", (String) row.get("type"),
                                "payload", String.valueOf(row.get("payload")))));
                jdbc.update("UPDATE outbox_event SET state='SENT', sent_at=NOW(3) WHERE id=?", row.get("id"));
            } catch (Exception e) {
                // 投不出去就停这一轮：保序 + 不放大故障（下一轮再来）
                log.warn("outbox 投递失败，稍后重试 eventId={} err={}", eventId, e.toString());
                return;
            }
        }
        if (!rows.isEmpty()) {
            log.info("outbox 投递 {} 条", rows.size());
        }
    }
}
