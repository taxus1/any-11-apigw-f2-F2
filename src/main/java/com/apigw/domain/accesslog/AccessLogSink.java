package com.apigw.domain.accesslog;

/**
 * 流水的出口（写侧端口）。转发主路径只依赖这个接口，不关心后面是攒批写库还是别的。
 *
 * 契约：
 * - {@link #record} 必须立刻返回，绝不在请求线程上等库；做不到（队列满）就丢这一条并计数告警，
 *   绝不抛异常出去影响转发——转发是网关的主职责，流水是旁路。
 * - 应用正常退出时，已经进了出口的记录要尽量 flush 完（实现侧负责，调用方无感）。
 */
public interface AccessLogSink {

    /** 收一行完整流水。该方法不允许抛出受检/非受检异常到调用方。 */
    void record(AccessLogEntry entry);
}
