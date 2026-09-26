package com.apigw.proxy.action;

import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 头动作执行器测试：
 * - 补头是覆盖语义：调用方/上游自带的同名头必须被配置值顶掉；
 * - 删头就是删掉，多值头也整组消失；
 * - 顺序号决定先后（先删后补 vs 先补后删，结果不同）；
 * - 请求方向动作绝不碰响应头，反之亦然。
 */
class HeaderActionApplierTest {

    private GatewayRoute route(List<GatewayRule> actions) {
        GatewayRoute r = GatewayRoute.create("r", "r", "http://h:1", 1, null);
        r.replaceRules(List.of(), actions);
        return r;
    }

    private GatewayRule reqAdd(String name, String value, int sort) {
        return GatewayRule.create("REQUEST", "REQ_ADD_HEADER", name, value, sort);
    }

    private GatewayRule reqRemove(String name, int sort) {
        return GatewayRule.create("REQUEST", "REQ_REMOVE_HEADER", name, null, sort);
    }

    private GatewayRule respAdd(String name, String value, int sort) {
        return GatewayRule.create("RESPONSE", "RESP_ADD_HEADER", name, value, sort);
    }

    private GatewayRule respRemove(String name, int sort) {
        return GatewayRule.create("RESPONSE", "RESP_REMOVE_HEADER", name, null, sort);
    }

    @Test
    void addHeader_overwritesCallersSameNamedHeader() {
        // 调用方自己塞了同名头，必须被网关配置覆盖，不能让他做主
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Gw", "from-caller");
        HeaderActionApplier.applyRequestActions(route(List.of(reqAdd("X-Gw", "from-gw", 1))), headers);
        assertEquals("from-gw", headers.getFirst("X-Gw"));
        assertEquals(1, headers.get("X-Gw").size(), "覆盖后只能有一个值，不能追加成双值");
    }

    @Test
    void addHeader_isCaseInsensitiveOnName() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("x-gw", "old");
        HeaderActionApplier.applyRequestActions(route(List.of(reqAdd("X-GW", "new", 1))), headers);
        assertEquals("new", headers.getFirst("X-Gw"));
        assertEquals(1, headers.size());
    }

    @Test
    void removeHeader_dropsItEntirely() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Internal", "a");
        headers.add("X-Internal", "b");
        HeaderActionApplier.applyRequestActions(route(List.of(reqRemove("X-Internal", 1))), headers);
        assertNull(headers.getFirst("X-Internal"));
        assertFalse(headers.containsKey("X-Internal"));
    }

    @Test
    void actionsExecuteInSortNoOrder_removeThenAddKeepsIt() {
        // 顺序号 1 先删、顺序号 2 再补：最后留得下配置值
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-K", "original");
        HeaderActionApplier.applyRequestActions(route(List.of(
                reqRemove("X-K", 1),
                reqAdd("X-K", "added-after-remove", 2))), headers);
        assertEquals("added-after-remove", headers.getFirst("X-K"));
    }

    @Test
    void actionsExecuteInSortNoOrder_addThenRemoveDropsIt() {
        // 反过来：先补后删，删是最后动作，头必须不存在（证明真按顺序号来）
        HttpHeaders headers = new HttpHeaders();
        HeaderActionApplier.applyRequestActions(route(List.of(
                reqAdd("X-K", "v", 1),
                reqRemove("X-K", 2))), headers);
        assertFalse(headers.containsKey("X-K"));
    }

    @Test
    void requestActionsNeverTouchResponseDirection_andViceVersa() {
        GatewayRoute r = route(List.of(
                reqAdd("X-Req", "1", 1),
                respAdd("X-Resp", "2", 2)));

        HttpHeaders requestHeaders = new HttpHeaders();
        HeaderActionApplier.applyRequestActions(r, requestHeaders);
        assertEquals("1", requestHeaders.getFirst("X-Req"));
        assertFalse(requestHeaders.containsKey("X-Resp"), "响应动作不能作用到请求上");

        HttpHeaders responseHeaders = new HttpHeaders();
        HeaderActionApplier.applyResponseActions(r, responseHeaders);
        assertEquals("2", responseHeaders.getFirst("X-Resp"));
        assertFalse(responseHeaders.containsKey("X-Req"), "请求动作不能作用到响应上");
    }

    @Test
    void responseRemove_dropsUpstreamHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Debug", "leak");
        HeaderActionApplier.applyResponseActions(route(List.of(respRemove("X-Debug", 1))), headers);
        assertFalse(headers.containsKey("X-Debug"));
    }

    private static void assertFalse(boolean condition, String message) {
        org.junit.jupiter.api.Assertions.assertFalse(condition, message);
    }

    private static void assertFalse(boolean condition) {
        org.junit.jupiter.api.Assertions.assertFalse(condition);
    }
}
