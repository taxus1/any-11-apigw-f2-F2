package com.apigw.proxy.userauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 用户登录令牌鉴权的可调参数（前缀 {@code apigw.user-auth}）。
 *
 * 密钥一律走配置/环境变量，绝不硬编码进仓库：
 * - {@code token-secret}：验用户令牌（JWT/HS256）签名的 HMAC 密钥。
 *   不配 = 用户鉴权未启用：开放路由照常转发，但配了「需登录」的路由会 fail-closed 回 503，
 *   绝不因为没配密钥就把受保护路由裸放出去。
 * - {@code pass-secret}：盖通行标记（X-Gateway-Pass）的 HMAC 密钥，与上游共享；
 *   不配时回退用 token-secret（两边仍是同一把，只是少一层隔离），都没有则不盖标记。
 * - {@code issuer}：非空时强制校验令牌的 iss 声明。
 *
 * 过期判定没有任何宽限参数可配：now &gt;= exp 即过期，边界卡死，这是有意为之。
 */
@ConfigurationProperties(prefix = "apigw.user-auth")
public record UserAuthProperties(String tokenSecret,
                                 String passSecret,
                                 String issuer) {

    /** 是否配了验签密钥（= 用户令牌鉴权是否可用）。 */
    public boolean tokenVerificationEnabled() {
        return tokenSecret != null && !tokenSecret.isBlank();
    }

    /** 通行标记实际使用的密钥：专用密钥优先，缺省回退验签密钥。 */
    public String effectivePassSecret() {
        if (passSecret != null && !passSecret.isBlank()) {
            return passSecret;
        }
        return tokenSecret;
    }

    /** 是否具备盖通行标记的条件。 */
    public boolean passSigningEnabled() {
        String s = effectivePassSecret();
        return s != null && !s.isBlank();
    }
}
