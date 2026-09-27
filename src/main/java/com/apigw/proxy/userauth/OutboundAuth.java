package com.apigw.proxy.userauth;

import com.apigw.domain.userauth.UserIdentity;

/**
 * 转发器写向上游的身份上下文：这一笔请求「网关认定」的身份与通行标记。
 *
 * 由 {@link UserAuthGatekeeper} 在验签之后组装，转发器只负责照写：
 * - identity 非空 → 写 X-User-Id / X-Tenant-Id；为空 → 上游一个身份头都看不到（匿名）；
 * - gatewayPass 非空 → 写 X-Gateway-Pass；
 * - stripAuthorization 为真（用户鉴权已启用）→ 入站的 Authorization 头不原样递上游，
 *   原始令牌只在「调用方↔网关」这一段有意义。
 */
public record OutboundAuth(UserIdentity identity,
                           String gatewayPass,
                           boolean stripAuthorization) {

    /** 用户鉴权整体未启用时的空上下文：不写身份、不盖标记、不动 Authorization。 */
    public static OutboundAuth none() {
        return new OutboundAuth(null, null, false);
    }
}
