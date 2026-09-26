package com.apigw.interfaces.rest.accesslog.vo;

import com.apigw.domain.accesslog.AccessLogEntry;

import java.time.Instant;

/**
 * 一行访问流水的对外视图。与表列一一对应；时间用 ISO-8601 UTC（带 Z），前端按本地时区渲染。
 */
public record AccessLogVO(String requestId,
                          String routeNo,
                          String appNo,
                          String clientIp,
                          String method,
                          String path,
                          Integer statusCode,
                          Long elapsedMs,
                          Instant occurredAt) {

    public static AccessLogVO of(AccessLogEntry e) {
        return new AccessLogVO(e.requestId(), e.routeNo(), e.appNo(), e.clientIp(),
                e.method(), e.path(), e.statusCode(), e.elapsedMs(), e.occurredAt());
    }
}
