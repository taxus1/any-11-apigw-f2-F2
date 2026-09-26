package com.apigw.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 来源地址口径测试。这套顺序同时是鉴权/限流的口径，任何改动都是跨模块行为变化。
 */
class ClientIpResolverTest {

    private MockServerHttpRequest.BaseBuilder<?> request() {
        return MockServerHttpRequest.get("/x");
    }

    @Test
    void prefersFirstValidAddressInXff() {
        var req = request()
                .header(ClientIpResolver.XFF_HEADER, "203.0.113.9, 10.0.0.2, 10.0.0.3")
                .header(ClientIpResolver.REAL_IP_HEADER, "198.51.100.7")
                .remoteAddress(new InetSocketAddress("172.16.0.1", 1234))
                .build();
        // 最左一跳（最初客户端）赢，不看 X-Real-IP，也不看 remoteAddress
        assertThat(ClientIpResolver.resolve(req)).isEqualTo("203.0.113.9");
    }

    @Test
    void fallsBackToRealIp_whenXffMissingOrInvalid() {
        var onlyRealIp = request()
                .header(ClientIpResolver.REAL_IP_HEADER, "198.51.100.7")
                .remoteAddress(new InetSocketAddress("172.16.0.1", 1))
                .build();
        assertThat(ClientIpResolver.resolve(onlyRealIp)).isEqualTo("198.51.100.7");

        // XFF 是伪造垃圾（不是合法 IP），整级跳过，退到 X-Real-IP
        var garbageXff = request()
                .header(ClientIpResolver.XFF_HEADER, "not-an-ip; curl xxx")
                .header(ClientIpResolver.REAL_IP_HEADER, "198.51.100.7")
                .remoteAddress(new InetSocketAddress("172.16.0.1", 1))
                .build();
        assertThat(ClientIpResolver.resolve(garbageXff)).isEqualTo("198.51.100.7");
    }

    @Test
    void fallsBackToRemoteAddress_whenNoTrustedHeaders() {
        var req = request()
                .remoteAddress(new InetSocketAddress("172.16.0.1", 4321))
                .build();
        assertThat(ClientIpResolver.resolve(req)).isEqualTo("172.16.0.1");
    }

    @Test
    void skipsIllegalFirstHopInXff_butUsesLaterValidHop() {
        var req = request()
                .header(ClientIpResolver.XFF_HEADER, "evil, 198.51.100.50")
                .remoteAddress(new InetSocketAddress("172.16.0.1", 1))
                .build();
        assertThat(ClientIpResolver.resolve(req)).isEqualTo("198.51.100.50");
    }

    @Test
    void rejectsHostnamesAndOutOfRangeOctets() {
        assertThat(ClientIpResolver.isIpAddress("10.0.0.evil")).isFalse();
        assertThat(ClientIpResolver.isIpAddress("999.1.1.1")).isFalse();
        assertThat(ClientIpResolver.isIpAddress("1.2.3")).isFalse();
        assertThat(ClientIpResolver.isIpAddress("1.2.3.4.5")).isFalse();
        assertThat(ClientIpResolver.isIpAddress("")).isFalse();
        assertThat(ClientIpResolver.isIpAddress(null)).isFalse();

        assertThat(ClientIpResolver.isIpAddress("10.0.0.1")).isTrue();
        assertThat(ClientIpResolver.isIpAddress("255.255.255.255")).isTrue();
        assertThat(ClientIpResolver.isIpAddress("0.0.0.0")).isTrue();
    }

    @Test
    void acceptsWellFormedIpv6_andRejectsGarbage() {
        assertThat(ClientIpResolver.isIpAddress("2001:db8::1")).isTrue();
        assertThat(ClientIpResolver.isIpAddress("::1")).isTrue();
        assertThat(ClientIpResolver.isIpAddress("fe80::1%eth0")).isTrue();
        assertThat(ClientIpResolver.isIpAddress("::ffff:192.0.2.1")).isTrue();
        assertThat(ClientIpResolver.isIpAddress("2001:db8:::1")).isFalse();
        assertThat(ClientIpResolver.isIpAddress("[::1]")).isFalse();
    }

    @Test
    void neverReturnsNull_evenWithoutAnyAddress() {
        var req = MockServerHttpRequest.get("/x").build();
        assertThat(ClientIpResolver.resolve(req)).isNotBlank();
    }

    @Test
    void sameResultAsDirectCall_usedByOtherModules() {
        // 演示鉴权/限流侧用同一个静态入口，口径天然一致
        HttpHeaders headers = new HttpHeaders();
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x")
                .header(ClientIpResolver.XFF_HEADER, "192.0.2.10"));
        String ip = ClientIpResolver.resolve(exchange.getRequest());
        assertThat(ip).isEqualTo("192.0.2.10");
    }
}
