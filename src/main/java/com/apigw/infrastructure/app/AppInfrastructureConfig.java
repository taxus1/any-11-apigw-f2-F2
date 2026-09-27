package com.apigw.infrastructure.app;

import com.apigw.application.app.ClientAppService;
import com.apigw.domain.app.AppCredentialRepository;
import com.apigw.proxy.auth.AppAuthProperties;
import com.apigw.proxy.auth.AppAuthWebFilter;
import com.apigw.proxy.auth.AppCredentialCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;

/**
 * 第三方接入鉴权装配。仅在 {@code apigw.app-auth.enabled=true} 时整体生效：
 * 仓储（JDBC）、鉴权内存快照、管理应用服务与控制器、转发鉴权过滤器一起装上；
 * 关闭时本类一个 bean 都不产生，转发链路和未做该功能时完全一致。
 *
 * 数据源/JdbcTemplate 由 {@link com.apigw.infrastructure.jdbc.SharedDataSourceConfig}
 * 在「任一 JDBC 功能开启」时统一提供。
 */
@Configuration
@EnableConfigurationProperties(AppAuthProperties.class)
@ConditionalOnProperty(prefix = "apigw.app-auth", name = "enabled", havingValue = "true")
public class AppInfrastructureConfig {

    // 全系统统一 Clock 由常驻的 com.apigw.common.time.GatewayTimeConfig 提供，
    // 避免与用户登录鉴权模块各自定义同名 bean 冲突。

    @Bean
    AppCredentialRepository appCredentialRepository(JdbcTemplate gatewayJdbcTemplate,
                                                    PlatformTransactionManager transactionManager) {
        return new JdbcAppCredentialRepository(gatewayJdbcTemplate, transactionManager);
    }

    @Bean
    AppCredentialCatalog appCredentialCatalog(AppCredentialRepository repository, Clock gatewayClock) {
        return new AppCredentialCatalog(repository, gatewayClock);
    }

    @Bean
    ClientAppService clientAppService(AppCredentialRepository repository,
                                      ApplicationEventPublisher events,
                                      Clock gatewayClock) {
        return new ClientAppService(repository, events, gatewayClock);
    }

    // ClientAppController 是 @RestController，由组件扫描注册；关闭开关时
    // ApigwApplication 上的 AppAuthComponentFilter 会把它挡在容器外。

    @Bean
    AppAuthWebFilter appAuthWebFilter(AppCredentialCatalog catalog, ObjectMapper objectMapper) {
        return new AppAuthWebFilter(catalog, objectMapper);
    }
}
