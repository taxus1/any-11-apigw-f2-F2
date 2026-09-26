package com.apigw.interfaces.rest.app;

import com.apigw.application.app.ClientAppService;
import com.apigw.common.exception.BizException;
import com.apigw.common.exception.GlobalExceptionHandler;
import com.apigw.domain.app.AppCredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 接入管理接口的 HTTP 切片测试：Controller 真实、Service mock，不碰库。
 * 覆盖统一返回结构、创建响应里一次性密钥与提示、错误收口、来路增删、停用启用幂等入口。
 */
class ClientAppControllerWebTest {

    private ClientAppService service;
    private WebTestClient web;
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = mock(ClientAppService.class);
        web = WebTestClient.bindToController(new ClientAppController(service, clock))
                .controllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private com.apigw.domain.app.ClientApp appAggregate() {
        return com.apigw.domain.app.ClientApp.create("app-1", "应用一",
                "abcdefghijklmnopqrstuvwxyz0123456789ABCD", null, 1, "张三", "备注", "admin", clock);
    }

    @Test
    void create_returnsDetailAndOneTimeSecret_neverHash() {
        when(service.create(eq("app-1"), eq("应用一"), any(), any(), any(), any(), any()))
                .thenReturn(new ClientAppService.CreatedApp(appAggregate(), "PLAIN-SECRET-VALUE-0000000000000"));

        web.post().uri("/api/gateway/apps")
                .bodyValue(java.util.Map.of(
                        "appNo", "app-1",
                        "appName", "应用一"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.app.appNo").isEqualTo("app-1")
                .jsonPath("$.data.secret").isEqualTo("PLAIN-SECRET-VALUE-0000000000000")
                .jsonPath("$.data.secretNotice").isNotEmpty()
                // 详情对象里没有任何密钥字段
                .jsonPath("$.data.app.secret").doesNotExist()
                .jsonPath("$.data.app.secretHash").doesNotExist();
    }

    @Test
    void create_badExpiryTime_returnsBizError() {
        web.post().uri("/api/gateway/apps")
                .bodyValue(java.util.Map.of(
                        "appNo", "app-1",
                        "appName", "应用一",
                        "secretExpiresAt", "not-a-time"))
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(1)
                .jsonPath("$.msg").isEqualTo("密钥有效期应为 ISO-8601，如 2027-09-26T10:00:00Z");
    }

    @Test
    void duplicateAppNo_returnsBizMessage() {
        when(service.create(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new BizException("应用编号已被占用（停用的应用也占号）：app-1"));
        web.post().uri("/api/gateway/apps")
                .bodyValue(java.util.Map.of("appNo", "app-1", "appName", "x"))
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(1)
                .jsonPath("$.msg").isEqualTo("应用编号已被占用（停用的应用也占号）：app-1");
    }

    @Test
    void detail_missing_returns404Code() {
        when(service.detail("ghost")).thenThrow(new BizException(404, "应用不存在：ghost"));
        web.get().uri("/api/gateway/apps/ghost")
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(404)
                .jsonPath("$.msg").isEqualTo("应用不存在：ghost");
    }

    @Test
    void list_returnsPageShapeWithOriginCount() {
        var row = new AppCredentialRepository.AppRow(1L, "app-1", "应用一",
                null, 1, "c", "r", "admin",
                Instant.parse("2026-09-26T00:00:00Z"), 3);
        when(service.page(1, 20, null))
                .thenReturn(new com.apigw.infrastructure.store.dto.PageResult<>(List.of(row), 1, 1, 20));
        web.get().uri("/api/gateway/apps")
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.total").isEqualTo(1)
                .jsonPath("$.data.content[0].appNo").isEqualTo("app-1")
                .jsonPath("$.data.content[0].originCount").isEqualTo(3)
                .jsonPath("$.data.content[0].secretHash").doesNotExist()
                .jsonPath("$.data.content[0].secretNeverExpires").isEqualTo(true);
    }

    @Test
    void disable_enable_endpoints_returnSuccess() {
        doNothing().when(service).disable("app-1");
        doNothing().when(service).enable("app-1");
        web.put().uri("/api/gateway/apps/app-1/disable").exchange().expectBody()
                .jsonPath("$.code").isEqualTo(0);
        web.put().uri("/api/gateway/apps/app-1/enable").exchange().expectBody()
                .jsonPath("$.code").isEqualTo(0);
    }

    @Test
    void disable_missingApp_returns404() {
        doThrow(new BizException(404, "应用不存在：ghost")).when(service).disable("ghost");
        web.put().uri("/api/gateway/apps/ghost/disable").exchange().expectBody()
                .jsonPath("$.code").isEqualTo(404);
    }

    @Test
    void origins_getAddDelete() {
        when(service.listOrigins("app-1")).thenReturn(List.of("10.0.0.1", "10.0.0.2"));
        when(service.addOrigin(eq("app-1"), eq("10.0.0.3"))).thenReturn(true);
        when(service.removeOrigin(eq("app-1"), eq("10.0.0.1"))).thenReturn(true);

        web.get().uri("/api/gateway/apps/app-1/origins").exchange().expectBody()
                .jsonPath("$.data.restrict").isEqualTo(true)
                .jsonPath("$.data.count").isEqualTo(2)
                .jsonPath("$.data.origins[0]").isEqualTo("10.0.0.1");

        web.post().uri("/api/gateway/apps/app-1/origins")
                .bodyValue(java.util.Map.of("ip", "10.0.0.3"))
                .exchange().expectBody().jsonPath("$.code").isEqualTo(0);

        // 删除走 query 参数（IPv6 里有冒号，放 path 会歧义）
        web.delete().uri("/api/gateway/apps/app-1/origins?ip=10.0.0.1")
                .exchange().expectBody().jsonPath("$.code").isEqualTo(0);
    }

    @Test
    void addOrigin_blankIp_rejected() {
        web.post().uri("/api/gateway/apps/app-1/origins")
                .bodyValue(java.util.Map.of("ip", "  "))
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(1)
                .jsonPath("$.msg").isEqualTo("ip 不能为空");
    }
}
