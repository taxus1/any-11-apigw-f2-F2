package com.apigw.support;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 测试用的令牌铸造器：按 JWT compact 形式拼 header.payload.signature。
 * 既能造「真」令牌（HS256 正确签名），也能按需造各种假（错密钥、错算法、坏段、
 * 塞「不过期」字样），用来验证网关的验签不是拆开看字段就算数。
 */
public final class JwtMinter {

    private JwtMinter() {
    }

    /** 标准 HS256 头 + 给定载荷 + 用给定密钥签名。 */
    public static String mintHs256(String secret, String payloadJson) {
        return mint(secret, "{\"alg\":\"HS256\",\"typ\":\"JWT\"}", payloadJson);
    }

    /** 自定义 header 的完整铸造（header/payload 都是 JSON 原文）。 */
    public static String mint(String secret, String headerJson, String payloadJson) {
        String h = b64url(headerJson.getBytes(StandardCharsets.UTF_8));
        String p = b64url(payloadJson.getBytes(StandardCharsets.UTF_8));
        String sig = b64url(hmac(secret, h + "." + p));
        return h + "." + p + "." + sig;
    }

    /** 只拼段不签名（签名段原样给定）——造「载荷被改、签名照旧」这类假令牌用。 */
    public static String assemble(String headerB64, String payloadB64, String signatureB64) {
        return headerB64 + "." + payloadB64 + "." + signatureB64;
    }

    public static String b64url(String json) {
        return b64url(json.getBytes(StandardCharsets.UTF_8));
    }

    public static String b64url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static byte[] hmac(String secret, String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
