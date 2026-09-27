package com.apigw.proxy.auth.user;

import com.apigw.common.web.GatewayHeaders;
import com.apigw.domain.route.GatewayRoute;
import com.apigw.proxy.error.GatewayErrors;
import com.apigw.proxy.error.UpstreamFailureKind;
import com.apigw.proxy.match.RouteMatcher;
import com.apigw.proxy.route.RouteCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 路由级用户登录鉴权 + 身份透传的总过滤器。仅在 {@code apigw.user-auth.enabled=true} 时装配，
 * 顺序在接入凭据鉴权之后、转发编排之前：
 *
 * <pre>
 * AppAuthWebFilter（HIGHEST+5，可选）
 *   → 本过滤器（HIGHEST+8）：先匹配路由，再按路由的 requireLogin 决定要不要登录
 *     → GatewayProxyWebFilter（HIGHEST+10）：转发
 * </pre>
 *
 * 关键口径（全类统一，别改散）：
 * 1. <b>先匹配路由、后谈鉴权</b>。是否要求登录是「这条路由」自己的标记，不是全局开关：
 *    开放路由（requireLogin=0）的请求即使没带令牌也绝不走进「拒登录」分支；
 *    内部路由（requireLogin=1）必须带一张验签通过、没过期、信息齐全的令牌，否则 401。
 *    开放/内部路由混用同一条链路，靠一次匹配分流，互不误伤。
 * 2. <b>开放路由遇到坏令牌的口径：放行，但不赋予身份</b>（见下「为什么」）。
 * 3. 匹配不到路由不在这里 401：放给转发过滤器回它的 404 NO_ROUTE，
 *    绝不因为没带令牌把一个「无路」请求提前变成 401。
 * 4. 所有失败对外只有同一种 401 {@code TOKEN_UNAUTHENTICATED} 固定文案，
 *    不区分「没带/签名错/过期/缺声明」，内部细节只进服务端 debug 日志。
 * 5. fail-closed：路由快照此刻读不出来（Redis 故障且无旧快照）→ 503，
 *    绝不猜成「开放」裸放行。
 *
 * <b>开放路由遇到坏令牌，为什么一律放行、按匿名处理？</b>
 * 开放路由的契约就是「谁都能打，令牌不是入场券」。浏览器/客户端普遍会在同一域名下
 * 对所有请求自动带上过期或无效的 Authorization（旧登录态、爬虫、探测请求都会这样）；
 * 若对坏令牌回 401，公共页面会被一张过期令牌打成登录失效，和「没带令牌照常放行」自相矛盾，
 * 前端还会被这个 401 误踢去登录页。所以口径是：开放路由尽力识别身份——
 * 令牌有效就按登录用户透传身份，令牌缺失/损坏/过期都与「没带」同等对待，照常放行、不写身份头。
 * 真正能区分匿名/登录用户的，是上游看到的 X-Auth-User 有无；坏令牌拿不到任何身份，
 * 因此「故意带坏令牌」换不来额外权限，没有放行它的安全代价。
 *
 * <b>身份头防伪造（硬安全线）</b>：无论路由开放与否，请求进来先把调用方自带的
 * X-Auth-User / X-Auth-Tenant / X-Gateway-Stamp 连同 Authorization 一起剥光，
 * 再由网关按自己的验签结果写 X-Auth-User / X-Auth-Tenant、按共享密钥盖 X-Gateway-Stamp。
 * 上游收到的这三个头只有「网关写的」这一个来源；原始令牌本身绝不向上游转发。
 */
@Slf4j
public class UserTokenAuthWebFilter implements WebFilter, Ordered {

    /** 接入凭据鉴权（HIGHEST+5）之后、转发编排（HIGHEST+10）之前。 */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 8;

    private static final List<String> PASSTHROUGH_PREFIXES = List.of("/api", "/actuator");

    private final RouteCatalog routeCatalog;
    private final RouteMatcher routeMatcher;
    private final JwtUserToken.Verifier tokenVerifier;
    private final GatewayStampSigner stampSigner;
    private final ObjectMapper objectMapper;

    public UserTokenAuthWebFilter(RouteCatalog routeCatalog,
                                  RouteMatcher routeMatcher,
                                  JwtUserToken.Verifier tokenVerifier,
                                  GatewayStampSigner stampSigner,
                                  ObjectMapper objectMapper) {
        this.routeCatalog = routeCatalog;
        this.routeMatcher = routeMatcher;
        this.tokenVerifier = tokenVerifier;
        this.stampSigner = stampSigner;
        this.objectMapper = objectMapper;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().pathWithinApplication().value();
        if (isPassthrough(path)) {
            // 管理接口/actuator 不属转发流量，原样交给后面（不剥头、不盖章）
            return chain.filter(exchange);
        }

        // 错误收口只包「取路由快照 + 匹配」这一步：下游链（chain.filter）的异常不在这里吞，
        // 转发过滤器有自己的错误收口，不能把它的故障误标成「登录鉴权配置不可用」
        return routeCatalog.routes()
                .<MatchResult>map(routes ->
                        new MatchResult(routeMatcher.match(routes, request), false))
                .onErrorResume(err -> failConfig(exchange, err)
                        .then(Mono.just(new MatchResult(null, true))))
                .flatMap(result -> {
                    if (result.configFailed()) {
                        // 503 答复已在 failConfig 写出，这里结束请求
                        return Mono.empty();
                    }
                    GatewayRoute route = result.route();
                    if (route == null) {
                        // 无路可走交给转发过滤器回 404；本过滤器不产生 401，
                        // 避免开放路由的坏请求/无令牌请求被提前拦在鉴权里
                        return chain.filter(exchange);
                    }
                    // 把匹配结果交给转发过滤器复用（同笔请求不再匹配第二次）
                    exchange.getAttributes().put(RouteCatalog.MATCHED_ROUTE_ATTRIBUTE, route);
                    return authenticateAndContinue(exchange, chain, request, route);
                });
    }

    /** 匹配结果：configFailed=true 表示快照读不出来（503 已回），route 为 null 表示没匹配上。 */
    private record MatchResult(GatewayRoute route, boolean configFailed) {
    }

    /**
     * 按路由的登录标记分流：内部路由必须令牌有效；开放路由尽力识别身份、失败按匿名放行。
     * 两条路径最终都汇到 {@link #continueWithIdentity}：剥光伪造头 → 写可信头 → 盖网关戳。
     * 校验拒绝直接返回「写 401 答复」的 Mono（响应由它收口，不再进入后续链路）。
     */
    private Mono<Void> authenticateAndContinue(ServerWebExchange exchange, WebFilterChain chain,
                                               ServerHttpRequest request, GatewayRoute route) {
        String token = extractBearer(request);

        if (!route.isLoginRequired()) {
            // 开放路由：令牌有效就带上身份，缺失/坏令牌一律按匿名放行（口径见类注释）
            JwtUserToken.Principal principal = null;
            if (token != null) {
                try {
                    principal = tokenVerifier.verify(token);
                } catch (InvalidTokenException e) {
                    log.debug("开放路由上的令牌未通过校验，按匿名放行 path={} reason={}",
                            request.getPath().pathWithinApplication().value(), e.getMessage());
                    principal = null;
                }
            }
            return continueWithIdentity(exchange, chain, request, principal, false);
        }

        // 内部路由：没令牌或令牌任何一点不过 → 统一 401，不透出细节
        if (token == null) {
            return reject(exchange, request, "NO_TOKEN");
        }
        try {
            JwtUserToken.Principal principal = tokenVerifier.verify(token);
            return continueWithIdentity(exchange, chain, request, principal, true);
        } catch (InvalidTokenException e) {
            return reject(exchange, request, e.code());
        }
    }

    /**
     * 剥掉调用方可能伪造的全部身份头与原始令牌，再写入网关自己的结果：
     * - 验签通过：写 X-Auth-User / X-Auth-Tenant（值再过一次白名单，非法字符不允许进头）；
     * - 匿名：两个身份头保持删除状态（上游看不到，就是匿名）；
     * - 每笔请求都盖 X-Gateway-Stamp（匿名也盖，戳的身份字段为空串）。
     *
     * 令牌签名有效但身份声明含写不进头的脏值时：内部路由按校验不过 401
     * （不能让「有效签名 + 脏声明」漏进上游）；开放路由与坏令牌同口径，按匿名放行。
     */
    private Mono<Void> continueWithIdentity(ServerWebExchange exchange, WebFilterChain chain,
                                            ServerHttpRequest request,
                                            JwtUserToken.Principal principal,
                                            boolean loginRequired) {
        String userId = principal == null ? null
                : GatewayHeaders.normalizeIdentityValue(principal.userId());
        String tenantId = principal == null ? null
                : GatewayHeaders.normalizeIdentityValue(principal.tenantId());

        if (principal != null && (userId == null || tenantId == null)) {
            if (loginRequired) {
                return reject(exchange, request, "BAD_IDENTITY_VALUE");
            }
            log.debug("开放路由上的令牌身份声明含非法字符，按匿名放行 path={}",
                    request.getPath().pathWithinApplication().value());
            userId = null;
            tenantId = null;
        }

        String method = request.getMethod() == null ? "-" : request.getMethod().name();
        String path = request.getPath().pathWithinApplication().value();
        String query = request.getURI().getRawQuery();
        // traceId 与转发侧同一套：带了合法 X-Trace-Id 就沿用，否则生成。
        // 写回入站 X-Trace-Id（只可能是白名单值），转发过滤器与上游 X-Gateway-Trace-Id 都沿用它，
        // 保证盖戳里签的 traceId、转发侧记录/回显的 traceId 是同一个
        String incomingTraceId = GatewayHeaders.normalizeTraceId(
                request.getHeaders().getFirst(GatewayHeaders.TRACE_ID_HEADER));
        final String traceId = incomingTraceId != null ? incomingTraceId : GatewayHeaders.newRequestId();
        String stamp = stampSigner.stamp(method, path, query, traceId, userId, tenantId);

        final String finalUserId = userId;
        final String finalTenantId = tenantId;
        ServerHttpRequest mutated = request.mutate().headers(headers -> {
            // 1. 先剥：调用方自带的同名身份头、伪造戳、原始令牌，一个不留
            GatewayHeaders.GATEWAY_MANAGED_HEADERS.forEach(headers::remove);
            headers.remove(GatewayHeaders.AUTHORIZATION_HEADER);
            // 2. 再写：只写网关验签/盖章的结果；匿名就不写身份头（保持缺失）
            if (finalUserId != null) {
                headers.set(GatewayHeaders.AUTH_USER_HEADER, finalUserId);
            }
            if (finalTenantId != null) {
                headers.set(GatewayHeaders.AUTH_TENANT_HEADER, finalTenantId);
            }
            headers.set(GatewayHeaders.GATEWAY_STAMP_HEADER, stamp);
            // 统一本次请求的 traceId（非法/缺失时是网关生成值）
            headers.set(GatewayHeaders.TRACE_ID_HEADER, traceId);
        }).build();

        return chain.filter(exchange.mutate().request(mutated).build());
    }

    /** 只认 Authorization: Bearer &lt;jwt&gt;；方案名大小写不敏感，其余形状一律视为「没带可用令牌」。 */
    private String extractBearer(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            return null;
        }
        String h = header.trim();
        String scheme = GatewayHeaders.BEARER_PREFIX.trim();
        // 必须是 "Bearer" + 至少一个空白 + 非空令牌；用 regionMatches 比较方案名，别把尾空格算进去
        if (h.length() <= scheme.length()
                || !h.regionMatches(true, 0, scheme, 0, scheme.length())
                || !Character.isWhitespace(h.charAt(scheme.length()))) {
            return null;
        }
        String token = h.substring(scheme.length() + 1).trim();
        return token.isEmpty() ? null : token;
    }

    private Mono<Void> reject(ServerWebExchange exchange, ServerHttpRequest request, String reason) {
        String traceId = resolveTraceId(exchange);
        // 服务端留具体原因（排障），调用方只拿同一种固定文案
        log.debug("用户登录鉴权拒绝 path={} traceId={} reason={}",
                request.getPath().pathWithinApplication().value(), traceId, reason);
        return GatewayErrors.write(exchange, objectMapper,
                UpstreamFailureKind.TOKEN_UNAUTHENTICATED, traceId, null);
    }

    private Mono<Void> failConfig(ServerWebExchange exchange, Throwable err) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.empty();
        }
        String traceId = resolveTraceId(exchange);
        log.warn("用户登录鉴权：路由配置不可用 traceId={}", traceId, err);
        return GatewayErrors.write(exchange, objectMapper,
                UpstreamFailureKind.TOKEN_CONFIG_UNAVAILABLE, traceId, err);
    }

    private String resolveTraceId(ServerWebExchange exchange) {
        String incoming = GatewayHeaders.normalizeTraceId(
                exchange.getRequest().getHeaders().getFirst(GatewayHeaders.TRACE_ID_HEADER));
        return incoming != null ? incoming : GatewayHeaders.newRequestId();
    }

    private boolean isPassthrough(String path) {
        for (String prefix : PASSTHROUGH_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }
}
