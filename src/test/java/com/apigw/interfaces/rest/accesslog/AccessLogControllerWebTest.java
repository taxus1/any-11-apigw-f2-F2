package com.apigw.interfaces.rest.accesslog;

import com.apigw.common.exception.GlobalExceptionHandler;
import com.apigw.domain.accesslog.AccessLogEntry;
import com.apigw.domain.accesslog.AccessLogQuery;
import com.apigw.domain.accesslog.AccessLogRepository;
import com.apigw.infrastructure.accesslog.AccessLogQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 翻流水接口的 Web 切片：Controller（含时间串解析）+ 真实查询服务 + 内存仓储，
 * 不引 JDBC。验证统一返回结构、组合筛选、分页字段齐全且对得上、参数错误走统一收口。
 */
class AccessLogControllerWebTest {

    private WebTestClient web;

    @BeforeEach
    void setUp() {
        Instant t0 = Instant.parse("2026-09-26T10:00:00Z");
        List<AccessLogEntry> data = new ArrayList<>();
        data.add(new AccessLogEntry("t1", "order", "app-a", "203.0.113.1",
                "GET", "/order/1", 200, 11, t0));
        data.add(new AccessLogEntry("t2", "order", "app-a", "203.0.113.2",
                "GET", "/order/2", 502, 9001, t0.plus(1, ChronoUnit.MINUTES)));
        data.add(new AccessLogEntry("t3", "pay", null, "10.0.0.3",
                "POST", "/pay/x", 200, 7, t0.plus(2, ChronoUnit.MINUTES)));
        AccessLogRepository repo = new AccessLogRepository() {
            @Override
            public void saveBatch(List<AccessLogEntry> entries) {
            }

            @Override
            public AccessLogPage page(AccessLogQuery q, long offset, int limit) {
                List<AccessLogEntry> filtered = data.stream()
                        .filter(e -> q.startTime() == null || !e.occurredAt().isBefore(q.startTime()))
                        .filter(e -> q.endTime() == null || e.occurredAt().isBefore(q.endTime()))
                        .filter(e -> q.routeNo() == null || q.routeNo().equals(e.routeNo()))
                        .filter(e -> q.statusCode() == null || q.statusCode() == e.statusCode())
                        .toList();
                return new AccessLogPage(filtered.stream().skip(offset).limit(limit).toList(),
                        filtered.size());
            }
        };
        web = WebTestClient.bindToController(new AccessLogController(new AccessLogQueryService(repo)))
                .controllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void queryByTimeRange_returnsPaginationEnvelope() {
        web.get().uri("/api/gateway/access-logs?startTime=2026-09-26T10:00:00Z"
                        + "&endTime=2026-09-26T11:00:00Z&pageNum=1&pageSize=2")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.code").isEqualTo(0)
                .jsonPath("$.data.content.length()").isEqualTo(2)
                .jsonPath("$.data.pageNum").isEqualTo(1)
                .jsonPath("$.data.pageSize").isEqualTo(2)
                .jsonPath("$.data.total").isEqualTo(3)
                .jsonPath("$.data.totalPages").isEqualTo(2)
                // 行字段齐全，失败行也在
                .jsonPath("$.data.content[0].requestId").isEqualTo("t1")
                .jsonPath("$.data.content[0].clientIp").isEqualTo("203.0.113.1")
                .jsonPath("$.data.content[0].appNo").isEqualTo("app-a")
                .jsonPath("$.data.content[1].statusCode").isEqualTo(502)
                .jsonPath("$.data.content[1].elapsedMs").isEqualTo(9001);
    }

    @Test
    void filtersCombine_routeAndStatus() {
        web.get().uri("/api/gateway/access-logs?startTime=2026-09-26T10:00:00Z"
                        + "&endTime=2026-09-26T11:00:00Z&routeNo=order&statusCode=200")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.total").isEqualTo(1)
                .jsonPath("$.data.content[0].requestId").isEqualTo("t1");
    }

    @Test
    void acceptsOffsetLocalTime_interpretedAsUtc() {
        // 不带偏移的本地时间按 UTC 解释：10:00:00 正好含 t0
        web.get().uri("/api/gateway/access-logs?startTime=2026-09-26T10:00:00"
                        + "&endTime=2026-09-26T10:01:00")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.total").isEqualTo(1)
                .jsonPath("$.data.content[0].requestId").isEqualTo("t1");
    }

    @Test
    void missingTimeParams_failsWithUnifiedResult() {
        web.get().uri("/api/gateway/access-logs?endTime=2026-09-26T11:00:00Z")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.code").isEqualTo(1)
                .jsonPath("$.msg").isNotEmpty();
    }

    @Test
    void badTimeFormat_isRejected() {
        web.get().uri("/api/gateway/access-logs?startTime=nonsense&endTime=2026-09-26T11:00:00Z")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.code").isEqualTo(1)
                .jsonPath("$.msg").isEqualTo("startTime 时间格式应为 ISO-8601，如 2026-09-26T10:00:00Z");
    }

    @Test
    void rangeTooWide_isRejected() {
        web.get().uri("/api/gateway/access-logs?startTime=2026-09-01T00:00:00Z"
                        + "&endTime=2026-09-26T00:00:00Z")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.code").isEqualTo(1)
                .jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("7 天"));
    }
}
