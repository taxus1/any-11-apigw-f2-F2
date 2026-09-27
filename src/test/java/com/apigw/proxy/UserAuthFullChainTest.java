package com.apigw.proxy;

import com.apigw.common.web.GatewayHeaders;
import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import com.apigw.proxy.accesslog.AccessLogRecorder;
import com.apigw.proxy.auth.user.GatewayStampSigner;
import com.apigw.proxy.auth.user.JwtUserToken;
import com.apigw.proxy.auth.user.OrderedCorsWebFilter;
import com.apigw.proxy.auth.user.UserAuthProperties;
import com.apigw.proxy.auth.user.UserTokenAuthWebFilter;
import com.apigw.proxy.config.GatewayProxyProperties;
import com.apigw.proxy.forward.UpstreamForwarder;
import com.apigw.proxy.match.RouteMatcher;
import com.apigw.proxy.route.RouteCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.WebHandler;
import org.springframework.web.server.adapter.HttpWebHandlerAdapter;
import org.springframework.web.server.handler.FilteringWebHandler;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全链路端到端：CORS 过滤器 + 用户登录鉴权过滤器 + 转发过滤器 + 真实上游（JDK HttpServer）。
 *
 * 与 {@code UserTokenAuthWebFilterTest}（链尾回显）不同，这里断言的是
 * <b>真实上游收到的报文</b>与<b>调用方收到的响应</b>，覆盖：
 * - 内部路由：有效令牌 → 上游收到 X-Auth-User/X-Auth-Tenant（验签值）、X-Gateway-Stamp，
 *   且收不到 Authorization（原始令牌不上递）；
 * - 调用方伪造同名身份头 → 上游只拿到验签值，伪造值消失；
 * - 无令牌打内部路由 → 401，上游根本没被碰；
 * - 开放路由无令牌 → 正常转发，上游看不到任何身份头；
 * - CORS：预检在鉴权之前被答复（带 Authorization 的正式请求才发得出去），
 *   响应带 Access-Control-Expose-Headers 放行三个透传头，前端读得到。
 */
class UserAuthFullChainTest {

    private static final String TOKEN_SECRET = "token-secret-token-secret-token-secret-0001";
    private static final String STAMP_SECRET = "stamp-secret-stamp-secret-stamp-secret-0001";
    private static final Instant T0 = Instant.parse("2099-01-01T00:00:00Z");

    private FakeUpstream upstream;
    private InMemoryRouteStore store;
    private RouteCatalog catalog;
    private DisposableServer server;
    private String baseUrl;
    private WebClient client;

    private final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
    private final JwtUserToken.Signer signer = new JwtUserToken.Signer(TOKEN_SECRET, clock);

    @BeforeEach
    void setUp() throws Exception {
        upstream = new FakeUpstream();
        store = new InMemoryRouteStore();
        var props = new GatewayProxyProperties(
                Duration.ofMillis(500), Duration.ofMillis(800), Duration.ofHours(1));
        catalog = new RouteCatalog(store, props);

        var nettyClient = reactor.netty.http.client.HttpClient.create()
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 500)
                .responseTimeout(Duration.ofMillis(800));
        WebClient webClient = WebClient.builder()
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(nettyClient))
                .build();

        var matcher = new RouteMatcher();
        var objectMapper = new ObjectMapper();
        var proxyFilter = new GatewayProxyWebFilter(
                catalog, matcher, new UpstreamForwarder(webClient),
                new AccessLogRecorder(), e -> { }, objectMapper);
        var authFilter = new UserTokenAuthWebFilter(catalog, matcher,
                new JwtUserToken.Verifier(TOKEN_SECRET, clock),
                new GatewayStampSigner(STAMP_SECRET, clock), objectMapper);
        var corsFilter = OrderedCorsWebFilter.create(new UserAuthProperties(
                true, TOKEN_SECRET, STAMP_SECRET, null, null));

        WebHandler tail = exchange -> {
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            return exchange.getResponse().setComplete();
        };
        // FilteringWebHandler 不排序、按传入顺序执行；真实容器里 Spring Boot 按 Ordered 排成
        // cors(+6) → auth(+8) → proxy(+10)，这里显式按同一顺序传入
        WebHandler filtering = new FilteringWebHandler(tail, List.of(corsFilter, authFilter, proxyFilter));
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
        upstream.close();
    }

    private void loadRoutes(GatewayRoute... routes) {
        store.setRoutes(List.of(routes));
        catalog.refresh().block();
    }

    private GatewayRoute route(String no, String prefix, int requireLogin) {
        GatewayRoute r = GatewayRoute.create(no, no, upstream.baseUrl(), 1, requireLogin, null);
        r.replaceRules(
                List.of(GatewayRule.create("REQUEST", "PATH_PREFIX", null, prefix, 1)),
                List.of());
        return r;
    }

    private String validToken() {
        return signer.issue("user-7", "tenant-9", Duration.ofMinutes(10));
    }

    record Resp(int status, HttpHeaders headers, String body) {
        String header(String name) {
            return headers.getFirst(name);
        }
    }

    private Resp call(String method, String path, Map<String, String> headers) {
        return client.method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(baseUrl + path)
                .headers(h -> headers.forEach(h::set))
                .exchangeToMono(resp -> resp.bodyToMono(String.class).defaultIfEmpty("")
                        .map(b -> new Resp(resp.statusCode().value(), resp.headers().asHttpHeaders(), b)))
                .block(Duration.ofSeconds(5));
    }

    // ---- 内部路由 ----

    @Test
    void internalRoute_validToken_upstreamSeesVerifiedIdentity_andNoRawToken() {
        loadRoutes(route("inner", "/inner/", 1));
        Resp r = call("GET", "/inner/data", Map.of(
                HttpHeaders.AUTHORIZATION, "Bearer " + validToken()));
        assertThat(r.status).isEqualTo(200);

        HttpExchange got = upstream.lastExchange();
        assertThat(got).isNotNull();
        var h = got.getRequestHeaders();
        assertThat(h.getFirst(GatewayHeaders.AUTH_USER_HEADER)).isEqualTo("user-7");
        assertThat(h.getFirst(GatewayHeaders.AUTH_TENANT_HEADER)).isEqualTo("tenant-9");
        // 原始令牌绝不上递
        assertThat(h.getFirst(HttpHeaders.AUTHORIZATION)).isNull();
        // 网关戳存在且上游可验真
        String stamp = h.getFirst(GatewayHeaders.GATEWAY_STAMP_HEADER);
        assertThat(stamp).startsWith("v1.");
        String trace = h.getFirst("X-Gateway-Trace-Id");
        assertThat(com.apigw.proxy.auth.user.StampTestUtil.verify(stamp, STAMP_SECRET,
                "GET", "/inner/data", null, trace, "user-7", "tenant-9")).isTrue();
    }

    @Test
    void internalRoute_spoofedIdentityHeaders_upstreamSeesOnlyVerifiedValues() {
        loadRoutes(route("inner", "/inner/", 1));
        Resp r = call("GET", "/inner/data", Map.of(
                HttpHeaders.AUTHORIZATION, "Bearer " + validToken(),
                GatewayHeaders.AUTH_USER_HEADER, "spoofed-admin",
                GatewayHeaders.AUTH_TENANT_HEADER, "spoofed-tenant",
                GatewayHeaders.GATEWAY_STAMP_HEADER, "v1.spoofed.spoofed"));
        assertThat(r.status).isEqualTo(200);
        var h = upstream.lastExchange().getRequestHeaders();
        assertThat(h.getFirst(GatewayHeaders.AUTH_USER_HEADER)).isEqualTo("user-7");
        assertThat(h.getFirst(GatewayHeaders.AUTH_TENANT_HEADER)).isEqualTo("tenant-9");
        assertThat(h.getFirst(GatewayHeaders.GATEWAY_STAMP_HEADER))
                .isNotEqualTo("v1.spoofed.spoofed").startsWith("v1.");
    }

    @Test
    void internalRoute_noToken_401_andUpstreamNeverTouched() {
        loadRoutes(route("inner", "/inner/", 1));
        Resp r = call("GET", "/inner/data", Map.of());
        assertThat(r.status).isEqualTo(401);
        assertThat(r.header("X-Gateway-Error")).isEqualTo("TOKEN_UNAUTHENTICATED");
        assertThat(upstream.lastExchange()).isNull();
    }

    // ---- 开放路由 ----

    @Test
    void openRoute_noToken_forwards_andUpstreamSeesNoIdentityHeaders() {
        loadRoutes(route("pub", "/pub/", 0));
        Resp r = call("GET", "/pub/items", Map.of());
        assertThat(r.status).isEqualTo(200);
        var h = upstream.lastExchange().getRequestHeaders();
        assertThat(h.getFirst(GatewayHeaders.AUTH_USER_HEADER)).isNull();
        assertThat(h.getFirst(GatewayHeaders.AUTH_TENANT_HEADER)).isNull();
        // 匿名也盖章
        assertThat(h.getFirst(GatewayHeaders.GATEWAY_STAMP_HEADER)).startsWith("v1.");
    }

    @Test
    void openRoute_spoofedIdentityHeaders_upstreamSeesNone() {
        loadRoutes(route("pub", "/pub/", 0));
        Resp r = call("GET", "/pub/items", Map.of(
                GatewayHeaders.AUTH_USER_HEADER, "fake",
                GatewayHeaders.AUTH_TENANT_HEADER, "fake",
                GatewayHeaders.GATEWAY_STAMP_HEADER, "v1.fake.fake"));
        assertThat(r.status).isEqualTo(200);
        var h = upstream.lastExchange().getRequestHeaders();
        assertThat(h.getFirst(GatewayHeaders.AUTH_USER_HEADER)).isNull();
        assertThat(h.getFirst(GatewayHeaders.AUTH_TENANT_HEADER)).isNull();
        assertThat(h.getFirst(GatewayHeaders.GATEWAY_STAMP_HEADER))
                .isNotEqualTo("v1.fake.fake").startsWith("v1.");
    }

    // ---- CORS ----

    @Test
    void corsPreflight_isAnsweredBeforeAuth_andAllowsAuthorizationHeader() {
        loadRoutes(route("inner", "/inner/", 1));
        // 预检不带令牌：必须不被内部路由的 401 挡掉
        Resp preflight = call("OPTIONS", "/inner/data", Map.of(
                "Origin", "https://web.example.com",
                "Access-Control-Request-Method", "GET",
                "Access-Control-Request-Headers", "authorization"));
        assertThat(preflight.status).isEqualTo(200);
        assertThat(preflight.header("Access-Control-Allow-Origin")).isEqualTo("*");
        assertThat(preflight.header("Access-Control-Allow-Headers").toLowerCase())
                .contains("authorization");
        // 预检不往上游打
        assertThat(upstream.lastExchange()).isNull();
    }

    @Test
    void corsActualResponse_exposesGatewayIdentityHeaders_toBrowserJs() {
        loadRoutes(route("inner", "/inner/", 1));
        Resp r = call("GET", "/inner/data", Map.of(
                "Origin", "https://web.example.com",
                HttpHeaders.AUTHORIZATION, "Bearer " + validToken()));
        assertThat(r.status).isEqualTo(200);
        String expose = r.header("Access-Control-Expose-Headers");
        assertThat(expose).isNotNull();
        assertThat(expose.toLowerCase())
                .contains("x-auth-user").contains("x-auth-tenant").contains("x-gateway-stamp");
        assertThat(r.header("Access-Control-Allow-Origin")).isEqualTo("*");
    }
}
