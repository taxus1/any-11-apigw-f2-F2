package com.apigw.infrastructure.accesslog;

import com.apigw.domain.accesslog.AccessLogEntry;
import com.apigw.domain.accesslog.AccessLogQuery;
import com.apigw.domain.accesslog.AccessLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 流水仓储真实 SQL 集成测试（H2 MySQL 兼容模式）。
 *
 * 覆盖：批量写入、整批事务原子性（中途失败 → 一条都不留，杜绝半批）、
 * 组合条件筛选、count 与分页数字一致、排序稳定、空值列。
 */
class JdbcAccessLogRepositoryTest {

    private EmbeddedDatabase db;
    private JdbcTemplate jdbc;
    private JdbcAccessLogRepository repository;

    @BeforeEach
    void setUp() {
        db = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                // MySQL 兼容：反引号、AUTO_INCREMENT、KEY 内联索引都按 MySQL 方言解释
                .setName("accesslog;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
                .addScript("classpath:schema-access-log.sql")
                .build();
        jdbc = new JdbcTemplate(db);
        repository = new JdbcAccessLogRepository(jdbc, new DataSourceTransactionManager(db));
    }

    @AfterEach
    void tearDown() {
        db.shutdown();
    }

    private AccessLogEntry row(String id, String routeNo, String appNo, String ip,
                               String method, String path, int status, long elapsed, Instant at) {
        return new AccessLogEntry(id, routeNo, appNo, ip, method, path, status, elapsed, at);
    }

    @Test
    void savesBatch_andReadsBackAllColumns() {
        Instant at = Instant.parse("2026-09-26T10:00:00Z");
        repository.saveBatch(List.of(
                row("t1", "order", "app-1", "203.0.113.1", "GET", "/order/1", 200, 12, at),
                row("t2", null, null, "10.0.0.9", "POST", "/nope", 404, 3, at.plusMillis(5))));

        List<AccessLogEntry> all = jdbc.query(
                "SELECT * FROM gw_access_log ORDER BY id", (rs, n) -> new AccessLogEntry(
                        rs.getString("request_id"), rs.getString("route_no"),
                        rs.getString("app_no"), rs.getString("client_ip"),
                        rs.getString("method"), rs.getString("path"),
                        rs.getInt("status_code"), rs.getLong("elapsed_ms"),
                        rs.getTimestamp("occurred_at").toInstant()));

        assertThat(all).hasSize(2);
        AccessLogEntry a = all.get(0);
        assertThat(a.requestId()).isEqualTo("t1");
        assertThat(a.routeNo()).isEqualTo("order");
        assertThat(a.appNo()).isEqualTo("app-1");
        assertThat(a.clientIp()).isEqualTo("203.0.113.1");
        assertThat(a.statusCode()).isEqualTo(200);
        assertThat(a.elapsedMs()).isEqualTo(12);
        // 毫秒精度存取不丢
        assertThat(all.get(1).occurredAt()).isEqualTo(at.plusMillis(5));
        // 未匹配路由、认不出应用：两列为 NULL
        AccessLogEntry b = all.get(1);
        assertThat(b.routeNo()).isNull();
        assertThat(b.appNo()).isNull();
        assertThat(b.statusCode()).isEqualTo(404);
    }

    @Test
    void failedBatchRollsBackEntirely_noHalfBatchRows() {
        Instant at = Instant.parse("2026-09-26T10:00:00Z");
        List<AccessLogEntry> batch = new ArrayList<>(List.of(
                row("good-1", "r", "a", "1.1.1.1", "GET", "/a", 200, 1, at),
                row("good-2", "r", "a", "1.1.1.1", "GET", "/b", 200, 1, at)));
        // 中间夹一条超长 path（列长 2048），executeBatch 必失败
        String tooLongPath = "x".repeat(2049);
        batch.add(1, row("bad-row", "r", "a", "1.1.1.1", "GET", tooLongPath, 200, 1, at));
        batch.add(row("good-3", "r", "a", "1.1.1.1", "GET", "/c", 200, 1, at));

        assertThatThrownBy(() -> repository.saveBatch(batch)).isInstanceOf(Exception.class);

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM gw_access_log", Integer.class);
        // 整批回滚：good-1/good-2 也不能偷偷留下，库里一条都不该有
        assertThat(count).isZero();
    }

    @Test
    void filtersByTimeRange_routeAndStatus_combinedWithAnd() {
        Instant base = Instant.parse("2026-09-26T10:00:00Z");
        repository.saveBatch(List.of(
                row("a", "order", "app", "1.1.1.1", "GET", "/order/1", 200, 1, base),
                row("b", "order", "app", "1.1.1.1", "GET", "/order/2", 502, 1, base.plus(1, ChronoUnit.MINUTES)),
                row("c", "pay", "app", "1.1.1.1", "GET", "/pay/1", 200, 1, base.plus(2, ChronoUnit.MINUTES)),
                row("d", "order", "app", "1.1.1.1", "GET", "/order/3", 200, 1, base.plus(1, ChronoUnit.HOURS))));

        // 时间窗 [10:00, 10:05)：d 在窗外
        AccessLogRepository.AccessLogPage byTime = repository.page(
                AccessLogQuery.of(base, base.plus(5, ChronoUnit.MINUTES), null, null), 0, 20);
        assertThat(byTime.content()).extracting(AccessLogEntry::requestId)
                .containsExactly("a", "b", "c");
        assertThat(byTime.total()).isEqualTo(3);

        // 时间 + 路由 + 状态三者 AND
        AccessLogRepository.AccessLogPage combo = repository.page(
                AccessLogQuery.of(base, base.plus(5, ChronoUnit.MINUTES), "order", 502), 0, 20);
        assertThat(combo.content()).extracting(AccessLogEntry::requestId).containsExactly("b");
        assertThat(combo.total()).isEqualTo(1);

        // 只按状态
        AccessLogRepository.AccessLogPage byStatus = repository.page(
                AccessLogQuery.of(base, base.plus(2, ChronoUnit.HOURS), null, 200), 0, 20);
        assertThat(byStatus.total()).isEqualTo(3);
    }

    @Test
    void paginationNumbersAreExact_andContentMatchesTotal() {
        Instant base = Instant.parse("2026-09-26T10:00:00Z");
        List<AccessLogEntry> rows = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            rows.add(row("p" + i, "r", "a", "1.1.1.1", "GET", "/p/" + i,
                    i % 2 == 0 ? 200 : 500, i, base.plus(i, ChronoUnit.MILLIS)));
        }
        repository.saveBatch(rows);

        AccessLogQuery q = AccessLogQuery.of(base, base.plus(1, ChronoUnit.DAYS), "r", null);

        AccessLogRepository.AccessLogPage p1 = repository.page(q, 0, 20);
        AccessLogRepository.AccessLogPage p2 = repository.page(q, 20, 20);
        AccessLogRepository.AccessLogPage p3 = repository.page(q, 40, 20);

        assertThat(p1.content()).hasSize(20);
        assertThat(p2.content()).hasSize(20);
        assertThat(p3.content()).hasSize(15); // 末页不足一页
        assertThat(p1.total()).isEqualTo(55);
        assertThat(p2.total()).isEqualTo(55);
        assertThat(p3.total()).isEqualTo(55);

        // 按 occurred_at, id 稳定排序：翻页不重不漏
        List<String> ids = new ArrayList<>();
        p1.content().forEach(e -> ids.add(e.requestId()));
        p2.content().forEach(e -> ids.add(e.requestId()));
        p3.content().forEach(e -> ids.add(e.requestId()));
        assertThat(ids).containsExactlyElementsOf(rows.stream().map(AccessLogEntry::requestId).toList());
    }

    @Test
    void rowsSameMillisecond_areTieBrokenById_noPageSkip() {
        Instant at = Instant.parse("2026-09-26T10:00:00.123Z");
        List<AccessLogEntry> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            // 全部同一毫秒，排序必须靠 id 兜底
            rows.add(row("same-ms-" + i, "r", "a", "1.1.1.1", "GET", "/x", 200, 1, at));
        }
        repository.saveBatch(rows);

        AccessLogQuery q = AccessLogQuery.of(at.minusSeconds(1), at.plusSeconds(1), null, null);
        AccessLogRepository.AccessLogPage first = repository.page(q, 0, 10);
        AccessLogRepository.AccessLogPage second = repository.page(q, 10, 10);
        assertThat(first.content()).hasSize(10);
        assertThat(second.content()).hasSize(10);
        assertThat(first.content()).doesNotContainAnyElementsOf(second.content());
        assertThat(first.total()).isEqualTo(30);
    }

    @Test
    void saveEmptyBatch_isNoop() {
        repository.saveBatch(List.of());
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM gw_access_log", Integer.class);
        assertThat(count).isZero();
    }
}
