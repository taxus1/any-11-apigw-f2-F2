package com.apigw.infrastructure.userauth;

import com.apigw.proxy.auth.user.GatewayStampSigner;
import com.apigw.proxy.auth.user.JwtUserToken;
import com.apigw.proxy.auth.user.OrderedCorsWebFilter;
import com.apigw.proxy.auth.user.UserAuthProperties;
import com.apigw.proxy.auth.user.UserTokenAuthWebFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code apigw.user-auth.enabled=true} 且配了密钥时的真实 Spring 上下文装配：
 * 验签器、签发器、戳签名器、登录鉴权过滤器、CORS 过滤器全部就位。
 */
@SpringBootTest(properties = {
        "apigw.user-auth.enabled=true",
        "apigw.user-auth.token-secret=0123456789abcdef0123456789abcdef01234567",
        "apigw.user-auth.stamp-secret=abcdef0123456789abcdef0123456789abcdef01",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6399"
})
class UserAuthEnabledSmokeTest {

    @Autowired
    ApplicationContext ctx;

    @Test
    void allUserAuthBeansAreWired() {
        assertThat(ctx.getBean(JwtUserToken.Verifier.class)).isNotNull();
        assertThat(ctx.getBean(JwtUserToken.Signer.class)).isNotNull();
        assertThat(ctx.getBean(GatewayStampSigner.class)).isNotNull();
        assertThat(ctx.getBean(UserTokenAuthWebFilter.class)).isNotNull();
        assertThat(ctx.getBean(OrderedCorsWebFilter.class)).isNotNull();
        UserAuthProperties props = ctx.getBean(UserAuthProperties.class);
        assertThat(props.enabled()).isTrue();
        assertThat(props.effectiveStampSecret())
                .isEqualTo("abcdef0123456789abcdef0123456789abcdef01");
    }
}
