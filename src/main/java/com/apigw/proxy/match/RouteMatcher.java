package com.apigw.proxy.match;

import com.apigw.domain.route.GatewayRoute;
import com.apigw.domain.route.GatewayRule;
import com.apigw.domain.route.RuleTypes;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 路由匹配：拿一份「当前启用、可匹配」的路由快照，对一个请求找出唯一命中的路由。
 *
 * 命中规则：同一条路由上的全部匹配条件是 AND，任何一条不满足就不命中；
 * 多条路由同时命中时，按下列确定次序选出唯一一条（同样的请求永远走同一条，不会漂移）：
 *   1. PATH_PREFIX 前缀更长的优先（更具体的路径赢；没写路径条件的按 0 长度排最后）；
 *   2. 仍并列（如路径条件相同、或都没路径条件）时，条件总数更多的赢（约束更具体）；
 *   3. 还并列就按路由编号字典序（routeNo 只含字母数字 . _ -，字典序确定且稳定）。
 *
 * 各条件类型的语义：
 * - PATH_PREFIX：见 {@link PathPrefixMatcher}，按常规 URL 语义路径大小写敏感；
 * - METHOD：HTTP 方法名大小写不敏感（GET 与 get 等价），比较时统一大写；
 * - HEADER：头名大小写不敏感（HTTP 头本来就不区分大小写），头值大小写敏感、精确相等；
 * - QUERY：参数名大小写敏感，参数值大小写敏感、精确相等（只判断该参数是否带着这个值）。
 *
 * 这类不持有状态、不碰 Redis，快照由上层 RouteCatalog 提供，方便直接单测。
 */
@Component
public class RouteMatcher {

    /**
     * 多路由撞配时的稳定次序：前缀长度降序 → 条件数降序 → 编号升序。
     */
    static final Comparator<GatewayRoute> PRECEDENCE = Comparator
            .comparingInt((GatewayRoute r) -> pathPrefixLength(r))
            .reversed()
            .thenComparing(Comparator.comparingInt((GatewayRoute r) -> r.getConditions().size()).reversed())
            .thenComparing(GatewayRoute::getRouteNo);

    /**
     * 返回唯一命中的路由；一条都不命中返回 null（上层据此回 404 NO_ROUTE）。
     */
    public GatewayRoute match(List<GatewayRoute> routes, ServerHttpRequest request) {
        GatewayRoute best = null;
        for (GatewayRoute route : routes) {
            if (!allConditionsMatch(route, request)) {
                continue;
            }
            if (best == null || PRECEDENCE.compare(route, best) < 0) {
                best = route;
            }
        }
        return best;
    }

    /** 一条路由的全部条件 AND。 */
    private boolean allConditionsMatch(GatewayRoute route, ServerHttpRequest request) {
        for (GatewayRule c : route.getConditions()) {
            if (!conditionMatches(c, request)) {
                return false;
            }
        }
        return true;
    }

    private boolean conditionMatches(GatewayRule c, ServerHttpRequest request) {
        switch (c.getType()) {
            case RuleTypes.TYPE_PATH_PREFIX -> {
                return PathPrefixMatcher.matches(c.getValue(), request.getPath().pathWithinApplication().value());
            }
            case RuleTypes.TYPE_METHOD -> {
                // 方法名是大小写不敏感的 token
                return request.getMethod() != null
                        && c.getValue().equalsIgnoreCase(request.getMethod().name());
            }
            case RuleTypes.TYPE_HEADER -> {
                String actual = request.getHeaders().getFirst(c.getName());
                return actual != null && actual.equals(c.getValue());
            }
            case RuleTypes.TYPE_QUERY -> {
                List<String> values = request.getQueryParams().get(c.getName());
                return values != null && values.contains(c.getValue());
            }
            default -> {
                // 条件白名单在配置保存时已守住，这里属于数据异常，保守按不命中处理
                return false;
            }
        }
    }

    /** 取路由上路径前缀条件的前缀长度（多条时取最长）；没有路径条件返回 0。 */
    private static int pathPrefixLength(GatewayRoute route) {
        int len = 0;
        for (GatewayRule c : route.getConditions()) {
            if (RuleTypes.TYPE_PATH_PREFIX.equals(c.getType()) && c.getValue() != null) {
                len = Math.max(len, c.getValue().length());
            }
        }
        return len;
    }

    /** 排序/日志里用得上：把方法名归一化成大写。 */
    public static String normalizeMethod(String method) {
        return method == null ? "" : method.toUpperCase(Locale.ROOT);
    }
}
