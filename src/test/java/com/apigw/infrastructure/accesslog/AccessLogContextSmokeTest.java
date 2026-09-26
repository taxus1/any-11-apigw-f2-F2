package com.apigw.infrastructure.accesslog;

import com.apigw.domain.accesslog.AccessLogRepository;
import com.apigw.domain.accesslog.AccessLogSink;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * enabled=true 时的 Spring 上下文冒烟：数据源、JdbcTemplate、事务管理器由条件装配拉起，
 * 出口是异步攒批实现（不是空实现），写线程随容器启动、随容器关停。
 *
 * 用 H2 内存库充当 MySQL：不依赖外部数据库也能验证真实 bean 接线。
 */
@SpringBootTest(
        properties = {
                "apigw.accesslog.enabled=true",
                "apigw.accesslog.batch-size=10",
                "apigw.accesslog.flush-interval=200ms",
                "spring.datasource.url=jdbc:h2:mem:ctxsmoke;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=6399" // 指向不存在的端口：本测试不碰 Redis，避免启动期连接
        })
class AccessLogContextSmokeTest {

    @Autowired
    ApplicationContext ctx;
    @Autowired
    AccessLogSink sink;
    @Autowired
    AccessLogRepository repository;

    @Test
    void wiresJdbcBeans_andAsyncSinkIsRunning() {
        assertThat(sink).isInstanceOf(AsyncBatchingAccessLogSink.class);
        assertThat(ctx.getBean(JdbcAccessLogRepository.class)).isSameAs(repository);
        assertThat(ctx.getBean(AccessLogQueryService.class)).isNotNull();
        assertThat(ctx.getBean(javax.sql.DataSource.class)).isNotNull();
        assertThat(((AsyncBatchingAccessLogSink) sink).isRunning()).isTrue();
    }
}
