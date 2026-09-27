package com.apigw.infrastructure.userauth;

import com.apigw.proxy.auth.user.OrderedCorsWebFilter;
import com.apigw.proxy.auth.user.UserTokenAuthWebFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 默认（用户登录鉴权关闭）形态：不装令牌/戳/CORS 过滤器等任何功能性 bean，上下文照常启动。
 * （UserAuthProperties 这个属性载体可能被 @ConfigurationPropertiesScan 注册，但不产生任何行为。）
 */
@SpringBootTest(properties = {
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6399"
})
class UserAuthDisabledSmokeTest {

    @Autowired
    ApplicationContext ctx;

    @Test
    void noUserAuthFilterBeans_andContextStillStarts() {
        assertThatCode(() -> ctx.getBean(UserTokenAuthWebFilter.class))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
        assertThatCode(() -> ctx.getBean(OrderedCorsWebFilter.class))
                .isInstanceOf(NoSuchBeanDefinitionException.class);
    }
}
