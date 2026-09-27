package com.apigw.infrastructure.userauth;

import com.apigw.proxy.auth.user.UserAuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 安全护栏：开启用户登录鉴权却不注入密钥（或密钥太短），配置绑定必须直接失败，
 * 绝不允许退回一个内置默认密钥（那等于把令牌伪造能力公开）。
 *
 * 用 Binder 直接驱动 {@code @ConfigurationProperties} 记录类的构造器校验，
 * 不启动 Web/SCG 容器。Binder 会把构造器抛出的异常包一层，根因在异常链上，
 * 断言沿链找到我们自己抛的 {@link IllegalStateException} 及其文案。
 */
class UserAuthMissingSecretFailFastTest {

    /** 在异常链（含被 Binder/Spring 包装的层级）里找根因文案。 */
    private static Throwable rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur;
    }

    @Test
    void enabledWithoutSecret_bindingFails() {
        var source = new MapConfigurationPropertySource(Map.of("apigw.user-auth.enabled", "true"));
        Binder binder = new Binder(source);
        assertThatThrownBy(() -> binder.bind("apigw.user-auth", UserAuthProperties.class))
                .satisfies(t -> {
                    Throwable root = rootCause(t);
                    assertThat(root).isInstanceOf(IllegalStateException.class);
                    assertThat(root.getMessage()).contains("token-secret");
                });
    }

    @Test
    void enabledWithBlankSecret_bindingFails() {
        var source = new MapConfigurationPropertySource(Map.of(
                "apigw.user-auth.enabled", "true",
                "apigw.user-auth.token-secret", "   "));
        Binder binder = new Binder(source);
        assertThatThrownBy(() -> binder.bind("apigw.user-auth", UserAuthProperties.class))
                .satisfies(t -> assertThat(rootCause(t).getMessage()).contains("token-secret"));
    }

    @Test
    void enabledWithTooShortSecret_bindingFails() {
        var source = new MapConfigurationPropertySource(Map.of(
                "apigw.user-auth.enabled", "true",
                "apigw.user-auth.token-secret", "short"));
        Binder binder = new Binder(source);
        assertThatThrownBy(() -> binder.bind("apigw.user-auth", UserAuthProperties.class))
                .satisfies(t -> assertThat(rootCause(t).getMessage()).contains("长度不足"));
    }

    @Test
    void enabledWithProperSecret_bindingSucceeds() {
        var source = new MapConfigurationPropertySource(Map.of(
                "apigw.user-auth.enabled", "true",
                "apigw.user-auth.token-secret", "0123456789abcdef0123456789abcdef01234567"));
        Binder binder = new Binder(source);
        UserAuthProperties bound = binder.bind("apigw.user-auth", UserAuthProperties.class).get();
        assertThat(bound.enabled()).isTrue();
        assertThat(bound.corsAllowedOrigins()).containsExactly("*");
    }

    @Test
    void disabledWithoutSecret_bindingSucceeds() {
        var source = new MapConfigurationPropertySource(Map.of("apigw.user-auth.enabled", "false"));
        Binder binder = new Binder(source);
        assertThat(binder.bind("apigw.user-auth", UserAuthProperties.class).get().enabled()).isFalse();
    }
}
