package com.apigw.common.web;

import org.springframework.http.server.reactive.ServerHttpRequest;

import java.net.InetSocketAddress;

/**
 * 客户端来源地址解析。**全网关唯一口径**：鉴权、限流、访问流水都必须用它，
 * 谁都不许自己再写一套取值顺序——否则同一个请求在各处看到的来源对不上。
 *
 * 约定的取值顺序（前者取到合法值就用前者，否则逐级回退）：
 * <pre>
 *   1. X-Forwarded-For 的第一个地址（最左 = 调用链上最早那一跳；网关部署在可信代理后面，
 *      最左才是最初的客户端，取最右会把代理地址当来源）；
 *   2. X-Real-IP（单层代理常见，直接给一个地址）；
 *   3. 传输层对端地址 remoteAddress（TCP 连接上的对端，永远可信，作为最后兜底）。
 * </pre>
 *
 * 安全口径：头里的值必须「长得像个 IP」才采纳（逐段校验 IPv4/IPv6）。
 * 调用方能任意伪造头，原样采信会让别人用 XFF 冒充来源；非法值直接跳过这一级往后退。
 * XFF 里多跳形如 {@code client, proxy1, proxy2}，只取第一个且不做「可信代理跳数裁剪」
 * （内网约定：网关前面只挂受信接入层；需要剥跳时在这里统一改，口径仍只有这一处）。
 */
public final class ClientIpResolver {

    public static final String XFF_HEADER = "X-Forwarded-For";
    public static final String REAL_IP_HEADER = "X-Real-IP";

    private ClientIpResolver() {
    }

    public static String resolve(ServerHttpRequest request) {
        String fromXff = firstValidForwardedFor(request.getHeaders().getFirst(XFF_HEADER));
        if (fromXff != null) {
            return fromXff;
        }
        String realIp = trimToNull(request.getHeaders().getFirst(REAL_IP_HEADER));
        if (realIp != null && isIpAddress(realIp)) {
            return realIp;
        }
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote != null && remote.getAddress() != null) {
            return remote.getAddress().getHostAddress();
        }
        // 对端地址都拿不到（理论上极少，如 mock/管道请求）：回退主机名字符串，再不行给固定未知值，
        // 流水这一列 NOT NULL，宁可记 unknown 也不能因为取不到地址把整条流水丢了
        if (remote != null && remote.getHostString() != null) {
            return remote.getHostString();
        }
        return "unknown";
    }

    /** XFF 取「第一个合法地址」：最左非法不整串丢弃，继续在后续跳里找，直到 remoteAddress 兜底。 */
    private static String firstValidForwardedFor(String xff) {
        if (xff == null || xff.isBlank()) {
            return null;
        }
        for (String part : xff.split(",")) {
            String candidate = trimToNull(part);
            if (candidate != null && isIpAddress(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * 严格按「IP 字面量」校验（不做 DNS 反解，绝不 accept 主机名，避免头注入/伪造主机名混进来）。
     * 兼容 IPv4、带点分四组的 IPv4、IPv6（含 zone、IPv4 内嵌形式）。
     */
    public static boolean isIpAddress(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        // IPv4：4 组 0..255，不允许前导空白之外的杂字符
        if (isIpv4(value)) {
            return true;
        }
        return isIpv6(value);
    }

    private static boolean isIpv4(String value) {
        String[] groups = value.split("\\.", -1);
        if (groups.length != 4) {
            return false;
        }
        for (String g : groups) {
            if (g.isEmpty() || g.length() > 3 || !allDigits(g)) {
                return false;
            }
            int n = Integer.parseInt(g);
            if (n > 255) {
                return false;
            }
        }
        return true;
    }

    private static boolean allDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * IPv6 粗粒度但安全的字面量校验：必须含 ':'，按 ':' 分组，每组要么为空（:: 压缩位，
     * 全串至多一处压缩）、要么是 1..4 位十六进制；末尾允许内嵌 IPv4；可带一个 zone（% 后）。
     */
    private static boolean isIpv6(String value) {
        if (value.indexOf(':') < 0) {
            return false;
        }
        String v = value;
        int pct = v.indexOf('%');
        if (pct >= 0) {
            // zone 标识后面只要非空即可，不参与地址合法性
            if (pct == v.length() - 1) {
                return false;
            }
            v = v.substring(0, pct);
        }
        if (v.startsWith("[" ) || v.endsWith("]")) {
            // 带方括号的是带端口写法（[::1]:80），头里不该出现，不采信
            return false;
        }

        String head;
        String lastGroup = v.substring(v.lastIndexOf(':') + 1);
        if (lastGroup.indexOf('.') >= 0) {
            // 内嵌 IPv4 尾部，单独校验，前面必须像 IPv6 前缀
            if (!isIpv4(lastGroup)) {
                return false;
            }
            head = v.substring(0, v.lastIndexOf(':'));
        } else {
            head = v;
        }

        boolean hasDoubleColon = head.contains("::");
        if (hasDoubleColon && head.indexOf("::") != head.lastIndexOf("::")) {
            return false; // 多处压缩非法
        }
        String[] groups = head.isEmpty() ? new String[0] : head.split(":", -1);
        int nonEmpty = 0;
        for (String g : groups) {
            if (g.isEmpty()) {
                continue; // 单空组只允许出现在 "::" 处；split 产生的连续空串即压缩位
            }
            if (g.length() > 4 || !isHex(g)) {
                return false;
            }
            nonEmpty++;
        }
        // 没有压缩符时，纯 IPv6 必须 8 组（内嵌 v4 时 6 组前缀）；有压缩时组数必须少于满额
        int fullGroups = lastGroup.indexOf('.') >= 0 ? 6 : 8;
        if (!hasDoubleColon) {
            return nonEmpty == fullGroups;
        }
        return nonEmpty < fullGroups;
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
