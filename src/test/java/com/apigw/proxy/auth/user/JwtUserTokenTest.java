package com.apigw.proxy.auth.user;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JwtUserToken} 签发/验签单测。重点不是「字段拆得对不对」，而是伪造手段必须全部识破：
 * 假签名、改 alg、塞「永不过期」私字段、压在过期那一刻——这些都得拒。
 */
class JwtUserTokenTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-1234567890";
    private static final Instant T0 = Instant.parse("2026-09-27T00:00:00Z");
    private final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
    private final JwtUserToken.Signer signer = new JwtUserToken.Signer(SECRET, clock);
    private final JwtUserToken.Verifier verifier = new JwtUserToken.Verifier(SECRET, clock);

    private String token() {
        return signer.issue("u123", "tenant-a", Duration.ofMinutes(10));
    }

    @Test
    void validToken_roundTripsClaims() {
        JwtUserToken.Principal p = verifier.verify(token());
        assertThat(p.userId()).isEqualTo("u123");
        assertThat(p.tenantId()).isEqualTo("tenant-a");
        assertThat(p.expiresAt()).isEqualTo(T0.plusSeconds(600));
    }

    @Test
    void missingOrBlankToken_401ish() {
        assertThatThrownBy(() -> verifier.verify(null)).isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> verifier.verify("   ")).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void wrongNumberOfSegments_rejected() {
        String t = token();
        assertThatThrownBy(() -> verifier.verify(t + ".extra")).isInstanceOf(InvalidTokenException.class);
        String[] parts = t.split("\\.");
        assertThatThrownBy(() -> verifier.verify(parts[0] + "." + parts[1]))
                .isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> verifier.verify(parts[0] + ".." + parts[2]))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void forgedSignature_rejected() {
        String t = token();
        String[] parts = t.split("\\.");
        // 末段签名翻转一个字符（base64url 字符集内替换）
        char[] sig = parts[2].toCharArray();
        sig[0] = sig[0] == 'A' ? 'B' : 'A';
        String forged = parts[0] + "." + parts[1] + "." + new String(sig);
        assertThatThrownBy(() -> verifier.verify(forged))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("签名");
    }

    @Test
    void signatureWithDifferentSecret_rejected() {
        String signedByOther = new JwtUserToken.Signer("another-secret-another-secret-1234567890ab", clock)
                .issue("u123", "tenant-a", Duration.ofMinutes(10));
        assertThatThrownBy(() -> verifier.verify(signedByOther))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("签名");
    }

    @Test
    void tamperedPayload_evenIfSignatureReattached_rejected() {
        // 改了载荷却用错误密钥补不上签名；这里直接改载荷字节，签名必然对不上
        String t = token();
        String[] parts = t.split("\\.");
        String payload = parts[1];
        char c = payload.charAt(0);
        String tamperedPayload = (c == 'A' ? 'B' : 'A') + payload.substring(1);
        assertThatThrownBy(() -> verifier.verify(parts[0] + "." + tamperedPayload + "." + parts[2]))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void algNoneForgery_rejected() {
        // 经典伪造：头部改成 {"alg":"none"}，签名段留空，试图让校验方不验签
        String t = token();
        String payload = t.split("\\.")[1];
        String noneHeader = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes());
        assertThatThrownBy(() -> verifier.verify(noneHeader + "." + payload + "."))
                .isInstanceOf(InvalidTokenException.class);
        // 再试一个 alg 为其他 HMAC 名的头部（签名是用 HS256 算的，头却宣称 HS512）
        String hs512Header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"HS512\",\"typ\":\"JWT\"}".getBytes());
        assertThatThrownBy(() -> verifier.verify(hs512Header + "." + payload + "." + t.split("\\.")[2]))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void typWrongOrMissing_rejected() {
        String t = token();
        String payload = t.split("\\.")[1];
        String sig = t.split("\\.")[2];
        String badTyp = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWTX\"}".getBytes());
        assertThatThrownBy(() -> verifier.verify(badTyp + "." + payload + "." + sig))
                .isInstanceOf(InvalidTokenException.class);
        String noTyp = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"HS256\"}".getBytes());
        assertThatThrownBy(() -> verifier.verify(noTyp + "." + payload + "." + sig))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void exactlyAtExpiry_isExpired_noGracePeriod() {
        // exp = T0，校验时钟也在 T0：now >= exp 即过期，压在线上也算过期
        String onTheLine = signer.issue("u1", "t1", T0.getEpochSecond() - 1, T0.getEpochSecond());
        assertThatThrownBy(() -> verifier.verify(onTheLine))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("过期");
    }

    @Test
    void oneSecondBeforeExpiry_valid() {
        Clock justBefore = Clock.fixed(T0.minusSeconds(1), ZoneOffset.UTC);
        String t = signer.issue("u1", "t1", T0.getEpochSecond() - 60, T0.getEpochSecond());
        JwtUserToken.Principal p = new JwtUserToken.Verifier(SECRET, justBefore).verify(t);
        assertThat(p.userId()).isEqualTo("u1");
    }

    @Test
    void expiredOneSecondAgo_rejected() {
        String expired = signer.issue("u1", "t1",
                T0.getEpochSecond() - 100, T0.getEpochSecond() - 1);
        assertThatThrownBy(() -> verifier.verify(expired))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("过期");
    }

    @Test
    void nonNumericExp_rejected_evenWithValidSignature() {
        // 用签发器正规造一张「exp 是字符串」的令牌：签名合法也必须因声明不合规被拒，
        // 证明校验不是只看签名
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes());
        String payloadJson = "{\"sub\":\"u1\",\"tenant\":\"t1\",\"iat\":1,\"exp\":\"9999999999\"}";
        String body = header + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes());
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            String sig = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertThatThrownBy(() -> verifier.verify(body + "." + sig))
                    .isInstanceOf(InvalidTokenException.class)
                    .hasMessageContaining("exp");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void fakeNeverExpireClaim_doesNotBypassExpiry() {
        // 塞「永不过期」之类私字段不能影响判定：exp 已过，照样拒
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes());
        String payloadJson = "{\"sub\":\"u1\",\"tenant\":\"t1\",\"iat\":1,\"exp\":1,\"neverExpire\":true}";
        String body = header + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes());
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            String sig = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertThatThrownBy(() -> verifier.verify(body + "." + sig))
                    .isInstanceOf(InvalidTokenException.class)
                    .hasMessageContaining("过期");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void missingSubjectOrTenant_rejected() {
        assertThatThrownBy(() -> signer.issue(null, "t1", Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> signer.issue("u1", " ", Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);

        // 构造一张签名正确但缺 tenant 的令牌
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes());
        String payloadJson = "{\"sub\":\"u1\",\"iat\":1,\"exp\":9999999999}";
        String body = header + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes());
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            String sig = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertThatThrownBy(() -> verifier.verify(body + "." + sig))
                    .isInstanceOf(InvalidTokenException.class)
                    .hasMessageContaining("tenant");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void garbageBase64_rejected() {
        assertThatThrownBy(() -> verifier.verify("###.@@@.!!!"))
                .isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> verifier.verify("not-a-jwt"))
                .isInstanceOf(InvalidTokenException.class);
    }
}
