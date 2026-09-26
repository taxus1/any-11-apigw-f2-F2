package com.apigw.common.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayHeadersTest {

    @Test
    void acceptsCallerTraceId_withinWhitelist() {
        assertThat(GatewayHeaders.normalizeTraceId("caller-trace.001_ABC")).isEqualTo("caller-trace.001_ABC");
        assertThat(GatewayHeaders.normalizeTraceId("  spaced-id-1234  ")).isEqualTo("spaced-id-1234");
    }

    @Test
    void rejectsIllegalOrTooShortTraceId() {
        assertThat(GatewayHeaders.normalizeTraceId(null)).isNull();
        assertThat(GatewayHeaders.normalizeTraceId("short")).isNull();           // <8
        assertThat(GatewayHeaders.normalizeTraceId("has space 1234")).isNull();
        assertThat(GatewayHeaders.normalizeTraceId("a,b,c\r\nInjected:1")).isNull();
        String tooLong = "x".repeat(65);
        assertThat(GatewayHeaders.normalizeTraceId(tooLong)).isNull();
    }

    @Test
    void generatedRequestId_is32Hex_andSelfAccepting() {
        String id = GatewayHeaders.newRequestId();
        assertThat(id).hasSize(32).matches("[0-9a-f]{32}");
        assertThat(GatewayHeaders.normalizeTraceId(id)).isEqualTo(id);
    }

    @Test
    void appNoWhitelist() {
        assertThat(GatewayHeaders.normalizeAppNo("billing.app-1_2")).isEqualTo("billing.app-1_2");
        assertThat(GatewayHeaders.normalizeAppNo("")).isNull();
        assertThat(GatewayHeaders.normalizeAppNo("  ")).isNull();
        assertThat(GatewayHeaders.normalizeAppNo("a/b")).isNull();
        assertThat(GatewayHeaders.normalizeAppNo("x".repeat(65))).isNull();
    }
}
