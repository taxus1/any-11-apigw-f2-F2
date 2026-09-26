package com.apigw.infrastructure.accesslog;

import com.apigw.domain.accesslog.AccessLogEntry;
import com.apigw.domain.accesslog.AccessLogSink;

/**
 * 不启用 JDBC 落库时的空出口：什么都不做。
 * 让没有数据库的本地/测试环境应用照常启动、转发照常工作，主链路对 {@link AccessLogSink}
 * 的依赖始终有一个 bean，不需要到处判空。
 */
public class NoopAccessLogSink implements AccessLogSink {

    @Override
    public void record(AccessLogEntry entry) {
        // 不落库；文件式 access-log 审计仍由 AccessLogRecorder 独立记录
    }
}
