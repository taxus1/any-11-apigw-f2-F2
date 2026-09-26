package com.apigw.proxy.match;

/**
 * 路径前缀匹配（纯逻辑，不依赖 Servlet/WebFlux）。
 *
 * 边界语义必须踩准，不能含糊：
 * - 规则以斜杠结尾（如 /order/）：请求路径以该串开头才算命中。
 *   /order/ 命中 /order/abc、/order/，但不命中 /order（那是另一个路径段，不是它的「下面」）；
 * - 规则不以斜杠结尾（如 /order）：要么请求路径与它逐字符相等，要么请求路径恰好从「/order/」
 *   这个段边界继续。这样 /order 命中 /order、/order/abc，但 /other、/ordering、/order-x
 *   这些「只是字符串前缀像」的一律不命中。
 *
 * 即「无尾斜杠 = 精确路径 + 子树」，「有尾斜杠 = 仅子树」。
 *
 * 大小写按常规 URL 语义：路径部分大小写敏感（RFC 3986 不做归一化），
 * 所以 /Order 与 /order 是两条路径，不做忽略大小写匹配。
 */
public final class PathPrefixMatcher {

    private PathPrefixMatcher() {
    }

    public static boolean matches(String prefix, String requestPath) {
        if (prefix == null || prefix.isEmpty() || requestPath == null) {
            return false;
        }
        if (prefix.endsWith("/")) {
            // /order/：严格按「前缀串」判，不做段边界放宽，/order 自身不算 /order/ 的子路径
            return requestPath.startsWith(prefix);
        }
        // /order：精确相等，或下一个字符必须是 '/'，挡住 /ordering、/order-x
        if (requestPath.equals(prefix)) {
            return true;
        }
        return requestPath.startsWith(prefix)
                && requestPath.startsWith("/", prefix.length());
    }
}
