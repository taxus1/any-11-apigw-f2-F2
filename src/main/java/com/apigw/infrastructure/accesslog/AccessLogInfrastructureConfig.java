package com.apigw.infrastructure.accesslog;

import com.apigw.domain.accesslog.AccessLogRepository;
import com.apigw.domain.accesslog.AccessLogSink;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 访问流水落库装配。
 *
 * 数据源/JdbcTemplate 在 {@link com.apigw.infrastructure.jdbc.SharedDataSourceConfig}
 * 里按「accesslog 或 app-auth 任一开启」统一创建（同一套连接配置、同一个池），
 * 本类只在 {@code apigw.accesslog.enabled=true} 时挂流水相关的 bean。
 * 没开启时出口是 {@link NoopAccessLogSink}，转发主链路零差别。
 */
@Configuration
@EnableConfigurationProperties(AccessLogProperties.class)
public class AccessLogInfrastructureConfig {

    @Configuration
    @ConditionalOnProperty(prefix = "apigw.accesslog", name = "enabled", havingValue = "true")
    static class JdbcAccessLogEnabled {

        @Bean
        AccessLogRepository accessLogRepository(JdbcTemplate gatewayJdbcTemplate,
                                                PlatformTransactionManager transactionManager) {
            return new JdbcAccessLogRepository(gatewayJdbcTemplate, transactionManager);
        }

        @Bean
        AccessLogSink accessLogSink(AccessLogRepository repository, AccessLogProperties props) {
            // SmartLifecycle：随容器启动写线程，正常退出时在 Web 容器停完之后 drain 剩余批次
            return new AsyncBatchingAccessLogSink(repository, props);
        }

        @Bean
        AccessLogQueryService accessLogQueryService(AccessLogRepository repository) {
            return new AccessLogQueryService(repository);
        }
    }

    /**
     * 没开启 JDBC 时的兜底：enabled 缺失或不等于 true 时只放空实现，
     * 与上面 enabled=true 的分支靠属性条件严格互斥。
     */
    @Configuration
    @ConditionalOnProperty(prefix = "apigw.accesslog", name = "enabled",
            havingValue = "false", matchIfMissing = true)
    static class AccessLogDisabled {

        @Bean
        AccessLogSink accessLogSink() {
            return new NoopAccessLogSink();
        }
    }
}
