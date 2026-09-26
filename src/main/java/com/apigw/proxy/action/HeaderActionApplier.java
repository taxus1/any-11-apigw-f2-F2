package com.apigw.proxy.action;

import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import com.apigw.domain.route.RuleTypes;
import org.springframework.http.HttpHeaders;

import java.util.ArrayList;
import java.util.List;

/**
 * 转发动作执行器：把一条路由上挂的「补头/删头」动作真正落到报文头上。
 *
 * 两条铁律：
 * 1. 方向隔离——请求类动作（REQ_*）只作用于发给上游的请求头，
 *    响应类动作（RESP_*）只作用于回给调用方的响应头，绝不串方向；
 * 2. 同方向内严格按配置的顺序号执行：先删后补和先补后删结果相反，顺序号就是合同。
 *
 * 补头是「覆盖」语义：调用方自己在请求里塞了同名头，以网关配置为准，
 * 先清掉旧值再放配置值（HTTP 头名大小写不敏感，HttpHeaders 自身会归一化处理）。
 * 删头就是删掉，删掉之后上游/调用方都不可能再收到它。
 */
public final class HeaderActionApplier {

    private HeaderActionApplier() {
    }

    /** 执行请求方向动作（REQ_ADD_HEADER / REQ_REMOVE_HEADER），直接改写传入的可变 headers。 */
    public static void applyRequestActions(GatewayRoute route, HttpHeaders headers) {
        for (GatewayRule action : requestActionsInOrder(route)) {
            apply(action, headers);
        }
    }

    /** 执行响应方向动作（RESP_ADD_HEADER / RESP_REMOVE_HEADER），直接改写传入的可变 headers。 */
    public static void applyResponseActions(GatewayRoute route, HttpHeaders headers) {
        for (GatewayRule action : responseActionsInOrder(route)) {
            apply(action, headers);
        }
    }

    /** 该路由的请求方向动作，按顺序号排好（顺序号在配置保存时已校验连续不重）。 */
    public static List<GatewayRule> requestActionsInOrder(GatewayRoute route) {
        return inOrder(route, RuleTypes.TYPE_REQ_ADD_HEADER, RuleTypes.TYPE_REQ_REMOVE_HEADER);
    }

    /** 该路由的响应方向动作，按顺序号排好。 */
    public static List<GatewayRule> responseActionsInOrder(GatewayRoute route) {
        return inOrder(route, RuleTypes.TYPE_RESP_ADD_HEADER, RuleTypes.TYPE_RESP_REMOVE_HEADER);
    }

    private static List<GatewayRule> inOrder(GatewayRoute route, String type1, String type2) {
        List<GatewayRule> picked = new ArrayList<>();
        for (GatewayRule a : route.getActions()) {
            if (type1.equals(a.getType()) || type2.equals(a.getType())) {
                picked.add(a);
            }
        }
        picked.sort(java.util.Comparator.comparing(GatewayRule::getSortNo));
        return picked;
    }

    private static void apply(GatewayRule action, HttpHeaders headers) {
        String type = action.getType();
        String name = action.getName();
        if (RuleTypes.TYPE_REQ_ADD_HEADER.equals(type) || RuleTypes.TYPE_RESP_ADD_HEADER.equals(type)) {
            // 覆盖语义：同名旧值（哪怕是调用方自己塞的）一律清掉，配置说了算
            headers.set(name, action.getValue());
        } else {
            headers.remove(name);
        }
    }
}
