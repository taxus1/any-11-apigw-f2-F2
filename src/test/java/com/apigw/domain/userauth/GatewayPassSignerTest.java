package com.apigw.domain.userauth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通行标记（X-Gateway-Pass）签名器测试：网关盖的章上游验得出，调用方伪造的一个都不认。
 */
class GatewayPassSignerTest {

    private static final String SECRET = "pass-secret-shared-between-gateway-and-upstream!";
    private final GatewayPassSigner signer = new GatewayPassSigner(SECRET);

    @Test
    void signedMarker_verifies_withSameInputs() {
        long now = System.currentTimeMillis();
        String pass = signer.sign(now, "trace-1", "GET", "/order/1", "user-1", "tenant-a");
        assertThat(signer.verify(pass, "trace-1", "GET", "/order/1", "user-1", "tenant-a",
                now, 300_000)).isTrue();
    }

    @Test
    void forgedMarker_signedWithDifferentSecret_isRejected() {
        long now = System.currentTimeMillis();
        GatewayPassSigner attacker = new GatewayPassSigner("attacker-guessed-secret");
        String forged = attacker.sign(now, "trace-1", "GET", "/order/1", "admin", "tenant-a");
        assertThat(signer.verify(forged, "trace-1", "GET", "/order/1", "admin", "tenant-a",
                now, 300_000)).isFalse();
    }

    @Test
    void swappedIdentity_invalidatesMarker() {
        // 标记把身份绑死：换个用户/租户去验就不过，调用方没法拿别人的标记套自己的身份
        long now = System.currentTimeMillis();
        String pass = signer.sign(now, "trace-1", "GET", "/order/1", "user-1", "tenant-a");
        assertThat(signer.verify(pass, "trace-1", "GET", "/order/1", "admin", "tenant-a",
                now, 300_000)).isFalse();
        assertThat(signer.verify(pass, "trace-1", "GET", "/order/1", "user-1", "tenant-b",
                now, 300_000)).isFalse();
    }

    @Test
    void differentPathOrTrace_invalidatesMarker() {
        long now = System.currentTimeMillis();
        String pass = signer.sign(now, "trace-1", "GET", "/order/1", "user-1", "tenant-a");
        assertThat(signer.verify(pass, "trace-1", "GET", "/order/2", "user-1", "tenant-a",
                now, 300_000)).isFalse();
        assertThat(signer.verify(pass, "trace-2", "GET", "/order/1", "user-1", "tenant-a",
                now, 300_000)).isFalse();
    }

    @Test
    void staleMarker_isRejected_beyondFreshnessWindow() {
        long now = System.currentTimeMillis();
        String pass = signer.sign(now - 600_000, "trace-1", "GET", "/order/1", "user-1", "tenant-a");
        // 10 分钟前的标记，窗口 5 分钟：重放不认
        assertThat(signer.verify(pass, "trace-1", "GET", "/order/1", "user-1", "tenant-a",
                now, 300_000)).isFalse();
    }

    @Test
    void garbageMarkers_areRejected() {
        long now = System.currentTimeMillis();
        assertThat(signer.verify(null, "t", "GET", "/p", "u", "te", now, 300_000)).isFalse();
        assertThat(signer.verify("", "t", "GET", "/p", "u", "te", now, 300_000)).isFalse();
        assertThat(signer.verify("fake", "t", "GET", "/p", "u", "te", now, 300_000)).isFalse();
        assertThat(signer.verify("v1.notanumber.abc", "t", "GET", "/p", "u", "te", now, 300_000))
                .isFalse();
        assertThat(signer.verify("v2.123.abc", "t", "GET", "/p", "u", "te", now, 300_000)).isFalse();
    }

    @Test
    void anonymousMarker_roundTrips_withEmptyIdentity() {
        long now = System.currentTimeMillis();
        String pass = signer.sign(now, "trace-9", "GET", "/open/1", "", "");
        assertThat(signer.verify(pass, "trace-9", "GET", "/open/1", "", "", now, 300_000)).isTrue();
    }
}
