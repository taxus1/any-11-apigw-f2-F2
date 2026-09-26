package com.apigw.domain.app;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 密钥散列的安全口径测试：
 * - 不可逆验证只能走「重算比对」，且同一明文每次散列结果不同（随机盐）；
 * - 正确密钥通过、错误密钥不通过；
 * - 常量时间比对由实现保证（这里验证行为：长度不同的错密钥也安全返回 false，不抛异常）；
 * - 散列串自描述（算法/迭代/盐/散列），损坏串一律判不匹配。
 */
class SecretHasherTest {

    @Test
    void hash_isOneWay_andSalted() {
        String secret = "test-secret-which-is-long-enough-0123456789";
        String h1 = SecretHasher.hash(secret);
        String h2 = SecretHasher.hash(secret);

        // 原密钥不在散列串里
        assertThat(h1).doesNotContain(secret);
        // 随机盐：同一密钥散两次结果不同，但都能校验通过
        assertThat(h1).isNotEqualTo(h2);
        assertThat(SecretHasher.matches(secret, h1)).isTrue();
        assertThat(SecretHasher.matches(secret, h2)).isTrue();
    }

    @Test
    void wrongSecret_neverMatches_evenWithDifferentLength() {
        String h = SecretHasher.hash("correct-correct-correct-correct-0000");
        assertThat(SecretHasher.matches("wrong", h)).isFalse();
        assertThat(SecretHasher.matches("", h)).isFalse();
        assertThat(SecretHasher.matches(null, h)).isFalse();
        // 只差一个字符也不通过
        assertThat(SecretHasher.matches("correct-correct-correct-correct-0001", h)).isFalse();
    }

    @Test
    void malformedStoredHash_doesNotMatch_andDoesNotThrow() {
        for (String bad : List.of("", "garbage", "pbkdf2$abc$salt$hash", "md5$1$cw$cw",
                "pbkdf2$120000$@@@$cw")) {
            assertThat(SecretHasher.matches("any-secret-value-0123456789-ab", bad))
                    .as("损坏散列串应安全判否：%s", bad).isFalse();
        }
        assertThat(SecretHasher.matches("any-secret-value-0123456789-ab", null)).isFalse();
    }

    @Test
    void hashRejectsEmptySecret() {
        assertThatThrownBy(() -> SecretHasher.hash(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SecretHasher.hash(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
