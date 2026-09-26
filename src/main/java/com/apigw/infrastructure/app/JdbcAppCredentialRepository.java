package com.apigw.infrastructure.app;

import com.apigw.common.exception.BizException;
import com.apigw.domain.app.AppCredentialRepository;
import com.apigw.domain.app.ClientApp;
import com.apigw.domain.app.DuplicateAppNoException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 应用凭据/来路名单的 JDBC 实现。
 *
 * 安全与一致性要点：
 * - 所有参数走 PreparedStatement 绑定，没有字符串拼进 SQL；
 * - 编号重复靠 {@code uk_app_no} 唯一索引在 INSERT 时原子拒绝（不是先查后插），
 *   翻译成 {@link DuplicateAppNoException}；
 * - 状态切换、来路增删：「应用存在性校验 + 写入」放在同一个事务里；
 * - 来路加入用 INSERT IGNORE（H2/MySQL 均支持）实现幂等：重复加不报错也不产生重复行，
 *   唯一性由 {@code uk_app_ip} 保证；删除本来就是幂等操作；
 * - 分页 count 与取数在同一只读事务，快照一致，total 与 content 对得上；
 * - {@link #loadAllForAuth()} 两张表在同一事务里读齐，鉴权快照不会拿到「新状态配旧名单」。
 */
public class JdbcAppCredentialRepository implements AppCredentialRepository {

    private static final String T_APP = "gw_app";
    private static final String T_ORIGIN = "gw_app_origin";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnlyTx;

    public JdbcAppCredentialRepository(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.readOnlyTx = new TransactionTemplate(txManager);
        this.readOnlyTx.setReadOnly(true);
    }

    @Override
    public void insert(ClientApp app) {
        tx.executeWithoutResult(status -> {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            try {
                jdbc.update(con -> {
                    PreparedStatement ps = con.prepareStatement(
                            "INSERT INTO " + T_APP + " (app_no, app_name, secret_hash, secret_expires_at,"
                                    + " enabled, contact, remark, created_by, created_at)"
                                    + " VALUES (?,?,?,?,?,?,?,?,?)",
                            Statement.RETURN_GENERATED_KEYS);
                    int i = 1;
                    ps.setString(i++, app.getAppNo());
                    ps.setString(i++, app.getAppName());
                    ps.setString(i++, app.getSecretHash());
                    if (app.getSecretExpiresAt() == null) {
                        ps.setNull(i++, Types.TIMESTAMP);
                    } else {
                        ps.setTimestamp(i++, Timestamp.from(app.getSecretExpiresAt()));
                    }
                    ps.setInt(i++, app.getEnabled());
                    setNullableString(ps, i++, app.getContact());
                    setNullableString(ps, i++, app.getRemark());
                    setNullableString(ps, i++, app.getCreatedBy());
                    ps.setTimestamp(i, Timestamp.from(app.getCreatedAt()));
                    return ps;
                }, keyHolder);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // uk_app_no：并发/重复建同号在这里被原子拦住
                throw new DuplicateAppNoException(app.getAppNo());
            }
            Number key = keyHolder.getKey();
            if (key != null) {
                app.assignId(key.longValue());
            }
        });
    }

    @Override
    public Optional<ClientApp> findByAppNo(String appNo) {
        List<ClientApp> apps = jdbc.query(
                "SELECT * FROM " + T_APP + " WHERE app_no = ?", APP_MAPPER, appNo);
        if (apps.isEmpty()) {
            return Optional.empty();
        }
        ClientApp app = apps.get(0);
        jdbc.query("SELECT ip FROM " + T_ORIGIN + " WHERE app_no = ? ORDER BY id",
                rs -> {
                    app.addOriginPristine(rs.getString("ip"));
                }, appNo);
        return Optional.of(app);
    }

    @Override
    public AppPage page(String keyword, long offset, int limit) {
        return readOnlyTx.execute(status -> {
            List<Object> args = new ArrayList<>();
            String where = "";
            String kw = keyword == null ? null : keyword.trim();
            if (kw != null && !kw.isEmpty()) {
                // 编号或名称模糊匹配，忽略大小写；参数绑定，% 也只是普通字符（用 concat 拼通配符）
                where = " WHERE LOWER(a.app_no) LIKE ? OR LOWER(a.app_name) LIKE ?";
                String like = "%" + kw.toLowerCase() + "%";
                args.add(like);
                args.add(like);
            }
            Long total = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + T_APP + " a" + where, Long.class, args.toArray());

            String sql = "SELECT a.*, (SELECT COUNT(*) FROM " + T_ORIGIN + " o WHERE o.app_no = a.app_no)"
                    + " AS origin_count FROM " + T_APP + " a" + where
                    + " ORDER BY a.app_no ASC LIMIT ? OFFSET ?";
            List<Object> pageArgs = new ArrayList<>(args);
            pageArgs.add(limit);
            pageArgs.add(offset);
            List<AppRow> content = jdbc.query(sql, ROW_MAPPER, pageArgs.toArray());
            return new AppPage(content, total == null ? 0L : total);
        });
    }

    @Override
    public int updateEnabled(String appNo, int targetEnabled) {
        return tx.execute(status -> {
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + T_APP + " WHERE app_no = ?", Integer.class, appNo);
            if (exists == null || exists == 0) {
                return 0;
            }
            // 幂等：已是目标态时 UPDATE 影响 0 行，但对调用方仍是成功（返回 1 表示应用存在）
            jdbc.update("UPDATE " + T_APP + " SET enabled = ? WHERE app_no = ?", targetEnabled, appNo);
            return 1;
        });
    }

    @Override
    public void addOrigin(String appNo, String canonicalIp, Instant now) {
        tx.executeWithoutResult(status -> {
            requireAppExists(appNo);
            // INSERT IGNORE：撞 uk_app_ip 时跳过，重复加入幂等、不报错
            jdbc.update("INSERT IGNORE INTO " + T_ORIGIN + " (app_no, ip, created_at) VALUES (?,?,?)",
                    appNo, canonicalIp, Timestamp.from(now));
        });
    }

    @Override
    public void removeOrigin(String appNo, String canonicalIp) {
        tx.executeWithoutResult(status -> {
            requireAppExists(appNo);
            // DELETE 本来就幂等：没有该行影响 0 行，不报错
            jdbc.update("DELETE FROM " + T_ORIGIN + " WHERE app_no = ? AND ip = ?",
                    appNo, canonicalIp);
        });
    }

    @Override
    public List<AuthApp> loadAllForAuth() {
        return readOnlyTx.execute(status -> {
            List<ClientApp> apps = jdbc.query(
                    "SELECT * FROM " + T_APP + " ORDER BY app_no", APP_MAPPER);
            Map<String, ClientApp> byNo = new LinkedHashMap<>();
            for (ClientApp a : apps) {
                byNo.put(a.getAppNo(), a);
            }
            jdbc.query("SELECT app_no, ip FROM " + T_ORIGIN + " ORDER BY app_no, id", rs -> {
                ClientApp a = byNo.get(rs.getString("app_no"));
                if (a != null) {
                    a.addOriginPristine(rs.getString("ip"));
                }
            });
            List<AuthApp> out = new ArrayList<>(apps.size());
            for (ClientApp a : apps) {
                out.add(new AuthApp(a.getAppNo(), a.getSecretHash(), a.getSecretExpiresAt(),
                        a.getEnabled() == 1, List.copyOf(a.getOrigins())));
            }
            return out;
        });
    }

    private void requireAppExists(String appNo) {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + T_APP + " WHERE app_no = ?", Integer.class, appNo);
        if (exists == null || exists == 0) {
            throw new BizException(404, "应用不存在：" + appNo);
        }
    }

    private static void setNullableString(PreparedStatement ps, int idx, String value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(idx, Types.VARCHAR);
        } else {
            ps.setString(idx, value);
        }
    }

    /** 主表映射（不含来路名单，名单另行装载）。 */
    private static final RowMapper<ClientApp> APP_MAPPER = (rs, n) -> ClientApp.reconstitute(
            rs.getLong("id"),
            rs.getString("app_no"),
            rs.getString("app_name"),
            rs.getString("secret_hash"),
            rs.getTimestamp("secret_expires_at") == null ? null : rs.getTimestamp("secret_expires_at").toInstant(),
            rs.getInt("enabled"),
            rs.getString("contact"),
            rs.getString("remark"),
            rs.getString("created_by"),
            rs.getTimestamp("created_at").toInstant(),
            List.of());

    /** 列表行映射：来路条数由相关子查询 origin_count 一次带出。 */
    private static final RowMapper<AppRow> ROW_MAPPER = (rs, n) -> new AppRow(
            rs.getLong("id"),
            rs.getString("app_no"),
            rs.getString("app_name"),
            rs.getTimestamp("secret_expires_at") == null ? null : rs.getTimestamp("secret_expires_at").toInstant(),
            rs.getInt("enabled"),
            rs.getString("contact"),
            rs.getString("remark"),
            rs.getString("created_by"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getInt("origin_count"));
}
