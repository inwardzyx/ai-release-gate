package com.arg.gate.web;

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
 * traceId 过滤器（与 config-svc 那个同款，**故意复制而不是抽到 common**）。
 *
 * <p>★ 为什么不抽成公共库：这是 servlet 过滤器，抽到 {@code common} 就得让 common 依赖 spring-web，
 * 而 common 现在的定位是"只放契约、不带框架"。为了省 20 行重复代码，让一个"谁都能依赖"的模块
 * 背上 Web 框架依赖 —— 这是共享库腐化的经典第一步。
 * 判断标准：**重复的是实现细节，还是契约？** 契约（ApiError）共享，过滤器属于实现。
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
            MDC.remove(MDC_KEY);
        }
    }
}

