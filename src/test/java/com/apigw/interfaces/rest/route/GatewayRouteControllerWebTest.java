package com.apigw.interfaces.rest.route;

import com.apigw.application.route.GatewayRouteAppService;
import com.apigw.common.exception.BizException;
import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import com.apigw.domain.route.RuleTypes;
import com.apigw.infrastructure.store.RouteStore;
import com.apigw.common.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 管理接口 Web 切片测试：Controller + AppService + 领域聚合全部真实，
 * 只把 {@link RouteStore} mock 掉，所以不依赖 Redis 也能验证：
 * - HTTP 协议适配、统一返回结构、全局异常收口；
 * - 请求体进入时的聚合校验（顺序号、类型、上游地址等都在碰 Redis 之前发生）；
 * - 分页数字与每行子项计数的真实计算。
 *
 * 真正连 Redis 的原子占位/乐观锁/级联删见 RouteStoreTest 与 GatewayRouteControllerIT。
 */
class GatewayRouteControllerWebTest {

    private RouteStore store;
    private WebTestClient web;

    @BeforeEach
    void setUp() {
        store = mock(RouteStore.class);
        var events = mock(org.springframework.context.ApplicationEventPublisher.class);
        var appService = new GatewayRouteAppService(store, events);
        web = WebTestClient.bindToController(new GatewayRouteController(appService))
                .controllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private Map<String, Object> rule(String type, String name, String value, int sortNo) {
        var m = new java.util.HashMap<String, Object>();
        m.put("type", type);
        if (name != null) {
            m.put("name", name);
        }
        if (value != null) {
            m.put("value", value);
        }
        m.put("sortNo", sortNo);
        return m;
    }

    private Map<String, Object> body(String routeNo, String name, String upstream,
                                     Integer version,
                                     List<Map<String, Object>> conditions,
                                     List<Map<String, Object>> actions) {
        var m = new java.util.HashMap<String, Object>();
        m.put("routeNo", routeNo);
        m.put("name", name);
        m.put("upstream", upstream);
        m.put("enabled", 1);
        if (version != null) {
            m.put("version", version);
        }
        m.put("conditions", conditions);
        m.put("actions", actions);
        return m;
    }

    @Test
    void create_valid_returnsDetailWithVersionZero() {
        when(store.create(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        web.post().uri("/api/gateway/routes")
                .bodyValue(body("order-01", "订单", "http://order-svc:8080", null,
                        List.of(rule("PATH_PREFIX", null, "/order/", 1),
                                rule("METHOD", null, "GET", 2)),
                        List.of(rule("REQ_ADD_HEADER", "X-Gw", "1", 1))))
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.routeNo").isEqualTo("order-01")
                .jsonPath("$.data.version").isEqualTo(0)
                .jsonPath("$.data.conditions.length()").isEqualTo(2)
                .jsonPath("$.data.actions[0].stage").isEqualTo("REQUEST");
    }

    @Test
    void create_duplicateSortNo_rejectedBeforeTouchingStore() {
        web.post().uri("/api/gateway/routes")
                .bodyValue(body("sort-01", "n", "http://h:8080", null,
                        List.of(rule("PATH_PREFIX", null, "/a/", 1),
                                rule("METHOD", null, "GET", 1)),
                        List.of()))
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(1)
                .jsonPath("$.msg")
                .isEqualTo("匹配条件第 1 条与第 2 条的顺序号撞了，都是 1，同组内顺序号不能重");
    }

    @Test
    void create_gapSortNo_rejected() {
        web.post().uri("/api/gateway/routes")
                .bodyValue(body("sort-02", "n", "http://h:8080", null,
                        List.of(rule("PATH_PREFIX", null, "/a/", 1),
                                rule("METHOD", null, "GET", 3)),
                        List.of()))
                .exchange().expectBody()
                .jsonPath("$.msg")
                .isEqualTo("匹配条件的顺序号必须从 1 起连续不跳号，缺了 2（现在有 2 条）");
    }

    @Test
    void create_unknownActionType_rejectedWithPosition() {
        web.post().uri("/api/gateway/routes")
                .bodyValue(body("t-01", "n", "http://h:8080", null,
                        List.of(rule("PATH_PREFIX", null, "/a/", 1)),
                        List.of(rule("REWRITE_BODY", "X-K", "v", 1))))
                .exchange().expectBody()
                .jsonPath("$.msg").value(v -> org.assertj.core.api.Assertions.assertThat(v.toString())
                        .contains("转发动作第 1 条的类型不支持：REWRITE_BODY"));
    }

    @Test
    void create_badUpstream_rejected() {
        web.post().uri("/api/gateway/routes")
                .bodyValue(body("u-01", "n", "http://###", null,
                        List.of(rule("PATH_PREFIX", null, "/a/", 1)), List.of()))
                .exchange().expectBody()
                .jsonPath("$.msg").value(v -> org.assertj.core.api.Assertions.assertThat(v.toString())
                        .containsAnyOf("不是合法的 URL", "主机:端口不合法"));
    }

    @Test
    void update_routeNoMismatch_rejected() {
        web.put().uri("/api/gateway/routes/order-01")
                .bodyValue(body("order-02", "n", "http://h:8080", 0,
                        List.of(rule("PATH_PREFIX", null, "/a/", 1)), List.of()))
                .exchange().expectBody()
                .jsonPath("$.msg").value(v -> org.assertj.core.api.Assertions.assertThat(v.toString())
                        .startsWith("路由编号建后不可修改"));
    }

    @Test
    void update_staleVersion_returns409() {
        when(store.update(any())).thenReturn(Mono.error(
                new BizException(409, "你这份配置已经旧了（当前版本 1，你手上是 0），请重新拉取后再提交")));

        web.put().uri("/api/gateway/routes/order-01")
                .bodyValue(body("order-01", "n", "http://h:8080", 0,
                        List.of(rule("PATH_PREFIX", null, "/a/", 1)), List.of()))
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(409)
                .jsonPath("$.msg").value(v -> org.assertj.core.api.Assertions.assertThat(v.toString())
                        .contains("你这份配置已经旧了"));
    }

    @Test
    void update_withoutVersion_rejectedWithClearMessage() {
        // 真实 RouteStore 的版本检查发生在碰 Redis 之前，null redis 也能验证到这条
        var realStore = new RouteStore(null, new ObjectMapper());
        var events = mock(org.springframework.context.ApplicationEventPublisher.class);
        var client = WebTestClient.bindToController(
                        new GatewayRouteController(new GatewayRouteAppService(realStore, events)))
                .controllerAdvice(new GlobalExceptionHandler())
                .build();

        client.put().uri("/api/gateway/routes/order-01")
                .bodyValue(body("order-01", "n", "http://h:8080", null,
                        List.of(rule("PATH_PREFIX", null, "/a/", 1)), List.of()))
                .exchange().expectBody()
                .jsonPath("$.code").isEqualTo(1)
                .jsonPath("$.msg").value(v -> org.assertj.core.api.Assertions.assertThat(v.toString())
                        .contains("必须带上读取时拿到的版本号"));
    }

    @Test
    void detail_missing_returns404() {
        when(store.findByRouteNo("ghost")).thenReturn(Mono.empty());
        web.get().uri("/api/gateway/routes/ghost").exchange().expectBody()
                .jsonPath("$.code").isEqualTo(404)
                .jsonPath("$.msg").isEqualTo("路由不存在：ghost");
    }

    @Test
    void delete_missing_returns404_notSilentSuccess() {
        when(store.delete(any(), any())).thenReturn(Mono.error(
                new BizException(404, "路由不存在，删除未执行：ghost")));
        web.delete().uri("/api/gateway/routes/ghost").exchange().expectBody()
                .jsonPath("$.code").isEqualTo(404)
                .jsonPath("$.msg").value(v -> org.assertj.core.api.Assertions.assertThat(v.toString())
                        .contains("删除未执行"));
    }

    @Test
    void page_realPagingMath_capSize_keywordAndCounts() {
        List<GatewayRoute> all = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            String no = "p-%02d".formatted(i);
            GatewayRoute r = GatewayRoute.create(no, "分页路由" + i, "http://h:8080", 1, null);
            var conds = new ArrayList<GatewayRule>();
            conds.add(GatewayRule.create(null, RuleTypes.TYPE_PATH_PREFIX, null, "/p" + i + "/", 1));
            if (i % 2 == 0) {
                conds.add(GatewayRule.create(null, RuleTypes.TYPE_METHOD, null, "GET", 2));
            }
            r.replaceRules(conds,
                    List.of(GatewayRule.create(null, RuleTypes.TYPE_REQ_REMOVE_HEADER, "X-A", null, 1)));
            all.add(r);
        }
        when(store.findAll()).thenReturn(Flux.fromIterable(all));

        // pageSize 超出上限：真实 AppService 把它压到 200
        web.get().uri("/api/gateway/routes?pageNum=1&pageSize=99999").exchange().expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.pageNum").isEqualTo(1)
                .jsonPath("$.data.pageSize").isEqualTo(200)
                .jsonPath("$.data.total").isEqualTo(6)
                .jsonPath("$.data.totalPages").isEqualTo(1)
                .jsonPath("$.data.content.length()").isEqualTo(6)
                .jsonPath("$.data.content[0].conditionCount").isEqualTo(1)
                .jsonPath("$.data.content[0].actionCount").isEqualTo(1);

        // 每页 5 条：第一页 5 条、共 2 页；偶数编号的条件数是 2
        web.get().uri("/api/gateway/routes?pageNum=1&pageSize=5").exchange().expectBody()
                .jsonPath("$.data.total").isEqualTo(6)
                .jsonPath("$.data.totalPages").isEqualTo(2)
                .jsonPath("$.data.content.length()").isEqualTo(5);
        web.get().uri("/api/gateway/routes?pageNum=2&pageSize=5").exchange().expectBody()
                .jsonPath("$.data.content.length()").isEqualTo(1);

        // 编号 / 名称模糊找
        web.get().uri("/api/gateway/routes?keyword=p-02").exchange().expectBody()
                .jsonPath("$.data.total").isEqualTo(1);
        web.get().uri("/api/gateway/routes?keyword=分页").exchange().expectBody()
                .jsonPath("$.data.total").isEqualTo(6);
    }
}
