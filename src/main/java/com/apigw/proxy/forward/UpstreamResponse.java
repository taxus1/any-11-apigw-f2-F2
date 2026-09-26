package com.apigw.proxy.forward;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Flux;

/**
 * 上游响应的运行时载体：状态码 + 响应头 + 响应体数据流。
 *
 * body 是「拿到一个发一个」的流，由上层在把响应写回调用方的过程中消费掉，
 * 网关不把整个响应体读进内存（大响应也不会撑爆堆）。
 */
public record UpstreamResponse(int statusCode, HttpHeaders headers, Flux<DataBuffer> body) {
}
