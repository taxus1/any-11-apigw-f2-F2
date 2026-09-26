package com.apigw.domain.accesslog;

import java.time.Instant;

/**
 * 访问流水的一行：一笔请求恰好对应一条。
 *
 * 字段两段来源，靠 {@link GatewayProxyWebFilter} 里「请求一进来就建、挂在 exchange 上」的
 * 专属持有者拼回同一行，绝不用 traceId 之类当 key 去共享 Map 里凑——并发再高也不会把
 * A 请求的路径配到 B 请求的状态码上。
 *
 * 不变量：
 * - requestId / clientIp / method / path / occurredAt 在请求进来时就定死（第一段）；
 * - routeNo / statusCode / elapsedMs 在响应收口时补齐（第二段），补完整行一次性入队；
 * - statusCode 取「最终回给调用方的状态码」；一个码都没产出（状态行写出前连接就断）填 0；
 * - appNo 认不出来为 null；routeNo 没匹配上为 null。
 */
public record AccessLogEntry(String requestId,
                             String routeNo,
                             String appNo,
                             String clientIp,
                             String method,
                             String path,
                             int statusCode,
                             long elapsedMs,
                             Instant occurredAt) {

    public AccessLogEntry {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId 不能为空");
        }
        if (clientIp == null || clientIp.isBlank()) {
            throw new IllegalArgumentException("clientIp 不能为空");
        }
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method 不能为空");
        }
        if (path == null) {
            throw new IllegalArgumentException("path 不能为空");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt 不能为空");
        }
    }

    /** 请求进来时的第一段：此时还没匹配路由、更没有状态码，后三项分别是 null / 0 / 待补。 */
    public static AccessLogEntry incoming(String requestId, String appNo, String clientIp,
                                          String method, String path, Instant occurredAt) {
        return new AccessLogEntry(requestId, null, appNo, clientIp, method, path, 0, 0, occurredAt);
    }

    /** 响应收口时补齐第二段（命中路由、最终状态码、总耗时），拼成完整一行。 */
    public AccessLogEntry complete(String routeNo, int statusCode, long elapsedMs) {
        return new AccessLogEntry(requestId, routeNo, appNo, clientIp, method, path,
                statusCode, elapsedMs, occurredAt);
    }
}
