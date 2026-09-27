package com.apigw.proxy.cors;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * 跨域（CORS）可调参数（前缀 {@code apigw.cors}）。
 *
 * 用户令牌走 {@code Authorization} 头，网关认人后写出的身份/追踪头要让浏览器里的前端读得到，
 * 所以这里同时管两件事：预检放行哪些请求头（含 Authorization）、实际响应向 JS 暴露哪些头。
 *
 * @param enabled          是否处理跨域；默认开（关掉则不带任何 CORS 头）。
 * @param allowedOrigins   允许的来源，精确匹配；含 "*" 表示任意来源（令牌在 Authorization 头
 *                         而非 Cookie，默认不带凭证）。生产应收敛成自己的页面来源。
 * @param allowedMethods   预检允许的方法。
 * @param allowedHeaders   预检允许的请求头；含 "*" 时把预检请求里申请的头原样回给浏览器。
 * @param exposedHeaders   实际响应允许前端 JS 读取的头：身份头、追踪号、网关错误标识都在这里。
 * @param maxAge           预检结果可缓存多久。
 */
@ConfigurationProperties(prefix = "apigw.cors")
public record GatewayCorsProperties(boolean enabled,
                                    List<String> allowedOrigins,
                                    List<String> allowedMethods,
                                    List<String> allowedHeaders,
                                    List<String> exposedHeaders,
                                    Duration maxAge) {

    public static final String DEFAULT_EXPOSED_USER_ID = "X-User-Id";
    public static final String DEFAULT_EXPOSED_TENANT_ID = "X-Tenant-Id";

    public GatewayCorsProperties {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            allowedOrigins = List.of("*");
        }
        if (allowedMethods == null || allowedMethods.isEmpty()) {
            allowedMethods = List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD");
        }
        if (allowedHeaders == null || allowedHeaders.isEmpty()) {
            // 通配让浏览器申请什么放行什么（含 Authorization、X-Trace-Id 等）
            allowedHeaders = List.of("*");
        }
        if (exposedHeaders == null || exposedHeaders.isEmpty()) {
            // 身份头（上游回显时前端能读）+ 追踪号 + 网关错误标识，前端跨域一律读得到
            exposedHeaders = List.of(
                    DEFAULT_EXPOSED_USER_ID,
                    DEFAULT_EXPOSED_TENANT_ID,
                    "X-Gateway-Trace-Id",
                    "X-Gateway-Error");
        }
        if (maxAge == null) {
            maxAge = Duration.ofHours(1);
        }
    }
}
