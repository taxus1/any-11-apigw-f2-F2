package com.apigw.infrastructure.app;

import com.apigw.application.app.ClientAppService;
import com.apigw.interfaces.rest.app.ClientAppController;
import com.apigw.proxy.auth.AppAuthWebFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 默认（开关关闭）形态：不连库、不装任何接入鉴权 bean，控制器也不进容器，
 * 上下文照常启动——本地无库环境仍是「纯网关」。
 */
@SpringBootTest(properties = {
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6399"
})
class AppAuthDisabledSmokeTest {

    @Autowired
    ApplicationContext ctx;

    @Test
    void noAppAuthBeans_andContextStillStarts() {
        assertThatCode(() -> ctx.getBean(ClientAppService.class))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
        assertThatCode(() -> ctx.getBean(ClientAppController.class))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
        assertThatCode(() -> ctx.getBean(AppAuthWebFilter.class))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
    }
}
