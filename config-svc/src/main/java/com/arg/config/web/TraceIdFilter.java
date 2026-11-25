package com.arg.config.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 给每个请求一个 traceId：有上游传的 {@code X-Request-Id} 就沿用，没有就生成 8 位。
 *
 * <p>★ 为什么现在就做（W1 就做，不等"以后"）：
 * 这个项目有 4 个进程，"一次发布"会在 4 份日志里各留几行。没有 traceId 时排查只能靠时间戳猜，
 * 而加上它只是 20 行代码 —— 这类"以后再说"的可观测性，最后通常变成"永远没有"。
 *
 * <p>★ 为什么用它而不是引入完整链路追踪（Zipkin/Micrometer Tracing）：
 * MVP 阶段要的是"能把两个进程的日志串起来"，不是火焰图。先上最小的，别让依赖拖慢进度。
 *
 * <p>★ 必须 {@code response.setHeader} 回传：否则出错时前端拿不到 id，
 * 客服从页面上报"出错了"却给不出 traceId，等于白做。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String incoming = req.getHeader(HEADER);
        String traceId = (incoming != null && !incoming.isBlank())
                ? incoming.trim()
                : UUID.randomUUID().toString().substring(0, 8);

        MDC.put(MDC_KEY, traceId);
        res.setHeader(HEADER, traceId);
        try {
            chain.doFilter(req, res);
        } finally {
            // ★ 必须清：Tomcat 复用线程，不清会把上一条请求的 traceId 带进下一条
            MDC.remove(MDC_KEY);
        }
    }
}
