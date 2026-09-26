package com.apigw.infrastructure.jdbc;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 网关关系型存储的公共装配：访问流水与第三方接入凭据共用同一个数据源/JdbcTemplate。
 *
 * 主程序排除了 {@code DataSourceAutoConfiguration}，所以没用到库的环境（纯本地转发）
 * 不会因为缺 spring.datasource.url 启动失败。只要下面两个开关任一为 true 就建池：
 * - {@code apigw.accesslog.enabled=true}
 * - {@code apigw.app-auth.enabled=true}
 *
 * 两类数据同在一个 MySQL 库里（DDL 见 resources/db/*.sql），池保持很小：
 * 写只有后台线程/低频管理操作，没必要占一堆连接。
 */
@Configuration
@EnableConfigurationProperties(DataSourceProperties.class)
public class SharedDataSourceConfig {

    @Bean(destroyMethod = "close")
    @Conditional(AnyJdbcFeatureEnabled.class)
    DataSource gatewayDataSource(DataSourceProperties properties) {
        HikariDataSource ds = properties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        ds.setPoolName("gateway-jdbc-pool");
        ds.setMaximumPoolSize(5);
        ds.setMinimumIdle(1);
        ds.setConnectionTimeout(2_000);
        return ds;
    }

    @Bean
    @Conditional(AnyJdbcFeatureEnabled.class)
    JdbcTemplate gatewayJdbcTemplate(DataSource gatewayDataSource) {
        return new JdbcTemplate(gatewayDataSource);
    }

    /** accesslog 或 app-auth 任一开启即装配 JDBC。 */
    static class AnyJdbcFeatureEnabled extends SpringBootCondition {
        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Boolean accessLog = context.getEnvironment()
                    .getProperty("apigw.accesslog.enabled", Boolean.class);
            Boolean appAuth = context.getEnvironment()
                    .getProperty("apigw.app-auth.enabled", Boolean.class);
            boolean match = Boolean.TRUE.equals(accessLog) || Boolean.TRUE.equals(appAuth);
            return new ConditionOutcome(match,
                    "apigw.accesslog.enabled or apigw.app-auth.enabled is true");
        }
    }
}
