package com.arg.gate.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Python 评测执行器的客户端。
 *
 * <p>★ 语义是"提交 + 回调"：这里只等 **202**（收下了），不等评测跑完。
 * 超时给 2 秒（提交是轻操作）；真正的结果走回调。
 * <b>不需要长超时</b> —— 长超时只会让 gate-svc 的线程陪着等，而结果本来就不是同步来的。
 */
@Component
public class ExecutorClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(500)).build();
    private final ObjectMapper json;
    private final String base;

    public ExecutorClient(ObjectMapper json, @Value("${arg.executor.base-url}") String base) {
        this.json = json;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /**
     * 提交一次评测。
     *
     * @param jobId 幂等键：执行器对同一个 jobId 只跑一次
     */
    public void submit(long jobId, long releaseId, String promptKey, int version, BigDecimal threshold) {
        try {
            String body = json.writeValueAsString(Map.of(
                    "jobId", String.valueOf(jobId),
                    "releaseId", releaseId,
                    "promptKey", promptKey,
                    "version", version,
                    "threshold", threshold));
            HttpResponse<String> r = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/eval/submit"))
                            .timeout(Duration.ofMillis(2000))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                throw new IllegalStateException("执行器拒绝提交 status=" + r.statusCode() + " body=" + r.body());
            }
        } catch (Exception e) {
            throw new IllegalStateException("提交评测失败：" + e.getMessage(), e);
        }
    }
}
