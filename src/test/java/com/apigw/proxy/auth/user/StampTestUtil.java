package com.apigw.proxy.auth.user;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 测试用的「上游侧」戳验证器：按 {@link GatewayStampSigner} 文档里的规则独立实现，
 * 证明拿到共享密钥的上游可以自己验真。
 */
public final class StampTestUtil {

    private StampTestUtil() {
    }

    public static boolean verify(String stamp, String secret, String method, String path,
                                 String rawQuery, String traceId, String userId, String tenantId) {
        String[] parts = stamp.split("\\.");
        if (parts.length != 3 || !"v1".equals(parts[0])) {
            return false;
        }
        String canonical = String.join("\n",
                "v1", parts[1], method.toUpperCase(), path,
                rawQuery == null ? "" : URLEncoder.encode(rawQuery, StandardCharsets.UTF_8),
                traceId == null ? "" : traceId,
                userId == null ? "" : userId,
                tenantId == null ? "" : tenantId);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    parts[2].getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
