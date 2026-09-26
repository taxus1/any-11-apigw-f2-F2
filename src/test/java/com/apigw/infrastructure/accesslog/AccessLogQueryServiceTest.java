package com.apigw.infrastructure.accesslog;

import com.apigw.common.exception.BizException;
import com.apigw.domain.accesslog.AccessLogEntry;
import com.apigw.domain.accesslog.AccessLogQuery;
import com.apigw.domain.accesslog.AccessLogRepository;
import com.apigw.infrastructure.store.dto.PageResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 查询服务单测：参数护栏 + 分页口径（页码从 1、每页封顶、total/totalPages 与实际对得上、
 * 深翻页不跑大 OFFSET）。底层用一个按条件真过滤的内存仓储，不引 JDBC。
 */
class AccessLogQueryServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-26T00:00:00Z");

    private AccessLogRepository repositoryWith(int n) {
        List<AccessLogEntry> data = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            data.add(new AccessLogEntry("e" + i, "r", "a", "1.1.1.1",
                    "GET", "/x", i % 2 == 0 ? 200 : 500, i,
                    T0.plus(i, ChronoUnit.MILLIS)));
        }
        return new InMemoryFilteringRepository(data);
    }

    private final AccessLogQueryService service =
            new AccessLogQueryService(repositoryWith(95));

    @Test
    void returnsExactPaginationNumbers() {
        PageResult<AccessLogEntry> p1 = service.page(T0, T0.plus(1, ChronoUnit.DAYS),
                null, null, 1, 20);
        assertThat(p1.content()).hasSize(20);
        assertThat(p1.pageNum()).isEqualTo(1);
        assertThat(p1.pageSize()).isEqualTo(20);
        assertThat(p1.total()).isEqualTo(95);
        assertThat(p1.totalPages()).isEqualTo(5); // ceil(95/20)

        PageResult<AccessLogEntry> last = service.page(T0, T0.plus(1, ChronoUnit.DAYS),
                null, null, 5, 20);
        assertThat(last.content()).hasSize(15);
        assertThat(last.totalPages()).isEqualTo(5);
    }

    @Test
    void pageNumStartsFromOne_negativeOrZeroBecomesOne() {
        PageResult<AccessLogEntry> p = service.page(T0, T0.plus(1, ChronoUnit.DAYS),
                null, null, -3, 20);
        assertThat(p.pageNum()).isEqualTo(1);
        assertThat(p.content()).hasSize(20);
    }

    @Test
    void pageSizeIsCapped_at200_andDefault20() {
        PageResult<AccessLogEntry> capped = service.page(T0, T0.plus(1, ChronoUnit.DAYS),
                null, null, 1, 999_999);
        assertThat(capped.pageSize()).isEqualTo(200);
        assertThat(capped.totalPages()).isEqualTo(1); // 95 条一页放得下

        PageResult<AccessLogEntry> def = service.page(T0, T0.plus(1, ChronoUnit.DAYS),
                null, null, 1, null);
        assertThat(def.pageSize()).isEqualTo(20);
    }

    @Test
    void beyondLastPage_emptyContentButRealTotalAndPages() {
        PageResult<AccessLogEntry> p = service.page(T0, T0.plus(1, ChronoUnit.DAYS),
                null, null, 50, 20);
        assertThat(p.content()).isEmpty();
        assertThat(p.total()).isEqualTo(95);
        assertThat(p.totalPages()).isEqualTo(5);
    }

    @Test
    void deepPagingIsGuarded_returnsEmptyWithoutRunningHugeOffset() {
        // 超深翻页（offset >= 10 万）：只允许为拿 total 打一次轻量查询（offset 0, limit 1），
        // 大 OFFSET 的取数绝不允许真打到仓储
        AccessLogRepository allowTotalOnly = new AccessLogRepository() {
            @Override
            public void saveBatch(List<AccessLogEntry> entries) {
            }

            @Override
            public AccessLogPage page(AccessLogQuery query, long offset, int limit) {
                if (offset == 0 && limit == 1) {
                    return new AccessLogPage(List.of(), 500_000);
                }
                throw new AssertionError("不应执行深翻页查询，offset=" + offset + " limit=" + limit);
            }
        };
        PageResult<AccessLogEntry> p = new AccessLogQueryService(allowTotalOnly)
                .page(T0, T0.plus(1, ChronoUnit.DAYS), null, null, 1000, 200);
        assertThat(p.content()).isEmpty();
        assertThat(p.total()).isEqualTo(500_000);
        assertThat(p.totalPages()).isEqualTo(2500);
    }

    @Test
    void missingTimeWindow_isRejected() {
        assertThatThrownBy(() -> service.page(null, T0, null, null, 1, 20))
                .isInstanceOf(BizException.class).hasMessageContaining("起止时间");
        assertThatThrownBy(() -> service.page(T0, null, null, null, 1, 20))
                .isInstanceOf(BizException.class).hasMessageContaining("起止时间");
    }

    @Test
    void endBeforeStart_isRejected() {
        assertThatThrownBy(() -> service.page(T0.plusSeconds(10), T0, null, null, 1, 20))
                .isInstanceOf(BizException.class).hasMessageContaining("结束时间");
    }

    @Test
    void rangeOverSevenDays_isRejected() {
        assertThatThrownBy(() -> service.page(T0, T0.plus(8, ChronoUnit.DAYS),
                null, null, 1, 20))
                .isInstanceOf(BizException.class).hasMessageContaining("7 天");
    }

    @Test
    void invalidStatusCode_isRejected() {
        assertThatThrownBy(() -> service.page(T0, T0.plusSeconds(10),
                null, 99, 1, 20))
                .isInstanceOf(BizException.class).hasMessageContaining("状态码");
        // 内部口径 0 也不允许作为查询入参
        assertThatThrownBy(() -> service.page(T0, T0.plusSeconds(10),
                null, 0, 1, 20))
                .isInstanceOf(BizException.class);
    }

    /** 按 AccessLogQuery 条件做真过滤的内存仓储，验证组合筛选与分页切片正确。 */
    static class InMemoryFilteringRepository implements AccessLogRepository {
        private final List<AccessLogEntry> data;

        InMemoryFilteringRepository(List<AccessLogEntry> data) {
            this.data = List.copyOf(data);
        }

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
            List<AccessLogEntry> slice = filtered.stream()
                    .skip(offset).limit(limit).toList();
            return new AccessLogPage(slice, filtered.size());
        }
    }
}
