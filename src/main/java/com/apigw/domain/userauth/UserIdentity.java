package com.apigw.domain.userauth;

import java.util.regex.Pattern;

/**
 * 网关验签后认定的调用方身份：用户标识 + 租户标识。
 *
 * 这两个值来自我们自签的令牌、随后要写进发往上游的 HTTP 头（X-User-Id / X-Tenant-Id），
 * 所以在这里就做白名单校验：只放行短的可见字符，杜绝任何能变成头注入（CR/LF）、
 * 或把报文头撑出垃圾内容的值。即便令牌签名是真的（签发侧 bug / 被滥用），
 * 不合规的身份值也进不了报文。
 */
public record UserIdentity(String userId, String tenantId) {

    /** 身份值白名单：字母数字与点/下划线/短横/冒号/@（覆盖编号、UUID、邮箱形态），1..128。 */
    private static final Pattern CLAIM_VALUE = Pattern.compile("[A-Za-z0-9._:@-]{1,128}");

    public UserIdentity {
        if (!isValidClaimValue(userId) || !isValidClaimValue(tenantId)) {
            throw new IllegalArgumentException("用户标识或租户标识不合法");
        }
    }

    /** 合法才认，否则 false（校验侧据此判 CLAIM_INVALID，绝不把脏值写进上游报文头）。 */
    public static boolean isValidClaimValue(String value) {
        return value != null && CLAIM_VALUE.matcher(value).matches();
    }
}
