package com.arg.release.client;

import com.arg.release.web.BizException;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * config-svc 的客户端。**服务间调用的三条纪律都在这里**：
 *
 * <ol>
 *   <li><b>必须有超时</b>：不设超时的 HTTP 调用，在对方"半死"（TCP 能连、不回包）时会把线程占住，
 *       把发布接口一起拖死。连接 500ms、读 1200ms，两个都设。</li>
 *   <li><b>失败要 fail closed</b>：拿不到"这个版本存不存在"，就**不许发布**。
 *       发布是不可逆动作，宁可拒绝也不能猜。</li>
 *   <li><b>traceId 要透传</b>：否则在 config-svc 的日志里找不到这次校验，
 *       跨服务排查就断了。</li>
 * </ol>
 *
 * <p>★ 为什么用 JDK 的 {@code HttpClient} 而不是 Spring 的 RestClient：
 * 这个项目里不需要流式，但需要"明确的超时 + 明确的异常"；JDK 的 API 把这两件事摆在明面上，
 * 出错时是 {@code HttpTimeoutException} / {@code ConnectException}，不需要猜框架把异常包成了什么。
 */
@Component
public class ConfigClient {

    private final HttpClient http;
    private final String baseUrl;

    public ConfigClient(@Value("${arg.config-svc.base-url}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .build();
    }

    /**
     * 校验"这个 promptKey 的这个版本存在"。
     *
     * @throws BizException 版本不存在（400）或上游不可用（503）
     */
    public void requireVersion(String promptKey, int version) {
        String path = "/api/config/versions/" + enc(promptKey) + "/" + version;
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofMillis(1200))
                .header("X-Request-Id", traceId())
                .GET()
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                return;
            }
            if (resp.statusCode() == 404) {
                throw BizException.badRequest("VERSION_NOT_FOUND",
                        "config-svc 里没有这个版本: " + promptKey + "@" + version);
            }
            throw BizException.upstreamUnavailable(
                    "config-svc 返回了 " + resp.statusCode() + "，无法确认版本存在，拒绝发布");
        } catch (java.net.http.HttpTimeoutException e) {
            throw BizException.upstreamUnavailable("config-svc 超时（>1200ms），拒绝发布");
        } catch (java.io.IOException e) {
            throw BizException.upstreamUnavailable("config-svc 不可达：" + e.getMessage() + "，拒绝发布");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // ★ 中断状态要还原，别吞掉
            throw BizException.upstreamUnavailable("调用 config-svc 被中断");
        }
    }

    private static String traceId() {
        String id = MDC.get("traceId");
        return id != null ? id : "-";
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
