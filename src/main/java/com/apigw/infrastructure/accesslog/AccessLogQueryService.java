package com.apigw.infrastructure.accesslog;

import com.apigw.common.exception.BizException;
import com.apigw.domain.accesslog.AccessLogEntry;
import com.apigw.domain.accesslog.AccessLogQuery;
import com.apigw.domain.accesslog.AccessLogRepository;
import com.apigw.infrastructure.store.dto.PageResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 翻流水的应用服务：参数护栏 + 分页口径，真正的 SQL 在仓储里。
 *
 * 不把库拖垮的三道护栏：
 * 1. pageSize 封顶 {@value #MAX_PAGE_SIZE}（传 99999 也只按上限算），默认 {@value #DEFAULT_PAGE_SIZE}；
 * 2. 时间范围必须带，且跨度不超过 {@value #MAX_RANGE_DAYS} 天——时间索引才顶用，
 *    不带时间窗的「全表随便翻」直接拒绝；
 * 3. 翻页深度封顶 {@value #MAX_OFFSET_ROWS} 行，深翻页（OFFSET 巨大）在大表上是灾难，
 *    要更早的数据应缩小时间窗而不是翻几千页。
 */
public class AccessLogQueryService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 200;
    public static final int MAX_OFFSET_ROWS = 100_000;
    public static final Duration MAX_RANGE = Duration.ofDays(7);
    static final long MAX_RANGE_DAYS = 7;

    private final AccessLogRepository repository;

    public AccessLogQueryService(AccessLogRepository repository) {
        this.repository = repository;
    }

    public PageResult<AccessLogEntry> page(Instant startTime, Instant endTime,
                                           String routeNo, Integer statusCode,
                                           Integer pageNum, Integer pageSize) {
        int page = pageNum == null || pageNum < 1 ? 1 : pageNum;
        int size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE
                : Math.min(pageSize, MAX_PAGE_SIZE);

        validate(startTime, endTime, statusCode);

        long offset = (long) (page - 1) * size;
        if (offset >= MAX_OFFSET_ROWS) {
            // 超深翻页不真去跑 SQL：返回空页，total 照样是筛选口径内的真实总数
            AccessLogQuery q = AccessLogQuery.of(startTime, endTime, routeNo, statusCode);
            long total = repository.page(q, 0, 1).total();
            return new PageResult<>(List.of(), total, page, size);
        }

        AccessLogQuery query = AccessLogQuery.of(startTime, endTime, routeNo, statusCode);
        AccessLogRepository.AccessLogPage result = repository.page(query, offset, size);
        // 直接复用分页结果对象：totalPages 由它按 total/size 向上取整算，保证页数与总条数对得上
        return new PageResult<>(result.content(), result.total(), page, size);
    }

    private void validate(Instant startTime, Instant endTime, Integer statusCode) {
        if (startTime == null || endTime == null) {
            throw new BizException("查询访问流水必须带起止时间（startTime/endTime），"
                    + "最大跨度 " + MAX_RANGE_DAYS + " 天，请缩小时间窗");
        }
        if (endTime.isBefore(startTime)) {
            throw new BizException("结束时间不能早于开始时间");
        }
        if (Duration.between(startTime, endTime).compareTo(MAX_RANGE) > 0) {
            throw new BizException("时间跨度不能超过 " + MAX_RANGE_DAYS + " 天，请缩小时间窗");
        }
        if (statusCode != null && (statusCode < 100 || statusCode > 599)) {
            // 0（未产生状态码）这种内部口径也不放行给查询条件，避免误用
            throw new BizException("状态码取值范围为 100~599");
        }
    }
}
