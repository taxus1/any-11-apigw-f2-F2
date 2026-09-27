package com.apigw.interfaces.rest.route.vo;

import java.io.Serializable;
import java.util.List;

/**
 * 保存路由的请求体。
 *
 * 用 record 承载外部输入：字段与题目描述一一对应，不做多余包装。
 * conditions / actions 里每条对应一个 {@link RuleVO}。
 */
public record RouteSaveVO(String routeNo,
                          String name,
                          String upstream,
                          Integer enabled,
                          Integer requireLogin,
                          String remark,
                          Integer version,
                          List<RuleVO> conditions,
                          List<RuleVO> actions) implements Serializable {

    public record RuleVO(String stage,
                         String type,
                         String name,
                         String value,
                         Integer sortNo) implements Serializable {
    }
}
