package com.apigw.proxy.auth.user;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/**
 * 网关给每笔转发请求盖的「确实过了网关」戳，写在 {@code X-Gateway-Stamp} 头里。
 * 密钥只在网关与上游之间共享（走配置，不进仓库），调用方拿不到，所以伪造不出来；
 * 上游用同一把密钥、同一套规则重算一遍即可验真。
 *
 * 头值格式（v1）：
 * <pre>
 * v1.&lt;unixEpochSeconds&gt;.&lt;base64url(HMAC-SHA256(canonicalString))&gt;
 * </pre>
 *
 * 待签名的 canonicalString 各字段以 {@code \n} 分隔，字段值先做长度受限的白名单/编码处理：
 * <pre>
 * v1
 * &lt;timestamp&gt;
 * &lt;METHOD 大写&gt;
 * &lt;应用内路径（不编码，原样）&gt;
 * &lt;原始 query（URLEncoder 编码，无则空串）&gt;
 * &lt;X-Gateway-Trace-Id&gt;
 * &lt;用户标识（白名单值；开放路由的匿名请求为空串）&gt;
 * &lt;租户标识（白名单值；开放路由的匿名请求为空串）&gt;
 * </pre>
 *
 * 为什么把方法/路径/query/traceId/身份都签进去：
 * - 只签一个「我是网关」的固定串，等于一枚永久有效、任何请求都能贴的图章，
 *   被内部网络上的人截到就能冒用；把请求要素与时间戳签进去，戳与「这一笔请求」绑定；
 * - 上游验签时同时确认身份头与戳内一致，调用方即便侥幸碰到一枚真戳也挪不到别的请求上；
 * - 开放路由的匿名请求一样盖章（身份字段为空串），上游不需要区分「匿名戳/登录戳」，
 *   只要戳验得过就说明请求确实经网关清洗过。
 *
 * 本类只负责算戳，无状态、线程安全；时钟可注入（测过期边界用）。
 */
public final class GatewayStampSigner {

    public static final String VERSION = "v1";

    private final byte[] secret;
    private final Clock clock;

    public GatewayStampSigner(String stampSecret, Clock clock) {
        this.secret = stampSecret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    /**
     * 盖戳。
     *
     * @param method   HTTP 方法（内部统一成大写）
     * @param path     应用内路径，如 {@code /order/1}
     * @param rawQuery 原始查询串（{@code ?} 后面那段，不带 {@code ?}），无则 null/空
     * @param traceId  网关本次请求的 traceId
     * @param userId   验签得到的用户标识；开放路由匿名请求传 null
     * @param tenantId 验签得到的租户标识；开放路由匿名请求传 null
     */
    public String stamp(String method, String path, String rawQuery, String traceId,
                        String userId, String tenantId) {
        long timestamp = Instant.now(clock).getEpochSecond();
        String canonical = String.join("\n",
                VERSION,
                Long.toString(timestamp),
                method == null ? "-" : method.toUpperCase(java.util.Locale.ROOT),
                path == null ? "" : path,
                rawQuery == null ? "" : URLEncoder.encode(rawQuery, StandardCharsets.UTF_8),
                traceId == null ? "" : traceId,
                userId == null ? "" : userId,
                tenantId == null ? "" : tenantId);
        String sig = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(canonical));
        return VERSION + "." + timestamp + "." + sig;
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("网关戳 HMAC 计算失败（网关侧配置问题）", e);
        }
    }
}
