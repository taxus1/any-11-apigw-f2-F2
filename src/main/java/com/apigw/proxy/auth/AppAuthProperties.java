package com.apigw.proxy.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 接入鉴权的可调参数（前缀 {@code apigw.app-auth}）。
 *
 * @param enabled               是否开启第三方凭据鉴权。默认关：无库的本地环境照常转发；
 *                              生产置 {@code APP_AUTH_ENABLED=true} 并配数据源。
 *                              关闭时不装鉴权过滤器、也不读 gw_app 表。
 * @param refreshInterval       鉴权快照兜底轮询周期（多实例下别的实例改了配置靠它收敛）；
 *                              本实例的变更主要靠事件即时生效。
 */
@ConfigurationProperties(prefix = "apigw.app-auth")
public record AppAuthProperties(boolean enabled,
                                Duration refreshInterval,
                                long refreshIntervalMs) {

    public AppAuthProperties {
        if (refreshInterval == null || refreshInterval.isZero() || refreshInterval.isNegative()) {
            refreshInterval = Duration.ofSeconds(10);
        }
        if (refreshIntervalMs <= 0) {
            refreshIntervalMs = refreshInterval.toMillis();
        }
    }

    public static AppAuthProperties defaults() {
        return new AppAuthProperties(false, Duration.ofSeconds(10), 10_000);
    }
}
