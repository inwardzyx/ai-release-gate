package com.arg.gate.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * release-svc 的客户端：查发布的"门禁标准差"、回写门禁结果。
 *
 * <p>★ 门禁标准差不写死在这里：它是**发布方声明**的（每版风险不同）。
 * gate-svc 只负责"按人家定的标准去判"，判完把结论交回去 —— 判决权在发布方，执行权在门禁。
 */
@Component
public class ReleaseClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(500)).build();
    private final ObjectMapper json;
    private final String base;

    public ReleaseClient(ObjectMapper json, @Value("${arg.release-svc.base-url}") String base) {
        this.json = json;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** 取发布的门禁标准差；拿不到就抛（**不猜**默认值 —— 猜错了会按错的标准放行）。 */
    public BigDecimal thresholdOf(long releaseId) {
        try {
            HttpResponse<String> r = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/releases/" + releaseId))
                            .timeout(Duration.ofMillis(1500)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                throw new IllegalStateException("查发布失败 status=" + r.statusCode());
            }
            JsonNode n = json.readTree(r.body());
            return new BigDecimal(n.path("gateThreshold").asText("0.5"));
        } catch (Exception e) {
            throw new IllegalStateException("查发布 " + releaseId + " 失败：" + e.getMessage(), e);
        }
    }

    /** 回写门禁结果（release-svc 会自行决定"通过就留着，不通过就回滚"）。 */
    public void reportGateResult(long releaseId, BigDecimal score, boolean passed) {
        try {
            String body = json.writeValueAsString(java.util.Map.of("score", score, "passed", passed));
            HttpResponse<String> r = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/releases/" + releaseId + "/gate-result"))
                            .timeout(Duration.ofMillis(2000))
                            .header("Content-Type", "application/json")
                            .header("X-Request-Id", traceId())
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                throw new IllegalStateException("回写门禁结果失败 status=" + r.statusCode() + " body=" + r.body());
            }
        } catch (Exception e) {
            throw new IllegalStateException("回写门禁结果失败：" + e.getMessage(), e);
        }
    }

    /** 当前线程的 traceId（空则给 "-"）：跨服务透传靠它。 */
    private static String traceId() {
        String id = org.slf4j.MDC.get("traceId");
        return id != null ? id : "-";
    }}

