package com.apigw.infrastructure.accesslog;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 访问流水落库的可调参数（前缀 apigw.accesslog）。
 *
 * 攒批的三条边界（都在这里，不写死）：
 * @param enabled      是否启用 JDBC 落库。没有配数据库的环境（本地/部分测试）关掉，
 *                     走空实现，应用照常启动、转发照常工作。
 * @param batchSize    攒够多少条立刻落一次：吞吐再高也是一批一条 SQL，不一行一插。
 * @param flushInterval 最多等多久必须落一次：低峰期数据不能在内存里无限攒、迟迟不可查。
 * @param queueCapacity 入口有界队列容量：请求线程只做非阻塞入队；满了丢日志计数，绝不反压转发。
 * @param shutdownAwait 正常退出时等后台线程把手里的批次 flush 完的最长时间；超时不再等（进程要能退出）。
 */
@ConfigurationProperties(prefix = "apigw.accesslog")
public record AccessLogProperties(boolean enabled,
                                  int batchSize,
                                  Duration flushInterval,
                                  int queueCapacity,
                                  Duration shutdownAwait) {

    public AccessLogProperties {
        if (batchSize <= 0) {
            batchSize = 500;
        }
        if (flushInterval == null || flushInterval.isZero() || flushInterval.isNegative()) {
            flushInterval = Duration.ofSeconds(2);
        }
        if (queueCapacity <= 0) {
            queueCapacity = 20_000;
        }
        if (shutdownAwait == null || shutdownAwait.isZero() || shutdownAwait.isNegative()) {
            shutdownAwait = Duration.ofSeconds(10);
        }
    }

    /** 便捷构造：没配时给一套生产默认值（enabled 默认 false，需显式开启）。 */
    public static AccessLogProperties defaults() {
        return new AccessLogProperties(false, 500, Duration.ofSeconds(2),
                20_000, Duration.ofSeconds(10));
    }
}
