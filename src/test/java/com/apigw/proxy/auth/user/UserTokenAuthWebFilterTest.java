package com.apigw.proxy.auth.user;

import com.apigw.common.web.GatewayHeaders;
import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import com.apigw.proxy.InMemoryRouteStore;
import com.apigw.proxy.config.GatewayProxyProperties;
import com.apigw.proxy.match.RouteMatcher;
import com.apigw.proxy.route.RouteCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebHandler;
import org.springframework.web.server.adapter.HttpWebHandlerAdapter;
import org.springframework.web.server.handler.FilteringWebHandler;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UserTokenAuthWebFilter} 端到端测试（真实 Netty，无 Redis）。
 *
 * 链尾 WebHandler 扮演「上游」：把网关加工后的请求头回显成 JSON，断言全部基于上游真实收到的东西。
 *
 * 覆盖硬性要求：
 * - 路由级开关：开放路由无令牌放行；内部路由无令牌/坏令牌/过期/假签名一律 401 TOKEN_UNAUTHENTICATED；
 * - 过期边界卡死：now == exp 按过期处理；
 * - 透传：X-Auth-User / X-Auth-Tenant 来自验签结果；Authorization 原始令牌绝不到上游；
 * - 防伪造：调用方自带同名身份头/戳一律先剥再按验签结果写，伪造值到不了上游；
 * - 网关戳：上游可独立验真（JWT 身份与戳一致）；
 * - 开放/内部混用同一链路：开放路由的无令牌/坏令牌请求不被内部路由的鉴权误伤；
 * - 开放路由遇到坏令牌：放行且无身份头（一致性口径，见过滤器类注释）；
 * - /api 管理流量原样放行、不剥头不盖章；
 * - 错误答复不区分原因、不含内部细节。
 */
class UserTokenAuthWebFilterTest {

    private static final String TOKEN_SECRET = "token-secret-token-secret-token-secret-0001";
    private static final String STAMP_SECRET = "stamp-secret-stamp-secret-stamp-secret-0001";
    // 固定在「未来」时刻：签发与验签用同一把固定钟，避免用例随真实日期推移而把「10 分钟有效」
    // 的令牌变成已过期
    private static final Instant T0 = Instant.parse("2099-01-01T00:00:00Z");

    private InMemoryRouteStore store;
    private RouteCatalog catalog;
    private DisposableServer server;
    private String baseUrl;
    private WebClient client;

    private final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
    private final JwtUserToken.Signer signer = new JwtUserToken.Signer(TOKEN_SECRET, clock);

    /** 上游最近一次收到的请求头（链尾回显 + 这里留一份）。 */
    private volatile HttpHeaders lastUpstreamHeaders;

    @BeforeEach
    void setUp() {
        store = new InMemoryRouteStore();
        catalog = new RouteCatalog(store,
                new GatewayProxyProperties(Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofHours(1)));

        var verifier = new JwtUserToken.Verifier(TOKEN_SECRET, clock);
        var stampSigner = new GatewayStampSigner(STAMP_SECRET, clock);
        var filter = new UserTokenAuthWebFilter(catalog, new RouteMatcher(), verifier, stampSigner,
                new ObjectMapper());

        WebHandler tail = (ServerWebExchange exchange) -> {
            // 扮演上游：把网关送来的关键请求头回显，证明「上游实际收到什么」
            lastUpstreamHeaders = exchange.getRequest().getHeaders();
            HttpHeaders h = exchange.getRequest().getHeaders();
            String body = "{"
                    + "\"user\":" + json(h.getFirst(GatewayHeaders.AUTH_USER_HEADER)) + ","
                    + "\"tenant\":" + json(h.getFirst(GatewayHeaders.AUTH_TENANT_HEADER)) + ","
                    + "\"stamp\":" + json(h.getFirst(GatewayHeaders.GATEWAY_STAMP_HEADER)) + ","
                    + "\"authorization\":" + json(h.getFirst(HttpHeaders.AUTHORIZATION)) + ","
                    + "\"trace\":" + json(h.getFirst(GatewayHeaders.TRACE_ID_HEADER)) + "}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            exchange.getResponse().getHeaders().setContentType(
                    org.springframework.http.MediaType.APPLICATION_JSON);
            return exchange.getResponse().writeWith(
                    Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
        };
        WebHandler filtering = new FilteringWebHandler(tail, List.of(filter));
        HttpWebHandlerAdapter adapter = new HttpWebHandlerAdapter(filtering);
        adapter.afterPropertiesSet();

        server = HttpServer.create()
                .handle(new ReactorHttpHandlerAdapter((HttpHandler) adapter))
                .bindNow();
        baseUrl = "http://127.0.0.1:" + server.port();
        client = WebClient.builder().build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.disposeNow();
        }
    }

    private static String json(String v) {
        return v == null ? "null" : "\"" + v.replace("\"", "\\\"") + "\"";
    }

    private void loadRoutes(GatewayRoute... routes) {
        store.setRoutes(List.of(routes));
        catalog.refresh().block();
    }

    private GatewayRoute route(String no, String prefix, int requireLogin) {
        GatewayRoute r = GatewayRoute.create(no, no, "http://upstream:8080", 1, requireLogin, null);
        r.replaceRules(
                List.of(GatewayRule.create("REQUEST", "PATH_PREFIX", null, prefix, 1)),
                List.of());
        return r;
    }

    private Resp call(String path, Map<String, String> headers) {
        return client.get().uri(baseUrl + path)
                .headers(h -> headers.forEach(h::set))
                .exchangeToMono(resp -> resp.bodyToMono(String.class).defaultIfEmpty("")
                        .map(b -> new Resp(resp.statusCode().value(),
                                resp.headers().asHttpHeaders().getFirst("X-Gateway-Error"), b)))
                .block(Duration.ofSeconds(5));
    }

    private Resp get(String path) {
        return call(path, Map.of());
    }

    private Resp withToken(String path, String token) {
        return call(path, Map.of(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private String validToken() {
        return signer.issue("user-7", "tenant-9", Duration.ofMinutes(10));
    }

    record Resp(int status, String errorHeader, String body) {
    }

    // ---- 开放路由 ----

    @Test
    void openRoute_noToken_passesAndHasNoIdentityHeaders() {
        loadRoutes(route("pub", "/pub/", 0));
        Resp r = get("/pub/items");
        assertThat(r.status).isEqualTo(200);
        assertThat(r.body).contains("\"user\":null").contains("\"tenant\":null")
                .contains("\"authorization\":null");
        // 匿名请求也盖网关戳
        assertThat(lastUpstreamHeaders.getFirst(GatewayHeaders.GATEWAY_STAMP_HEADER)).startsWith("v1.");
    }

    @Test
    void openRoute_validToken_passesAndCarriesVerifiedIdentity() {
        loadRoutes(route("pub", "/pub/", 0));
        Resp r = withToken("/pub/items", validToken());
        assertThat(r.status).isEqualTo(200);
        assertThat(r.body).contains("\"user\":\"user-7\"").contains("\"tenant\":\"tenant-9\"")
                .contains("\"authorization\":null");
    }

    @Test
    void openRoute_badToken_passesAsAnonymous() {
        loadRoutes(route("pub", "/pub/", 0));
        Resp r = withToken("/pub/items", "garbage.garbage.garbage");
        assertThat(r.status).isEqualTo(200);
        assertThat(r.body).contains("\"user\":null").contains("\"tenant\":null");
    }

    @Test
    void openRoute_expiredToken_passesAsAnonymous() {
        loadRoutes(route("pub", "/pub/", 0));
        String expired = signer.issue("u", "t", T0.getEpochSecond() - 100, T0.getEpochSecond() - 1);
        Resp r = withToken("/pub/items", expired);
        assertThat(r.status).isEqualTo(200);
        assertThat(r.body).contains("\"user\":null");
    }

    @Test
    void openRoute_validSignatureButDirtyIdentity_passesAsAnonymous() {
        loadRoutes(route("pub", "/pub/", 0));
        // 身份值含写不进头的换行/非 ASCII：开放路由与坏令牌同口径，按匿名放行
        String token = signer.issue("bad\nuser", "tenant", Duration.ofMinutes(10));
        Resp r = withToken("/pub/items", token);
        assertThat(r.status).isEqualTo(200);
        assertThat(r.body).contains("\"user\":null").contains("\"tenant\":null");
    }

    @Test
    void internalRoute_validSignatureButDirtyIdentity_401() {
        loadRoutes(route("inner", "/inner/", 1));
        String token = signer.issue("bad\nuser", "tenant", Duration.ofMinutes(10));
        Resp r = withToken("/inner/data", token);
        assertThat(r.status).isEqualTo(401);
        assertThat(r.errorHeader).isEqualTo("TOKEN_UNAUTHENTICATED");
    }

    @Test
    void openRoute_spoofedIdentityHeaders_areStripped_andStampReplaced() {
        loadRoutes(route("pub", "/pub/", 0));
        Resp r = call("/pub/items", Map.of(
                GatewayHeaders.AUTH_USER_HEADER, "fake-admin",
                GatewayHeaders.AUTH_TENANT_HEADER, "fake-tenant",
                GatewayHeaders.GATEWAY_STAMP_HEADER, "v1.forged.signature"));
        assertThat(r.status).isEqualTo(200);
        // 伪造身份头到不了上游（匿名 → 无头）；伪造戳被网关自己的戳顶掉
        assertThat(r.body).contains("\"user\":null").contains("\"tenant\":null");
        assertThat(lastUpstreamHeaders.getFirst(GatewayHeaders.GATEWAY_STAMP_HEADER))
                .startsWith("v1.").isNotEqualTo("v1.forged.signature");
    }

    // ---- 内部路由 ----

    @Test
    void internalRoute_noToken_401() {
        loadRoutes(route("inner", "/inner/", 1));
        Resp r = get("/inner/data");
        assertThat(r.status).isEqualTo(401);
        assertThat(r.errorHeader).isEqualTo("TOKEN_UNAUTHENTICATED");
        assertThat(r.body).doesNotContain("expired").doesNotContain("签名");
    }

    @Test
    void internalRoute_validToken_passesWithIdentity_andNoRawToken() {
        loadRoutes(route("inner", "/inner/", 1));
        Resp r = withToken("/inner/data", validToken());
        assertThat(r.status).isEqualTo(200);
        assertThat(r.body).contains("\"user\":\"user-7\"").contains("\"tenant\":\"tenant-9\"")
                .contains("\"authorization\":null");
    }

    @Test
    void internalRoute_badSignatureToken_401() {
        loadRoutes(route("inner", "/inner/", 1));
        String t = validToken();
        String[] parts = t.split("\\.");
        char[] sig = parts[2].toCharArray();
        sig[0] = sig[0] == 'A' ? 'B' : 'A';
        Resp r = withToken("/inner/data", parts[0] + "." + parts[1] + "." + new String(sig));
        assertThat(r.status).isEqualTo(401);
        assertThat(r.errorHeader).isEqualTo("TOKEN_UNAUTHENTICATED");
    }

    @Test
    void internalRoute_signedByOtherSecret_401() {
        loadRoutes(route("inner", "/inner/", 1));
        String foreign = new JwtUserToken.Signer(
                "another-secret-another-secret-another-00", clock)
                .issue("user-7", "tenant-9", Duration.ofMinutes(10));
        Resp r = withToken("/inner/data", foreign);
        assertThat(r.status).isEqualTo(401);
    }

    @Test
    void internalRoute_algNoneForgery_401() {
        loadRoutes(route("inner", "/inner/", 1));
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"u\",\"tenant\":\"t\",\"exp\":9999999999}".getBytes());
        String header = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes());
        Resp r = withToken("/inner/data", header + "." + payload + ".");
        assertThat(r.status).isEqualTo(401);
    }

    @Test
    void internalRoute_expiredToken_401_andExactlyAtExpiry_401() {
        loadRoutes(route("inner", "/inner/", 1));
        String oneSecondAgo = signer.issue("u", "t", T0.getEpochSecond() - 100, T0.getEpochSecond() - 1);
        assertThat(withToken("/inner/data", oneSecondAgo).status).isEqualTo(401);

        // 正好压在过期时刻：按过期处理，没有宽限
        String onTheLine = signer.issue("u", "t", T0.getEpochSecond() - 1, T0.getEpochSecond());
        Resp r = withToken("/inner/data", onTheLine);
        assertThat(r.status).isEqualTo(401);
        assertThat(r.errorHeader).isEqualTo("TOKEN_UNAUTHENTICATED");
    }

    @Test
    void internalRoute_tokenMissingClaims_401() {
        loadRoutes(route("inner", "/inner/", 1));
        // 手签一张缺 tenant 的合法签名令牌
        String header = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes());
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"u\",\"iat\":1,\"exp\":9999999999}".getBytes());
        String body = header + "." + payload;
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    TOKEN_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sig = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
            assertThat(withToken("/inner/data", body + "." + sig).status).isEqualTo(401);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void internalRoute_garbageAuthorizationHeader_401() {
        loadRoutes(route("inner", "/inner/", 1));
        assertThat(call("/inner/data", Map.of(HttpHeaders.AUTHORIZATION, "Basic abc123")).status)
                .isEqualTo(401);
        assertThat(call("/inner/data", Map.of(HttpHeaders.AUTHORIZATION, "Bearer")).status)
                .isEqualTo(401);
        assertThat(call("/inner/data", Map.of(HttpHeaders.AUTHORIZATION, "Bearer ")).status)
                .isEqualTo(401);
    }

    @Test
    void internalRoute_spoofedIdentityHeaders_areIgnored_401WithoutToken() {
        loadRoutes(route("inner", "/inner/", 1));
        Resp r = call("/inner/data", Map.of(
                GatewayHeaders.AUTH_USER_HEADER, "root",
                GatewayHeaders.AUTH_TENANT_HEADER, "root",
                GatewayHeaders.GATEWAY_STAMP_HEADER, "v1.fake.fake"));
        // 带多少伪造身份头都不能顶替令牌
        assertThat(r.status).isEqualTo(401);
    }

    @Test
    void internalRoute_validTokenButSpoofedHeaders_spoofedValuesReplacedByVerified() {
        loadRoutes(route("inner", "/inner/", 1));
        Resp r = call("/inner/data", Map.of(
                HttpHeaders.AUTHORIZATION, "Bearer " + validToken(),
                GatewayHeaders.AUTH_USER_HEADER, "spoofed",
                GatewayHeaders.AUTH_TENANT_HEADER, "spoofed",
                GatewayHeaders.GATEWAY_STAMP_HEADER, "v1.spoofed.spoofed"));
        assertThat(r.status).isEqualTo(200);
        // 上游只拿到验签身份，伪造值被剥掉
        assertThat(r.body).contains("\"user\":\"user-7\"").contains("\"tenant\":\"tenant-9\"");
        String stamp = lastUpstreamHeaders.getFirst(GatewayHeaders.GATEWAY_STAMP_HEADER);
        assertThat(stamp).startsWith("v1.").isNotEqualTo("v1.spoofed.spoofed");
    }

    @Test
    void gatewayStamp_isVerifiableUpstream_andMatchesIdentity() {
        loadRoutes(route("inner", "/inner/", 1));
        Resp r = withToken("/inner/data?x=1", validToken());
        assertThat(r.status).isEqualTo(200);
        String stamp = lastUpstreamHeaders.getFirst(GatewayHeaders.GATEWAY_STAMP_HEADER);
        String trace = lastUpstreamHeaders.getFirst(GatewayHeaders.TRACE_ID_HEADER);
        assertThat(trace).isNotBlank();
        assertThat(StampTestUtil.verify(stamp, STAMP_SECRET, "GET", "/inner/data", "x=1",
                trace, "user-7", "tenant-9")).isTrue();
        // 戳与身份绑定：用别的身份验不过
        assertThat(StampTestUtil.verify(stamp, STAMP_SECRET, "GET", "/inner/data", "x=1",
                trace, "someone-else", "tenant-9")).isFalse();
    }

    // ---- 混用 / 边界 ----

    @Test
    void openAndInternalRoutesShareChain_withoutCrossInterference() {
        loadRoutes(route("pub", "/pub/", 0), route("inner", "/inner/", 1));
        // 开放路由无令牌正常放行，不会被「内部路由要登录」牵连
        assertThat(get("/pub/a").status).isEqualTo(200);
        // 内部路由无令牌照样 401，不会被开放路由「带松」
        assertThat(get("/inner/b").status).isEqualTo(401);
        // 同一张有效令牌打两边都过，且开放路由上也能识别出身份
        String token = validToken();
        assertThat(withToken("/pub/a", token).body).contains("\"user\":\"user-7\"");
        assertThat(withToken("/inner/b", token).status).isEqualTo(200);
    }

    @Test
    void unmatchedRoute_fallsThroughWith404FromProxy_not401() {
        loadRoutes(route("pub", "/pub/", 0));
        // 过滤器不匹配路由时直接放行给链尾（真实环境是转发过滤器回 404 NO_ROUTE），
        // 这里链尾固定回 200，证明「无令牌 + 无路」没被本过滤器提前 401
        assertThat(get("/nowhere").status).isEqualTo(200);
    }

    @Test
    void managementApi_isPassedThroughUntouched() {
        Resp r = call("/api/gateway/routes", Map.of(
                HttpHeaders.AUTHORIZATION, "Bearer whatever",
                GatewayHeaders.AUTH_USER_HEADER, "left-as-is"));
        assertThat(r.status).isEqualTo(200);
        // /api 不剥头、不盖章：回显里 Authorization 原样
        assertThat(r.body).contains("\"authorization\":\"Bearer whatever\"")
                .contains("\"user\":\"left-as-is\"");
        assertThat(r.body).contains("\"stamp\":null");
    }

    @Test
    void traceIdFromCaller_isReused_whenValid() {
        loadRoutes(route("inner", "/inner/", 1));
        Resp r = call("/inner/data", Map.of(
                HttpHeaders.AUTHORIZATION, "Bearer " + validToken(),
                GatewayHeaders.TRACE_ID_HEADER, "traceabcd1234"));
        assertThat(r.status).isEqualTo(200);
        assertThat(r.body).contains("\"trace\":\"traceabcd1234\"");
    }

    @Test
    void errorResponseIsUniformAndDoesNotLeakInternals() {
        loadRoutes(route("inner", "/inner/", 1));
        // 固定 traceId，便于逐字比较两次答复
        Resp noToken = call("/inner/data", Map.of(GatewayHeaders.TRACE_ID_HEADER, "traceabcd1234"));
        Resp badToken = call("/inner/data", Map.of(
                HttpHeaders.AUTHORIZATION, "Bearer a.b.c",
                GatewayHeaders.TRACE_ID_HEADER, "traceabcd1234"));
        assertThat(noToken.status).isEqualTo(badToken.status).isEqualTo(401);
        assertThat(noToken.errorHeader).isEqualTo(badToken.errorHeader).isEqualTo("TOKEN_UNAUTHENTICATED");
        // 两种失败文案一致，无法据此区分「没带」与「坏令牌」
        assertThat(noToken.body).isEqualTo(badToken.body);
        assertThat(noToken.body).contains("TOKEN_UNAUTHENTICATED").doesNotContain("NullPointer");
    }
}
