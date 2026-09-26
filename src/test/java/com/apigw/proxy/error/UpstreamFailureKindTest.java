package com.apigw.proxy.error;

import io.netty.handler.timeout.ReadTimeoutException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 上游故障归类测试：连不上（502）和半天不吭声（504）必须区分开，
 * 且要能穿透 WebClient/Reactor 的包装层沿 cause 链找到根因。
 */
class UpstreamFailureKindTest {

    @Test
    void connectRefused_isUpstreamUnavailable_502() {
        assertEquals(UpstreamFailureKind.UPSTREAM_UNAVAILABLE,
                UpstreamFailureKind.classify(new ConnectException("Connection refused")));
        assertEquals(502, UpstreamFailureKind.UPSTREAM_UNAVAILABLE.statusCode());
    }

    @Test
    void noRouteToHost_isUpstreamUnavailable_502() {
        assertEquals(UpstreamFailureKind.UPSTREAM_UNAVAILABLE,
                UpstreamFailureKind.classify(new NoRouteToHostException("no route")));
    }

    @Test
    void readTimeout_isUpstreamTimeout_504() {
        assertEquals(UpstreamFailureKind.UPSTREAM_TIMEOUT,
                UpstreamFailureKind.classify(ReadTimeoutException.INSTANCE));
        assertEquals(UpstreamFailureKind.UPSTREAM_TIMEOUT,
                UpstreamFailureKind.classify(new TimeoutException("timeout")));
        assertEquals(UpstreamFailureKind.UPSTREAM_TIMEOUT,
                UpstreamFailureKind.classify(new SocketTimeoutException("slow")));
        assertEquals(504, UpstreamFailureKind.UPSTREAM_TIMEOUT.statusCode());
    }

    @Test
    void wrappedByWebClient_stillClassifiedByRootCause() {
        // 模拟 WebClient/Reactor 在根因外再包一层（常见为 RuntimeException 包装），分类必须穿透
        assertEquals(UpstreamFailureKind.UPSTREAM_UNAVAILABLE,
                UpstreamFailureKind.classify(
                        new RuntimeException(new ConnectException("refused"))));
        assertEquals(UpstreamFailureKind.UPSTREAM_TIMEOUT,
                UpstreamFailureKind.classify(
                        new RuntimeException(ReadTimeoutException.INSTANCE)));
        // 多包几层也要能找到
        assertEquals(UpstreamFailureKind.UPSTREAM_UNAVAILABLE,
                UpstreamFailureKind.classify(
                        new RuntimeException(new RuntimeException(new ConnectException("refused")))));
    }

    @Test
    void timeoutCheckedBeforeIoBecauseReadTimeoutIsIoException() {
        // ReadTimeoutException 本身也是 IOException 子类，顺序上超时必须先判中
        Throwable timeout = ReadTimeoutException.INSTANCE;
        assertEquals(UpstreamFailureKind.UPSTREAM_TIMEOUT, UpstreamFailureKind.classify(timeout));
    }

    @Test
    void otherIoError_isUnavailable_unknownError_fallsBackTo502() {
        assertEquals(UpstreamFailureKind.UPSTREAM_UNAVAILABLE,
                UpstreamFailureKind.classify(new IOException("connection reset")));
        assertEquals(UpstreamFailureKind.UPSTREAM_PROTOCOL_ERROR,
                UpstreamFailureKind.classify(new IllegalStateException("weird")));
        assertEquals(502, UpstreamFailureKind.UPSTREAM_PROTOCOL_ERROR.statusCode());
    }

    @Test
    void noRouteAndConfigKinds_areDistinctFromUpstreamErrors() {
        // 四类错误码互不相同，前端凭码就能区分「没找着路 / 上游连不上 / 上游超时 / 配置不可用」
        assertEquals(404, UpstreamFailureKind.NO_ROUTE.statusCode());
        assertEquals(502, UpstreamFailureKind.UPSTREAM_UNAVAILABLE.statusCode());
        assertEquals(504, UpstreamFailureKind.UPSTREAM_TIMEOUT.statusCode());
        assertEquals(503, UpstreamFailureKind.CONFIG_UNAVAILABLE.statusCode());
    }

    @Test
    void causeCycle_doesNotLoopForever() {
        // 防御：异常链成环也不能把分类器挂死
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);
        assertEquals(UpstreamFailureKind.UPSTREAM_PROTOCOL_ERROR, UpstreamFailureKind.classify(a));
    }
}
