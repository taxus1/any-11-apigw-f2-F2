package com.apigw.proxy.auth.user;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 用户登录鉴权的可调参数（前缀 {@code apigw.user-auth}）。
 *
 * @param enabled          是否开启路由级登录鉴权。默认关：与「没做这功能时」完全一致，
 *                         本地无配置也能起网关；生产置 {@code USER_AUTH_ENABLED=true}。
 *                         开启时整套（令牌过滤器、CORS、身份头透传）才装配。
 * @param tokenSecret      令牌验签密钥（HS256），<strong>必须走配置注入，绝不硬编码在仓库</strong>。
 *                         推荐 32 字节以上的高熵随机串；开启开关但缺失/过短直接 fail-fast 启动失败，
 *                         绝不退回一个内置默认密钥（那等于公开伪造能力）。
 * @param stampSecret      网关戳（X-Gateway-Stamp）的 HMAC 密钥，与上游共享验真。
 *                         留空时复用 {@link #tokenSecret}——单服务部署够用；
 *                         多上游、想分开授权时可单独配，同样不硬编码。
 * @param corsAllowedOrigins 跨域允许的来源。默认放通任意来源（{@code *}）；
 *                         生产建议配成明确的前端站点清单。{@code *} 下不回 Allow-Credentials。
 * @param corsAllowedMethods 预检放通的方法；默认 GET/POST/PUT/DELETE/PATCH/OPTIONS/HEAD。
 */
@ConfigurationProperties(prefix = "apigw.user-auth")
public record UserAuthProperties(boolean enabled,
                                 String tokenSecret,
                                 String stampSecret,
                                 List<String> corsAllowedOrigins,
                                 List<String> corsAllowedMethods) {

    /** 令牌密钥最小长度（字节）：低于它说明熵不够，拒绝启动，提醒运维换成长随机串。 */
    public static final int MIN_SECRET_LENGTH = 32;

    public UserAuthProperties {
        if (corsAllowedOrigins == null || corsAllowedOrigins.isEmpty()) {
            corsAllowedOrigins = List.of("*");
        } else {
            corsAllowedOrigins = List.copyOf(corsAllowedOrigins);
        }
        if (corsAllowedMethods == null || corsAllowedMethods.isEmpty()) {
            corsAllowedMethods = List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD");
        } else {
            corsAllowedMethods = List.copyOf(corsAllowedMethods);
        }
        if (enabled) {
            validateSecret(tokenSecret, "token-secret");
        }
    }

    static void validateSecret(String secret, String name) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "已开启 apigw.user-auth，但未配置 apigw.user-auth." + name
                            + "：密钥必须经环境变量/配置中心注入，禁止内置默认值");
        }
        if (secret.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "apigw.user-auth." + name + " 长度不足：至少 " + MIN_SECRET_LENGTH
                            + " 字节（建议用 32 字节以上的高熵随机串）");
        }
    }

    /** 真正用于盖戳的密钥：单独配了用单独的，没配复用令牌密钥。 */
    public String effectiveStampSecret() {
        return (stampSecret == null || stampSecret.isBlank()) ? tokenSecret : stampSecret;
    }
}
