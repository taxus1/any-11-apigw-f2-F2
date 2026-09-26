package com.apigw.proxy.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网关自身错误的统一答复：
 *
 *   HTTP 状态码  +  X-Gateway-Error 响应头（机器可读错误码）
 *              +  X-Gateway-Trace-Id 响应头（和访问日志对上号）
 *              +  JSON 体 { error, message, traceId }
 *
 * 响应头 X-Gateway-Error 是给前端「不看状态码、或状态码被中间层改写」时留的硬标识：
 * 前端凭它就能把「网关没找到路」和「后端服务出错/上游挂了」彻底分开，不用猜。
 *
 * 响应体只说网关侧的事实，绝不包含内部堆栈、异常消息或上游原始错误页。
 */
@Slf4j
public final class GatewayErrors {

    /** 网关错误的机器可读错误码响应头名。 */
    public static final String ERROR_HEADER = "X-Gateway-Error";
    /** 贯穿同一次请求的链路号响应头名（正常转发的响应也会带）。 */
    public static final String TRACE_HEADER = "X-Gateway-Trace-Id";

    private GatewayErrors() {
    }

    /**
     * 写出一个网关错误答复。exchange 一旦已经开始提交（响应头已发出），就不能再换状态码了，
     * 那种情况只能记日志后结束连接，由调用方感知连接中断。
     */
    public static Mono<Void> write(ServerWebExchange exchange, ObjectMapper objectMapper,
                                   UpstreamFailureKind kind, String traceId, Throwable detail) {
        if (exchange.getResponse().isCommitted()) {
            log.warn("响应已提交，无法回写网关错误 kind={} traceId={}：{}",
                    kind.errorCode(), traceId, detail == null ? "-" : detail.toString());
            return Mono.empty();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", kind.errorCode());
        body.put("message", kind.message());
        body.put("traceId", traceId);
        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(body);
        } catch (Exception e) {
            // JSON 序列化几乎不可能失败，退化成纯文本也不能把异常抛出去
            payload = kind.message().getBytes(StandardCharsets.UTF_8);
        }

        exchange.getResponse().setStatusCode(kind.httpStatus());
        HttpHeaders headers = exchange.getResponse().getHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(ERROR_HEADER, kind.errorCode());
        headers.set(TRACE_HEADER, traceId);
        // 这是网关自己生成的答复，内容长度必须按实际字节重算，不能沿用任何上游值
        headers.setContentLength(payload.length);

        log.debug("回网关错误 kind={} status={} traceId={} detail={}",
                kind.errorCode(), kind.statusCode(), traceId,
                detail == null ? "-" : detail.toString());
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(payload)));
    }
}
