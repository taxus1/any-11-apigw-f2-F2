package com.apigw.infrastructure.store;

import com.apigw.domain.route.GatewayRoute;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 路由 JSON 序列化/反序列化测试（不碰 Redis：serialize/deserialize 是纯映射）。
 * 重点：登录开关 authRequired 跟路由配置一起存取；旧配置 JSON 没这个字段时按开放（0）落。
 */
class RouteStoreSerializationTest {

    private final RouteStore store = new RouteStore(null, new ObjectMapper());

    @Test
    void authRequired_roundTripsThroughJson() {
        GatewayRoute r = GatewayRoute.create("secure-01", "需登录路由", "http://svc:8080", 1, null);
        r.changeAuthRequired(1);
        String json = store.serialize(r);
        assertThat(json).contains("\"authRequired\":1");

        GatewayRoute back = store.deserialize(json);
        assertThat(back.getAuthRequired()).isEqualTo(1);
        assertThat(back.requiresAuth()).isTrue();
    }

    @Test
    void legacyJsonWithoutAuthRequired_defaultsToOpen() {
        // 开关上线前写进 Redis 的旧配置：字段缺失 → 默认开放，不能突然变「需登录」
        String legacy = "{\"id\":\"id-1\",\"routeNo\":\"legacy\",\"name\":\"旧路由\","
                + "\"upstream\":\"http://svc:8080\",\"enabled\":1,\"version\":0,"
                + "\"conditions\":[],\"actions\":[]}";
        GatewayRoute back = store.deserialize(legacy);
        assertThat(back.getAuthRequired()).isEqualTo(0);
        assertThat(back.requiresAuth()).isFalse();
    }
}
