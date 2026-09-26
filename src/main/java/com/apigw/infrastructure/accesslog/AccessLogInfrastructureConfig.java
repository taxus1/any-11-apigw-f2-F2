package com.apigw.infrastructure.accesslog;

import com.apigw.domain.accesslog.AccessLogRepository;
import com.apigw.domain.accesslog.AccessLogSink;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 访问流水落库装配。
 *
 * 用 {@code apigw.accesslog.enabled=true} 显式开启：开启时才在本类里建数据源
 * （主程序已排除 {@code DataSourceAutoConfiguration}，没开启的环境根本不去碰库、
 * 不会因缺 spring.datasource.url 启动失败）；JdbcTemplate/事务管理器的自动配置
 * 看到 DataSource bean 后自然跟上。没开启时出口是 {@link NoopAccessLogSink}，
 * 转发主链路零差别。
 */
@Configuration
@EnableConfigurationProperties(AccessLogProperties.class)
public class AccessLogInfrastructureConfig {

    @Configuration
    @ConditionalOnProperty(prefix = "apigw.accesslog", name = "enabled", havingValue = "true")
    @EnableConfigurationProperties(DataSourceProperties.class)
    static class JdbcAccessLogEnabled {

        /**
         * 显式建 Hikari 连接池（url/driver/账号由 {@link DataSourceProperties} 绑定）。
         * 池很小：写只有一个后台线程、查询是低频管理操作，没必要也不应该占一堆连接。
         */
        @Bean(destroyMethod = "close")
        DataSource accessLogDataSource(DataSourceProperties properties) {
            HikariDataSource ds = properties
                    .initializeDataSourceBuilder()
                    .type(HikariDataSource.class)
                    .build();
            ds.setPoolName("access-log-pool");
            ds.setMaximumPoolSize(5);
            ds.setMinimumIdle(1);
            ds.setConnectionTimeout(2_000);
            return ds;
        }

        @Bean
        JdbcTemplate accessLogJdbcTemplate(DataSource accessLogDataSource) {
            return new JdbcTemplate(accessLogDataSource);
        }

        @Bean
        AccessLogRepository accessLogRepository(JdbcTemplate accessLogJdbcTemplate,
                                                org.springframework.transaction.PlatformTransactionManager transactionManager) {
            return new JdbcAccessLogRepository(accessLogJdbcTemplate, transactionManager);
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
