package com.apigw.proxy.auth.user;

import com.apigw.common.web.GatewayHeaders;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 固定顺序的 CORS 过滤器：委托给 Spring 自带的 {@link CorsWebFilter}，
 * 外面套一层 {@link Ordered} 把执行顺序钉在接入鉴权之后、用户登录鉴权之前。
 *
 * 为什么必须排这么早：预检请求（OPTIONS）没有、也不该带 Authorization，
 * 若 CORS 处理排在登录鉴权之后，预检会被内部路由的 401 挡掉，浏览器就永远发不出正式请求。
 * 排在前面后，预检在鉴权之前直接由 CORS 过滤器答复，正式请求则带上 CORS 头继续走鉴权/转发。
 */
public class OrderedCorsWebFilter implements WebFilter, Ordered {

    /** 接入凭据鉴权（HIGHEST+5）之后、用户登录鉴权（HIGHEST+8）之前。 */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 6;

    private final CorsWebFilter delegate;

    private OrderedCorsWebFilter(CorsWebFilter delegate) {
        this.delegate = delegate;
    }

    /**
     * 按配置构造 CORS 过滤器。
     *
     * 放行清单（让前端这条链路完整可用）：
     * - allowedHeaders：Authorization（正式请求要带 Bearer 令牌）、X-Trace-Id、
     *   应用接入凭据头等；
     * - exposedHeaders：<b>三个网关透传头</b>——X-Auth-User / X-Auth-Tenant / X-Gateway-Stamp，
     *   外加 X-Gateway-Trace-Id / X-Gateway-Error。浏览器默认只让 JS 读「简单响应头」，
     *   不在这里 Expose，前端读不到这些头；
     * - 来源取配置 {@code apigw.user-auth.cors-allowed-origins}，默认 *（不回 Allow-Credentials）；
     * - 预检缓存 30 分钟，减少 OPTIONS 往返。
     */
    public static OrderedCorsWebFilter create(UserAuthProperties properties) {
        CorsConfiguration config = new CorsConfiguration();

        List<String> origins = properties.corsAllowedOrigins();
        boolean wildcard = origins.size() == 1 && "*".equals(origins.get(0));
        if (wildcard) {
            // 字面 *：响应回 Access-Control-Allow-Origin: *（不回显请求 Origin，浏览器最省心）
            config.addAllowedOrigin("*");
        } else {
            origins.forEach(config::addAllowedOrigin);
        }
        properties.corsAllowedMethods().forEach(m -> config.addAllowedMethod(HttpMethod.valueOf(m)));

        // 正式请求允许带的请求头：Authorization + 网关自有头 + 任意业务头
        config.addAllowedHeader(HttpHeaders.AUTHORIZATION);
        config.addAllowedHeader(GatewayHeaders.TRACE_ID_HEADER);
        config.addAllowedHeader(GatewayHeaders.APP_NO_HEADER);
        config.addAllowedHeader(GatewayHeaders.APP_SECRET_HEADER);
        config.addAllowedHeader("*");

        // 浏览器 JS 默认可读的只有简单响应头；网关的身份/戳/trace/错误码头必须显式 Expose
        config.addExposedHeader(GatewayHeaders.AUTH_USER_HEADER);
        config.addExposedHeader(GatewayHeaders.AUTH_TENANT_HEADER);
        config.addExposedHeader(GatewayHeaders.GATEWAY_STAMP_HEADER);
        config.addExposedHeader("X-Gateway-Trace-Id");
        config.addExposedHeader("X-Gateway-Error");

        config.setMaxAge(1800L);
        // 浏览器不允许「* 来源 + Allow-Credentials」并存；显式来源清单时才放带 Cookie 的请求
        config.setAllowCredentials(!wildcard);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new OrderedCorsWebFilter(new CorsWebFilter(source));
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return delegate.filter(exchange, chain);
    }
}
