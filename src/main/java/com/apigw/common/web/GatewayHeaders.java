package com.apigw.common.web;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 网关注入/识别的请求头约定，全项目统一，别散落字面量。
 *
 * - {@code X-Trace-Id}（**入站**）：调用方带来的追踪号。带了且格式合法就沿用，
 *   跨服务排查能直接串起来；没带/非法由网关生成一个 32 位十六进制 UUID 串。
 *   同一个号还会经响应头 {@code X-Gateway-Trace-Id} 回给调用方。
 * - {@code X-App-No}（**入站**）：调进来的应用编号。带了且格式合法就入账，认不出来留空，
 *   绝不能把伪造垃圾写进流水。开启接入鉴权时，它和 {@code X-App-Secret} 合起来是调用方的凭据。
 * - {@code X-App-Secret}（**入站**）：调用方持有的密钥明文，仅用于当次校验，
 *   不记录、不回显、不落库（库里只有它的不可逆散列）。
 *
 * 用户登录鉴权（{@code apigw.user-auth}）这一组头分两类，来源必须分得清清楚楚：
 * - {@code Authorization}（**入站**）：调用方带来的登录令牌（{@code Bearer <jwt>}）。
 *   令牌只用于网关当次验签，验完即弃，**绝不原样转发给上游**；
 * - {@code X-Auth-User} / {@code X-Auth-Tenant}（**出站到上游**）：网关按验签结果写入的
 *   用户标识 / 租户标识。它们是「网关说了算」的可信头，入站时凡同名头一律先剥掉，
 *   调用方自己塞的一律不认；
 * - {@code X-Gateway-Stamp}（**出站到上游**）：网关给每笔请求盖的「确实过了网关」的戳，
 *   上游凭共享密钥可验真伪；入站同名头同样先剥，调用方伪造不出来。
 *
 * 两个入站头都做白名单校验，原因有二：一是这些值要落库、要进日志，不能放任任意长串/换行注入；
 * 二是追踪号会回写到响应头，非法字符可能变成响应拆分载体。
 */
public final class GatewayHeaders {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String APP_NO_HEADER = "X-App-No";
    /** 应用密钥头：只用于网关当次比对散列，绝不写日志/落库。 */
    public static final String APP_SECRET_HEADER = "X-App-Secret";

    /** 入站登录令牌头：只认 {@code Bearer <jwt>}；验完即弃，绝不转发给上游。 */
    public static final String AUTHORIZATION_HEADER = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";

    /** 出站可信身份头：用户标识（网关按验签结果写入，入站同名头先剥）。 */
    public static final String AUTH_USER_HEADER = "X-Auth-User";
    /** 出站可信身份头：租户标识（网关按验签结果写入，入站同名头先剥）。 */
    public static final String AUTH_TENANT_HEADER = "X-Auth-Tenant";
    /** 出站网关戳：每笔请求由网关盖，上游可凭共享密钥验真（入站同名头先剥）。 */
    public static final String GATEWAY_STAMP_HEADER = "X-Gateway-Stamp";

    /**
     * 「网关说了算」的出站头全集：请求进来时无论命中哪条路由都先剥光，
     * 再由网关按自己的验签/盖章结果写回。加新的可信头必须登记到这里，否则剥不干净。
     */
    public static final List<String> GATEWAY_MANAGED_HEADERS = List.of(
            AUTH_USER_HEADER, AUTH_TENANT_HEADER, GATEWAY_STAMP_HEADER);

    /** 追踪号：字母数字与 . _ -，长度 8..64（覆盖常见 trace/span 号与 UUID）。 */
    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    /** 应用编号：字母数字与 . _ -，长度 1..64（与路由编号一套字符集）。 */
    private static final Pattern APP_NO_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /**
     * 身份值（用户标识/租户标识）允许的字符集：令牌里解出来的值要写进请求头转发上游，
     * 必须挡住 CR/LF、空白与非 ASCII——既防响应/请求头拆分，也不让伪造值污染上游日志。
     * 长度封顶 256，足够容纳常规账号/租户编码。
     */
    private static final Pattern IDENTITY_VALUE_PATTERN = Pattern.compile("[A-Za-z0-9._@=-]{1,256}");

    private GatewayHeaders() {
    }

    /** 合法才认，否则 null（调用方据此决定是否自己生成）。 */
    public static String normalizeTraceId(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        return TRACE_ID_PATTERN.matcher(t).matches() ? t : null;
    }

    /** 合法才认，否则 null（流水里 app_no 留空）。 */
    public static String normalizeAppNo(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        return APP_NO_PATTERN.matcher(t).matches() ? t : null;
    }

    /** 身份值（用户/租户）只有通过白名单才允许写进出站头；非法值返回 null。 */
    public static String normalizeIdentityValue(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        return IDENTITY_VALUE_PATTERN.matcher(t).matches() ? t : null;
    }

    /** 网关自己生成的请求编号：32 位十六进制（去横线的 UUID），与既有响应头口径一致。 */
    public static String newRequestId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
