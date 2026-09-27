package com.apigw.domain.userauth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 「这一笔请求确实过了网关」的通行标记（X-Gateway-Pass）签名器与参考校验器。
 *
 * 为什么要它：上游只想信经过网关验签、清洗过的请求。调用方自己塞一个同名头没有用——
 * 网关转发前会先把入站同名头清掉，再用<b>只存在于网关与上游之间的共享密钥</b>
 * 把这次请求的关键信息盖一个 HMAC 章；上游用同一把密钥重算即可验出真假，
 * 拿不到密钥的调用方伪造不出任何一个能验过的标记。
 *
 * 标记形状：{@code v1.<毫秒时间戳>.<base64url(HMAC-SHA256)}，
 * 被签的规范化串（字段固定、顺序固定、\n 分隔，空身份就是空串）：
 * <pre>
 * v1
 * &lt;时间戳毫秒&gt;
 * &lt;traceId&gt;
 * &lt;方法&gt;
 * &lt;路径&gt;
 * &lt;用户标识&gt;
 * &lt;租户标识&gt;
 * </pre>
 * 标记把 traceId、方法、路径、用户、租户和时间戳绑在一起：换任何一个值签名就废；
 * 时间戳让上游可以把标记的有效期卡在一个小窗口内，旧标记不能被拿去重放。
 *
 * 本类既是网关的盖章实现，也作为上游验签的参考实现（verify 供验方照抄/对照）。
 */
public final class GatewayPassSigner {

    public static final String VERSION = "v1";

    /** 校验时允许的「来自未来」小容忍（各方时钟有毫秒差）；这不是令牌过期宽限。 */
    public static final long FUTURE_SKEW_MILLIS = 60_000;

    private final byte[] secret;

    public GatewayPassSigner(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("通行标记密钥不能为空（请配置 apigw.user-auth.pass-secret）");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** 生成通行标记。 */
    public String sign(long timestampMillis, String traceId, String method, String path,
                       String userId, String tenantId) {
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(
                hmacSha256(canonical(timestampMillis, traceId, method, path, userId, tenantId)));
        return VERSION + "." + timestampMillis + "." + signature;
    }

    /**
     * 上游侧参考校验：版本、时间戳新鲜度、HMAC 全部通过才认。
     *
     * @param headerValue      收到的 X-Gateway-Pass 原值
     * @param nowMillis        当前时间
     * @param maxAgeMillis     标记最长认多久（防重放窗口，例如 5 分钟）
     */
    public boolean verify(String headerValue, String traceId, String method, String path,
                          String userId, String tenantId, long nowMillis, long maxAgeMillis) {
        if (headerValue == null) {
            return false;
        }
        String[] parts = headerValue.split("\\.", -1);
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            return false;
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (timestamp > nowMillis + FUTURE_SKEW_MILLIS || nowMillis - timestamp > maxAgeMillis) {
            return false;
        }
        String expected = sign(timestamp, traceId, method, path, userId, tenantId);
        // 常量时间比对，不泄露签名前缀
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                headerValue.getBytes(StandardCharsets.UTF_8));
    }

    private String canonical(long timestampMillis, String traceId, String method, String path,
                             String userId, String tenantId) {
        return String.join("\n",
                VERSION,
                Long.toString(timestampMillis),
                traceId == null ? "" : traceId,
                method == null ? "" : method,
                path == null ? "" : path,
                userId == null ? "" : userId,
                tenantId == null ? "" : tenantId);
    }

    private byte[] hmacSha256(String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("通行标记 HMAC 计算失败", e);
        }
    }
}
