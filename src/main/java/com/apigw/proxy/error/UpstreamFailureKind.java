package com.apigw.proxy.error;

import io.netty.handler.timeout.ReadTimeoutException;
import org.springframework.http.HttpStatus;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * 网关错误的分类。转发故障与接入鉴权各占一组，给调用方「说得清」且彼此区分的答复：
 *
 * 接入鉴权（请求根本没资格进转发）：
 * - {@link #APP_UNAUTHENTICATED} 凭据缺失/编号不存在/密钥不对/密钥过期 → 401
 * - {@link #APP_FORBIDDEN}      凭据有效但应用已停用，或来源地址不在名单 → 403
 * - {@link #APP_CONFIG_UNAVAILABLE} 鉴权配置此刻读不出来（库故障且无旧快照，fail-closed）→ 503
 *
 * - {@link #NO_ROUTE}       网关这边没找到路由，根本没往上游打        → 404
 * - {@link #UPSTREAM_UNAVAILABLE}  上游连不上（拒接、不可达、TLS 失败） → 502
 * - {@link #UPSTREAM_TIMEOUT}      上游半天不吭声（连接/读取超时）      → 504
 * - {@link #CONFIG_UNAVAILABLE}    网关自己的路由配置此刻读不出来（Redis 挂了且无旧快照）→ 503
 *
 * 502 和 504 故意分开：连不上是「上游没在/地址错」，超时是「上游在但太慢/卡死」，
 * 前端和值班同学看到的处置动作完全不同，绝不能回成同一种错误。
 */
public enum UpstreamFailureKind {

    APP_UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "APP_UNAUTHENTICATED",
            "应用凭据缺失或无效：请使用网关签发的应用编号与密钥（X-App-No / X-App-Secret）"),
    APP_FORBIDDEN(HttpStatus.FORBIDDEN, "APP_FORBIDDEN",
            "应用已停用或来源地址不在来路名单内，网关拒绝本次调用"),
    APP_CONFIG_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "APP_CONFIG_UNAVAILABLE",
            "接入鉴权配置暂时不可用，请稍后重试"),
    /** 登录令牌：没带、签名不对、过期（含正好压在过期时刻）、声明不全/不合法，对外一律同一种 401。 */
    TOKEN_UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "TOKEN_UNAUTHENTICATED",
            "登录缺失或已失效：请在 Authorization 头携带网关签发的有效 Bearer 令牌"),
    /** 路由快照读不出来、无法判断这条路由要不要登录：fail-closed 回 503，绝不猜成开放。 */
    TOKEN_CONFIG_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "TOKEN_CONFIG_UNAVAILABLE",
            "网关鉴权配置暂时不可用，请稍后重试"),
    NO_ROUTE(HttpStatus.NOT_FOUND, "NO_ROUTE",
            "网关未匹配到路由：该请求没有对应的转发规则"),
    UPSTREAM_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "UPSTREAM_UNAVAILABLE",
            "上游服务暂时不可达（连接失败），网关未能完成转发"),
    UPSTREAM_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "UPSTREAM_TIMEOUT",
            "上游服务响应超时，网关未在规定时间内拿到响应"),
    CONFIG_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "CONFIG_UNAVAILABLE",
            "网关路由配置暂时不可用，请稍后重试"),
    UPSTREAM_PROTOCOL_ERROR(HttpStatus.BAD_GATEWAY, "UPSTREAM_PROTOCOL_ERROR",
            "上游返回了无法处理的响应，网关未能完成转发");

    private final HttpStatus httpStatus;
    private final String errorCode;
    private final String message;

    UpstreamFailureKind(HttpStatus httpStatus, String errorCode, String message) {
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.message = message;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public int statusCode() {
        return httpStatus.value();
    }

    /** 机器可读的错误码，回在响应头 X-Gateway-Error 与响应体 error 字段里。 */
    public String errorCode() {
        return errorCode;
    }

    /** 给调用方看的「说得清」的话；不含任何内部堆栈或上游原始错误页。 */
    public String message() {
        return message;
    }

    /**
     * 把转发过程中抛出的异常归到一类。Reactor Netty/WebClient 会在异常外再包几层
     * （WebClientRequestException、Exceptions.propagate 等），所以沿 cause 链找。
     */
    public static UpstreamFailureKind classify(Throwable error) {
        java.util.Set<Throwable> seen = new java.util.HashSet<>();
        for (Throwable t = error; t != null && seen.add(t); t = t.getCause()) {
            // 超时类放最前：ReadTimeoutException 本身也是 IOException 的子类，必须先判
            if (t instanceof ReadTimeoutException
                    || t instanceof TimeoutException
                    || t instanceof SocketTimeoutException) {
                return UPSTREAM_TIMEOUT;
            }
            if (t instanceof ConnectException
                    || t instanceof NoRouteToHostException
                    || t instanceof SSLException
                    || t instanceof SSLHandshakeException) {
                return UPSTREAM_UNAVAILABLE;
            }
            if (t instanceof IOException) {
                // 连接被重置、对端断连等其余 IO 故障，统一按上游不可用
                return UPSTREAM_UNAVAILABLE;
            }
        }
        // 拿不准的故障也不能把原始异常抛给调用方，按 502 兜底
        return UPSTREAM_PROTOCOL_ERROR;
    }
}
