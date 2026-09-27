package com.apigw.infrastructure.store;

import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 路由序列化往返（不依赖 Redis）：requireLogin 标记必须跟着路由配置一起存取；
 * 旧数据 JSON 里没有该字段时，反序列化按「开放（0）」处理，向后兼容。
 */
class RouteSerializationTest {

    private final RouteStore store = new RouteStore(null, new ObjectMapper());

    private GatewayRoute route(Integer requireLogin) {
        GatewayRoute r = GatewayRoute.create("r-1", "路由", "http://svc:8080", 1, requireLogin, null);
        r.setId("id-1");
        r.replaceRules(
                List.of(GatewayRule.create("REQUEST", "PATH_PREFIX", null, "/x/", 1)),
                List.of());
        return r;
    }

    @Test
    void requireLogin_roundTrips_bothValues() {
        assertThat(store.deserialize(store.serialize(route(1))).isLoginRequired()).isTrue();
        GatewayRoute open = store.deserialize(store.serialize(route(0)));
        assertThat(open.isLoginRequired()).isFalse();
        // 其余字段不丢
        assertThat(open.getRouteNo()).isEqualTo("r-1");
        assertThat(open.getConditions()).hasSize(1);
    }

    @Test
    void legacyJsonWithoutRequireLogin_defaultsToOpen() {
        // 模拟历史数据：没有 requireLogin 字段
        String legacy = "{\"id\":\"id-1\",\"routeNo\":\"r-1\",\"name\":\"路由\","
                + "\"upstream\":\"http://svc:8080\",\"enabled\":1,\"version\":0,"
                + "\"conditions\":[{\"stage\":\"REQUEST\",\"type\":\"PATH_PREFIX\","
                + "\"name\":null,\"value\":\"/x/\",\"sortNo\":1}],\"actions\":[]}";
        GatewayRoute r = store.deserialize(legacy);
        assertThat(r.isLoginRequired()).isFalse();
        assertThat(r.getRequireLogin()).isEqualTo(0);
    }
}
