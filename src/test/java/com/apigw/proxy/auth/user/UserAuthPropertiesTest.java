package com.apigw.proxy.auth.user;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link UserAuthProperties} 开关与密钥约束测试：
 * 密钥只能走配置，开启开关却没配/配太短必须 fail-fast，不允许退回内置默认密钥。
 */
class UserAuthPropertiesTest {

    private static final String LONG_SECRET = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void disabledByDefault_andNoSecretRequired() {
        UserAuthProperties p = new UserAuthProperties(false, null, null, null, null);
        assertThat(p.enabled()).isFalse();
        assertThat(p.corsAllowedOrigins()).containsExactly("*");
        assertThat(p.corsAllowedMethods()).contains("GET", "POST", "OPTIONS");
    }

    @Test
    void enabledWithoutSecret_failsFast_noDefaultKey() {
        assertThatThrownBy(() -> new UserAuthProperties(true, null, null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token-secret");
        assertThatThrownBy(() -> new UserAuthProperties(true, "  ", null, null, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void enabledWithTooShortSecret_failsFast() {
        assertThatThrownBy(() -> new UserAuthProperties(true, "short", null, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("长度不足");
    }

    @Test
    void enabledWithLongSecret_ok() {
        UserAuthProperties p = new UserAuthProperties(true, LONG_SECRET, null, null, null);
        assertThat(p.enabled()).isTrue();
        // 没单独配戳密钥时复用令牌密钥
        assertThat(p.effectiveStampSecret()).isEqualTo(LONG_SECRET);
    }

    @Test
    void separateStampSecret_isUsedWhenProvided() {
        String stampSecret = "stamp-secret-stamp-secret-stamp-secret-stamp-9999";
        UserAuthProperties p = new UserAuthProperties(true, LONG_SECRET, stampSecret, null, null);
        assertThat(p.effectiveStampSecret()).isEqualTo(stampSecret);
    }

    @Test
    void explicitCorsOrigins_areKeptAndCredentialsAllowed() {
        UserAuthProperties p = new UserAuthProperties(true, LONG_SECRET, null,
                java.util.List.of("https://web.example.com", "https://admin.example.com"), null);
        assertThat(p.corsAllowedOrigins())
                .containsExactly("https://web.example.com", "https://admin.example.com");
    }
}
