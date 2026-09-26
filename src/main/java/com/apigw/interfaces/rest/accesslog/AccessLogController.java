package com.apigw.interfaces.rest.accesslog;

import com.apigw.common.Result;
import com.apigw.domain.accesslog.AccessLogEntry;
import com.apigw.infrastructure.accesslog.AccessLogQueryService;
import com.apigw.infrastructure.store.dto.PageResult;
import com.apigw.interfaces.rest.accesslog.vo.AccessLogVO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 翻访问流水的口子：
 *
 *   GET /api/gateway/access-logs?startTime=&endTime=&routeNo=&statusCode=&pageNum=&pageSize=
 *
 * - startTime / endTime 必填，ISO-8601：可传带时区的 {@code 2026-09-26T10:00:00Z} 或
 *   {@code 2026-09-26T18:00:00+08:00}；不带偏移的本地时间按 UTC 解释（{@code 2026-09-26T10:00:00}）；
 * - routeNo / statusCode 可选，条件彼此 AND；
 * - pageNum 从 1 开始，pageSize 默认 20、上限 200；
 * - 时间跨度上限 7 天、翻页深度上限 10 万行（详见 {@link AccessLogQueryService}）。
 *
 * JDBC 是阻塞调用，绝不放在 Netty 事件循环上：统一切到 boundedElastic，
 * 慢查询不会堵住转发线程。
 */
@RestController
@ConditionalOnProperty(prefix = "apigw.accesslog", name = "enabled", havingValue = "true")
@RequestMapping("/api/gateway/access-logs")
public class AccessLogController {

    /** 不带时区偏移时的兜底格式（按 UTC 解释，行为确定、不依赖部署机器时区）。 */
    private static final DateTimeFormatter LOCAL_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final AccessLogQueryService queryService;

    public AccessLogController(AccessLogQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public Mono<Result<PageResult<AccessLogVO>>> page(
            @RequestParam String startTime,
            @RequestParam String endTime,
            @RequestParam(required = false) String routeNo,
            @RequestParam(required = false) Integer statusCode,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        Instant start = parseTime("startTime", startTime);
        Instant end = parseTime("endTime", endTime);

        return Mono.fromCallable(() -> queryService
                        .page(start, end, routeNo, statusCode, pageNum, pageSize))
                .subscribeOn(Schedulers.boundedElastic())
                .map(page -> new PageResult<>(page.content().stream().map(AccessLogVO::of).toList(),
                        page.total(), page.pageNum(), page.pageSize()))
                .map(Result::ok);
    }

    private static Instant parseTime(String name, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new com.apigw.common.exception.BizException(name + " 不能为空");
        }
        try {
            // 带 Z / 偏移直接解析成时刻
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                // 不带偏移按 UTC 解释
                return LocalDateTime.parse(value, LOCAL_FORMAT).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException e) {
                throw new com.apigw.common.exception.BizException(
                        name + " 时间格式应为 ISO-8601，如 2026-09-26T10:00:00Z");
            }
        }
    }
}
