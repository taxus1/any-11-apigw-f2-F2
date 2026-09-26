package com.apigw;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 微服务网关 apigw 启动类。
 *
 * 同一个应用里跑两件事：
 * 1. Spring Cloud Gateway 的转发链路（请求进来 → 匹配路由 → 转发上游）；
 * 2. 管理接口（/api/gateway/routes...），负责路由与匹配规则的配置维护。
 *
 * 开调度：转发侧路由快照的兜底定时刷新（{@code @EnableScheduling}），
 * 配置即时生效主要靠变更事件，调度是多实例间最终一致的兜底。
 *
 * 排除 {@link DataSourceAutoConfiguration}：访问流水落库的数据源只有在
 * {@code apigw.accesslog.enabled=true} 时才由 AccessLogInfrastructureConfig 显式创建，
 * 没配库的本地/测试环境不会因缺 spring.datasource.url 而启动失败。
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class ApigwApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApigwApplication.class, args);
    }
}
