package com.apigw.infrastructure.userauth;

import com.apigw.proxy.auth.user.GatewayStampSigner;
import com.apigw.proxy.auth.user.JwtUserToken;
import com.apigw.proxy.auth.user.OrderedCorsWebFilter;
import com.apigw.proxy.auth.user.UserAuthProperties;
import com.apigw.proxy.auth.user.UserTokenAuthWebFilter;
import com.apigw.proxy.match.RouteMatcher;
import com.apigw.proxy.route.RouteCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 用户登录鉴权 + 身份透传 + CORS 的装配。仅在 {@code apigw.user-auth.enabled=true} 时整体生效：
 * 令牌验签器、网关戳签名器、登录鉴权过滤器、CORS 过滤器一起装上；
 * 关闭时本类一个 bean 都不产生，转发链路与未做该功能时完全一致（调用方自带的同名头也不会被剥——
 * 这套安全语义只在功能开启时承诺）。
 *
 * 密钥没有默认值：开启开关时由 {@link UserAuthProperties} 构造器 fail-fast 校验，
 * 缺失/过短直接拒绝启动，密钥只经环境变量/配置中心注入，不硬编码进仓库。
 */
@Configuration
@EnableConfigurationProperties(UserAuthProperties.class)
@ConditionalOnProperty(prefix = "apigw.user-auth", name = "enabled", havingValue = "true")
public class UserAuthInfrastructureConfig {

    /** 令牌签发器（可选）：与网关共享密钥的签发侧/运维工具用它造令牌。 */
    @Bean
    JwtUserToken.Signer jwtUserTokenSigner(UserAuthProperties properties, Clock gatewayClock) {
        return new JwtUserToken.Signer(properties.tokenSecret(), gatewayClock);
    }

    @Bean
    JwtUserToken.Verifier jwtUserTokenVerifier(UserAuthProperties properties, Clock gatewayClock) {
        return new JwtUserToken.Verifier(properties.tokenSecret(), gatewayClock);
    }

    @Bean
    GatewayStampSigner gatewayStampSigner(UserAuthProperties properties, Clock gatewayClock) {
        return new GatewayStampSigner(properties.effectiveStampSecret(), gatewayClock);
    }

    @Bean
    UserTokenAuthWebFilter userTokenAuthWebFilter(RouteCatalog routeCatalog,
                                                  RouteMatcher routeMatcher,
                                                  JwtUserToken.Verifier tokenVerifier,
                                                  GatewayStampSigner gatewayStampSigner,
                                                  ObjectMapper objectMapper) {
        return new UserTokenAuthWebFilter(routeCatalog, routeMatcher, tokenVerifier,
                gatewayStampSigner, objectMapper);
    }

    @Bean
    OrderedCorsWebFilter orderedCorsWebFilter(UserAuthProperties properties) {
        return OrderedCorsWebFilter.create(properties);
    }
}
