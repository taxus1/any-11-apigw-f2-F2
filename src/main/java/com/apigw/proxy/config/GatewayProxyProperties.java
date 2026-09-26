package com.apigw.proxy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 转发链路可调参数（前缀 apigw.proxy）。
 *
 * 超时拆成两段：连不上上游（connect）和上游半天不吭声（response），
 * 两类故障给调用方的答复要区分得开（502 vs 504）。
 *
 * @param connectTimeout 与上游建连超时（时间内握不上手 → 502 UPSTREAM_UNAVAILABLE）
 * @param responseTimeout 上游响应超时（连上了但迟迟不回首字节 → 504 UPSTREAM_TIMEOUT）
 * @param routeRefreshInterval 路由快照兜底轮询周期：本实例配置变更走事件即时刷新，
 *                             轮询是给「别的实例改了配置、没收到事件」留的最终一致兜底
 */
@ConfigurationProperties(prefix = "apigw.proxy")
public record GatewayProxyProperties(Duration connectTimeout,
                                     Duration responseTimeout,
                                     Duration routeRefreshInterval) {

    public GatewayProxyProperties {
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(3);
        }
        if (responseTimeout == null) {
            responseTimeout = Duration.ofSeconds(10);
        }
        if (routeRefreshInterval == null) {
            routeRefreshInterval = Duration.ofSeconds(10);
        }
    }
}
