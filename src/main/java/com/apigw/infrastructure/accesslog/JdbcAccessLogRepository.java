package com.apigw.infrastructure.accesslog;

import com.apigw.domain.accesslog.AccessLogEntry;
import com.apigw.domain.accesslog.AccessLogQuery;
import com.apigw.domain.accesslog.AccessLogRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * 访问流水的 JDBC 实现。
 *
 * 写：{@link #saveBatch} 用 addBatch + executeBatch，并显式包在**一个事务**里——
 * MySQL 默认 autocommit 下 executeBatch 里每条语句各是一个事务，中途失败会留下前半批
 * （就是「半条/半批记录」）。关 autocommit 后整批要么一起提交、要么一起回滚。
 *
 * 读：{@link #page} 的 count 与取数放进同一只读事务，快照一致，
 * 保证「总条数」与「这一页实际筛出来的行」口径完全相同。
 *
 * 所有参数都走 PreparedStatement 绑定，没有任何字符串拼接进 SQL，杜绝注入。
 */
public class JdbcAccessLogRepository implements AccessLogRepository {

    private static final String TABLE = "gw_access_log";
    private static final String COLUMNS =
            "request_id, route_no, app_no, client_ip, method, path, status_code, elapsed_ms, occurred_at";

    private static final String INSERT_SQL =
            "INSERT INTO " + TABLE + " (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?)";

    private static final RowMapper<AccessLogEntry> ROW_MAPPER = (rs, rowNum) -> new AccessLogEntry(
            rs.getString("request_id"),
            rs.getString("route_no"),
            rs.getString("app_no"),
            rs.getString("client_ip"),
            rs.getString("method"),
            rs.getString("path"),
            rs.getInt("status_code"),
            rs.getLong("elapsed_ms"),
            rs.getTimestamp("occurred_at").toInstant());

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate readOnlyTx;

    public JdbcAccessLogRepository(JdbcTemplate jdbcTemplate,
                                   PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
    }

    @Override
    public void saveBatch(List<AccessLogEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        jdbcTemplate.execute((java.sql.Connection con) -> {
            // 显式事务边界：整批一个事务，失败 con.rollback()，库里要么全有要么全无
            boolean previousAutoCommit = con.getAutoCommit();
            con.setAutoCommit(false);
            try (java.sql.PreparedStatement ps = con.prepareStatement(INSERT_SQL)) {
                for (AccessLogEntry e : entries) {
                    int i = 1;
                    ps.setString(i++, e.requestId());
                    if (e.routeNo() == null) {
                        ps.setNull(i++, Types.VARCHAR);
                    } else {
                        ps.setString(i++, e.routeNo());
                    }
                    if (e.appNo() == null) {
                        ps.setNull(i++, Types.VARCHAR);
                    } else {
                        ps.setString(i++, e.appNo());
                    }
                    ps.setString(i++, e.clientIp());
                    ps.setString(i++, e.method());
                    ps.setString(i++, e.path());
                    ps.setInt(i++, e.statusCode());
                    ps.setLong(i++, e.elapsedMs());
                    ps.setTimestamp(i, Timestamp.from(e.occurredAt()));
                    ps.addBatch();
                }
                ps.executeBatch();
                con.commit();
            } catch (Exception ex) {
                try {
                    con.rollback();
                } catch (Exception ignore) {
                    // rollback 都失败说明连接已坏，原异常更有价值，不在这覆盖
                }
                throw ex;
            } finally {
                con.setAutoCommit(previousAutoCommit);
            }
            return null;
        });
    }

    @Override
    public AccessLogPage page(AccessLogQuery query, long offset, int limit) {
        // count 与分页取数在同一个只读事务里完成：新写入对二者一致可见，total 和 content 不会对不上
        return readOnlyTx.execute(status -> {
            StringBuilder where = new StringBuilder(" WHERE 1=1");
            List<Object> args = new ArrayList<>();
            appendConditions(where, args, query);

            Long total = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + TABLE + where, Long.class, args.toArray());

            String pageSql = "SELECT id, " + COLUMNS + " FROM " + TABLE + where
                    + " ORDER BY occurred_at ASC, id ASC LIMIT ? OFFSET ?";
            List<Object> pageArgs = new ArrayList<>(args);
            pageArgs.add(limit);
            pageArgs.add(offset);
            List<AccessLogEntry> content = jdbcTemplate.query(pageSql, ROW_MAPPER, pageArgs.toArray());

            return new AccessLogPage(content, total == null ? 0L : total);
        });
    }

    /** 条件全部可选、彼此 AND；按值类型绑定参数。 */
    private void appendConditions(StringBuilder where, List<Object> args, AccessLogQuery q) {
        if (q.startTime() != null) {
            where.append(" AND occurred_at >= ?");
            args.add(Timestamp.from(q.startTime()));
        }
        if (q.endTime() != null) {
            where.append(" AND occurred_at < ?");
            args.add(Timestamp.from(q.endTime()));
        }
        if (q.routeNo() != null) {
            where.append(" AND route_no = ?");
            args.add(q.routeNo());
        }
        if (q.statusCode() != null) {
            where.append(" AND status_code = ?");
            args.add(q.statusCode());
        }
    }
}
