package com.apigw.proxy.cors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 网关统一跨域过滤器，顺序在<b>所有业务过滤器之前</b>（含接入鉴权与转发）：
 *
 * 1. 预检请求（OPTIONS + Origin + Access-Control-Request-Method）：直接由网关回 200 并带齐
 *    ACAO / Allow-Methods / Allow-Headers / Max-Age，<b>不进鉴权、不匹配路由、不打上游</b>。
 *    否则浏览器的预检会被 401/404 顶掉，真正的业务请求根本发不出来。
 * 2. 实际请求：来源合法时，在响应提交前往头上补 ACAO 与 Access-Control-Expose-Headers——
 *    无论后面是谁写响应（转发上游、还是网关自己的 401/502），CORS 头都在，前端跨域读得到
 *    身份头（上游回显时）、X-Gateway-Trace-Id 与 X-Gateway-Error。
 *
 * 没有 Origin 的请求不是跨域，原样放行、不添头。
 */
@Slf4j
@Component
@EnableConfigurationProperties(GatewayCorsProperties.class)
public class GatewayCorsWebFilter implements WebFilter, Ordered {

    /** 比接入鉴权（HIGHEST_PRECEDENCE + 5）和转发（+10）都早：预检在这里直接答掉。 */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE;

    private final GatewayCorsProperties properties;

    public GatewayCorsWebFilter(GatewayCorsProperties properties) {
        this.properties = properties;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!properties.enabled()) {
            return chain.filter(exchange);
        }
        ServerHttpRequest request = exchange.getRequest();
        String origin = request.getHeaders().getOrigin();
        if (origin == null || origin.isBlank()) {
            // 非跨域请求：什么都不加
            return chain.filter(exchange);
        }

        if (isPreflight(request)) {
            if (!isOriginAllowed(origin)) {
                log.debug("跨域预检来源不在允许名单：{}", origin);
                exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                return exchange.getResponse().setComplete();
            }
            HttpHeaders h = exchange.getResponse().getHeaders();
            h.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, allowOriginValue(origin));
            h.set(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, String.join(", ", properties.allowedMethods()));
            // 配了 "*" 就把浏览器实际申请的头原样放行（Authorization 等都能过）；
            // 否则回显配置的固定名单
            String requestedHeaders = request.getHeaders()
                    .getFirst(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS);
            if (properties.allowedHeaders().contains("*") && requestedHeaders != null && !requestedHeaders.isBlank()) {
                h.set(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, requestedHeaders);
            } else {
                h.set(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, String.join(", ", properties.allowedHeaders()));
            }
            h.set(HttpHeaders.ACCESS_CONTROL_MAX_AGE, Long.toString(properties.maxAge().toSeconds()));
            h.add(HttpHeaders.VARY, HttpHeaders.ORIGIN);
            h.add(HttpHeaders.VARY, HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD);
            h.add(HttpHeaders.VARY, HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS);
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            // 预检没有任何业务语义，不带身体
            return exchange.getResponse().setComplete();
        }

        if (isOriginAllowed(origin)) {
            final String allowOrigin = allowOriginValue(origin);
            final String exposed = String.join(", ", properties.exposedHeaders());
            // 提交前补头：转发成功、上游 4xx/5xx、网关 401/502/504 任何结局都带上
            exchange.getResponse().beforeCommit(() -> {
                HttpHeaders h = exchange.getResponse().getHeaders();
                h.set(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, allowOrigin);
                h.set(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, exposed);
                h.add(HttpHeaders.VARY, HttpHeaders.ORIGIN);
                return Mono.empty();
            });
        }
        return chain.filter(exchange);
    }

    /** 预检：OPTIONS 方法 + Origin + Access-Control-Request-Method，三者缺一不可。 */
    private boolean isPreflight(ServerHttpRequest request) {
        return request.getMethod() == org.springframework.http.HttpMethod.OPTIONS
                && request.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD) != null;
    }

    /** 精确匹配来源；配置含 "*" 时任意来源放行。 */
    private boolean isOriginAllowed(String origin) {
        List<String> allowed = properties.allowedOrigins();
        if (allowed.contains("*")) {
            return true;
        }
        return allowed.contains(origin.trim());
    }

    /** 通配配法回 "*"（不带凭证时浏览器接受）；显式名单回显精确来源。 */
    private String allowOriginValue(String origin) {
        return properties.allowedOrigins().contains("*") ? "*" : origin.trim();
    }
}
