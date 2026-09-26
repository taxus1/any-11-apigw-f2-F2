package com.apigw.domain.accesslog;

import java.time.Instant;

/**
 * 翻流水的筛选条件，全部可选、彼此 AND：
 *
 * @param startTime 发生时间起（含），null 表示不限
 * @param endTime   发生时间止（不含），null 表示不限
 * @param routeNo   命中路由编号精确匹配，null 表示不限
 * @param statusCode 状态码精确匹配，null 表示不限
 */
public record AccessLogQuery(Instant startTime,
                             Instant endTime,
                             String routeNo,
                             Integer statusCode) {

    public static AccessLogQuery of(Instant startTime, Instant endTime,
                                    String routeNo, Integer statusCode) {
        return new AccessLogQuery(startTime, endTime,
                routeNo == null || routeNo.isBlank() ? null : routeNo.trim(),
                statusCode);
    }
}
