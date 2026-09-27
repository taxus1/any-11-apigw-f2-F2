package com.apigw.proxy.auth.user;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GatewayStampSigner} 测试：模拟上游用同一把密钥独立验戳，
 * 验证「确实过了网关」可被验出，而戳与请求要素绑定——换请求/换身份就验不过。
 */
class GatewayStampSignerTest {

    private static final String SECRET = "stamp-secret-stamp-secret-stamp-secret-1234567";
    private static final Instant T0 = Instant.parse("2026-09-27T00:00:00Z");
    private final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
    private final GatewayStampSigner signer = new GatewayStampSigner(SECRET, clock);

    /** 上游侧验真：完全独立的一份实现（文档里描述的 canonicalString 规则）。 */
    private static boolean verify(String stamp, String secret, String method, String path,
                                  String rawQuery, String traceId, String userId, String tenantId) {
        String[] parts = stamp.split("\\.");
        if (parts.length != 3 || !"v1".equals(parts[0])) {
            return false;
        }
        String canonical = String.join("\n",
                "v1", parts[1], method.toUpperCase(), path,
                rawQuery == null ? "" : URLEncoder.encode(rawQuery, StandardCharsets.UTF_8),
                traceId == null ? "" : traceId,
                userId == null ? "" : userId,
                tenantId == null ? "" : tenantId);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
            return MessageDigestEquals(expected, parts[2]);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean MessageDigestEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void upstreamCanVerifyStamp_forAuthenticatedRequest() {
        String stamp = signer.stamp("GET", "/order/1", "from=cart", "trace-1", "u123", "tenant-a");
        assertThat(stamp).startsWith("v1." + T0.getEpochSecond() + ".");
        assertThat(verify(stamp, SECRET, "GET", "/order/1", "from=cart", "trace-1", "u123", "tenant-a"))
                .isTrue();
    }

    @Test
    void anonymousOpenRouteStamp_alsoVerifies_withEmptyIdentityFields() {
        String stamp = signer.stamp("GET", "/pub/health", null, "trace-2", null, null);
        assertThat(verify(stamp, SECRET, "GET", "/pub/health", null, "trace-2", null, null)).isTrue();
    }

    @Test
    void stampBoundToRequest_modifyingAnyElementFailsVerification() {
        String stamp = signer.stamp("GET", "/order/1", null, "trace-1", "u123", "tenant-a");
        // 换路径
        assertThat(verify(stamp, SECRET, "GET", "/order/2", null, "trace-1", "u123", "tenant-a")).isFalse();
        // 换方法
        assertThat(verify(stamp, SECRET, "POST", "/order/1", null, "trace-1", "u123", "tenant-a")).isFalse();
        // 换身份
        assertThat(verify(stamp, SECRET, "GET", "/order/1", null, "trace-1", "u999", "tenant-a")).isFalse();
        assertThat(verify(stamp, SECRET, "GET", "/order/1", null, "trace-1", "u123", "tenant-b")).isFalse();
        // 换 trace
        assertThat(verify(stamp, SECRET, "GET", "/order/1", null, "trace-x", "u123", "tenant-a")).isFalse();
        // 别的密钥验不过（调用方拿不到网关密钥）
        assertThat(verify(stamp, "wrong-secret-wrong-secret-wrong-secret-123",
                "GET", "/order/1", null, "trace-1", "u123", "tenant-a")).isFalse();
    }

    @Test
    void forgedStampWithoutSecret_doesNotVerify() {
        String forged = "v1." + T0.getEpochSecond() + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString("garbage".getBytes());
        assertThat(verify(forged, SECRET, "GET", "/order/1", null, "trace-1", "u123", "tenant-a"))
                .isFalse();
    }
}
