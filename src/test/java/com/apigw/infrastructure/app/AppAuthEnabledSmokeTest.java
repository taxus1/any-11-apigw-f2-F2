package com.apigw.infrastructure.app;

import com.apigw.application.app.ClientAppService;
import com.apigw.domain.app.AppCredentialRepository;
import com.apigw.interfaces.rest.app.ClientAppController;
import com.apigw.proxy.auth.AppAuthProperties;
import com.apigw.proxy.auth.AppAuthWebFilter;
import com.apigw.proxy.auth.AppCredentialCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code apigw.app-auth.enabled=true} 时的真实 Spring 上下文装配（H2 内存库，不依赖外部环境）：
 * 仓储、时钟、快照、服务、控制器、鉴权过滤器全部就位，快照预热后即使库为空也已建立。
 */
@SpringBootTest(properties = {
        "apigw.app-auth.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:appauthon;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.schema-locations=classpath:schema-app.sql",
        "spring.sql.init.mode=always",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6399"
})
class AppAuthEnabledSmokeTest {

    @Autowired
    ApplicationContext ctx;

    @Test
    void allAppAuthBeansAreWired() {
        assertThat(ctx.getBean(AppCredentialRepository.class)).isNotNull();
        assertThat(ctx.getBean(AppCredentialCatalog.class)).isNotNull();
        assertThat(ctx.getBean(ClientAppService.class)).isNotNull();
        assertThat(ctx.getBean(ClientAppController.class)).isNotNull();
        assertThat(ctx.getBean(AppAuthWebFilter.class)).isNotNull();
        assertThat(ctx.getBean(AppAuthProperties.class).enabled()).isTrue();
        // MOCK 测试环境不保证 ApplicationReadyEvent 预热已跑完，主动同步刷一次后确认快照建立
        AppCredentialCatalog catalog = ctx.getBean(AppCredentialCatalog.class);
        catalog.refreshBlock(java.time.Duration.ofSeconds(5));
        assertThat(catalog.hasSnapshot()).isTrue();
    }
}
