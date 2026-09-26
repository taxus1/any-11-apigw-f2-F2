package com.apigw.infrastructure.app;

import com.apigw.domain.app.AppCredentialRepository;
import com.apigw.domain.app.ClientApp;
import com.apigw.domain.app.DuplicateAppNoException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 应用/来路仓储真实 SQL 集成测试（H2 MySQL 兼容模式）。
 *
 * 覆盖：
 * - 主记录读写与散列原样落库（明文绝不落库由应用层保证，这里确认库里只有散列）；
 * - 同编号并发/重复建由唯一索引原子拒绝（DuplicateAppNoException）；
 * - 来路加入/删除幂等（INSERT IGNORE + 唯一键；重复删不报错）；
 * - 列表模糊匹配 + count 与分页数字一致，originCount 由 SQL 带出；
 * - 鉴权快照应用与名单在同一视图读齐。
 */
class JdbcAppCredentialRepositoryTest {

    private EmbeddedDatabase db;
    private JdbcTemplate jdbc;
    private JdbcAppCredentialRepository repository;
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"), ZoneOffset.UTC);

    private static final String SECRET = "abcdefghijklmnopqrstuvwxyz0123456789ABCD";

    @BeforeEach
    void setUp() {
        db = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("appcreds;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
                .addScript("classpath:schema-app.sql")
                .build();
        jdbc = new JdbcTemplate(db);
        repository = new JdbcAppCredentialRepository(jdbc, new DataSourceTransactionManager(db));
    }

    @AfterEach
    void tearDown() {
        db.shutdown();
    }

    private ClientApp app(String no, String name, Integer enabled) {
        return ClientApp.create(no, name, SECRET, null, enabled, "contact", "remark", "admin", clock);
    }

    @Test
    void insert_andFindRoundTrips_allColumns_andOnlyHashStored() {
        ClientApp app = app("app-1", "订单应用", 1);
        repository.insert(app);
        assertThat(app.getId()).isNotNull();

        ClientApp loaded = repository.findByAppNo("app-1").orElseThrow();
        assertThat(loaded.getAppName()).isEqualTo("订单应用");
        assertThat(loaded.getEnabled()).isEqualTo(1);
        assertThat(loaded.getContact()).isEqualTo("contact");
        assertThat(loaded.getCreatedBy()).isEqualTo("admin");
        assertThat(loaded.getCreatedAt()).isEqualTo(Instant.parse("2026-09-26T00:00:00Z"));
        assertThat(loaded.getSecretHash()).startsWith("pbkdf2$");

        // 库表里只有散列，没有明文
        String stored = jdbc.queryForObject("SELECT secret_hash FROM gw_app WHERE app_no = 'app-1'",
                String.class);
        assertThat(stored).startsWith("pbkdf2$").doesNotContain(SECRET);
    }

    @Test
    void duplicateAppNo_isAtomicRejected_byUniqueIndex() {
        repository.insert(app("dup", "第一个", 1));
        assertThatThrownBy(() -> repository.insert(app("dup", "第二个", 1)))
                .isInstanceOf(DuplicateAppNoException.class)
                .hasMessageContaining("dup");

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM gw_app WHERE app_no = 'dup'",
                Integer.class);
        assertThat(count).isEqualTo(1);
        assertThat(repository.findByAppNo("dup").orElseThrow().getAppName()).isEqualTo("第一个");
    }

    @Test
    void findMissing_returnsEmpty() {
        assertThat(repository.findByAppNo("nope")).isEmpty();
    }

    @Test
    void enableDisable_isIdempotent_andMissingReturnsZero() {
        repository.insert(app("app-1", "n", 1));
        assertThat(repository.updateEnabled("app-1", 0)).isEqualTo(1);
        assertThat(repository.updateEnabled("app-1", 0)).isEqualTo(1); // 重复停用仍成功
        assertThat(jdbc.queryForObject("SELECT enabled FROM gw_app WHERE app_no='app-1'", Integer.class))
                .isZero();
        assertThat(repository.updateEnabled("ghost", 0)).isZero();
    }

    @Test
    void addOrigin_isIdempotent_andRemoveIsIdempotent_andRejectsMissingApp() {
        repository.insert(app("app-1", "n", 1));
        repository.addOrigin("app-1", "192.168.1.1", Instant.now(clock));
        repository.addOrigin("app-1", "192.168.1.1", Instant.now(clock)); // 重复加不报错
        repository.addOrigin("app-1", "2001:0db8:0000:0000:0000:0000:0000:0001", Instant.now(clock));

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM gw_app_origin WHERE app_no='app-1'", Integer.class);
        assertThat(rows).isEqualTo(2);

        repository.removeOrigin("app-1", "192.168.1.1");
        repository.removeOrigin("app-1", "192.168.1.1"); // 本来就没有也不报错
        Integer rows2 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM gw_app_origin WHERE app_no='app-1'", Integer.class);
        assertThat(rows2).isEqualTo(1);

        assertThatThrownBy(() -> repository.addOrigin("ghost", "1.1.1.1", Instant.now(clock)))
                .hasMessageContaining("应用不存在");
        assertThatThrownBy(() -> repository.removeOrigin("ghost", "1.1.1.1"))
                .hasMessageContaining("应用不存在");
    }

    @Test
    void findByAppNo_loadsOrigins() {
        repository.insert(app("app-1", "n", 1));
        repository.addOrigin("app-1", "10.0.0.1", Instant.now(clock));
        repository.addOrigin("app-1", "10.0.0.2", Instant.now(clock));
        ClientApp loaded = repository.findByAppNo("app-1").orElseThrow();
        assertThat(loaded.getOrigins()).containsExactlyInAnyOrder("10.0.0.1", "10.0.0.2");
    }

    @Test
    void page_keywordFilter_andExactCounts_andOriginCount() {
        repository.insert(app("order-01", "订单服务", 1));
        repository.insert(app("order-02", "订单渠道", 1));
        repository.insert(app("pay-01", "支付应用", 0));
        repository.addOrigin("order-01", "10.0.0.1", Instant.now(clock));
        repository.addOrigin("order-01", "10.0.0.2", Instant.now(clock));
        repository.addOrigin("pay-01", "10.0.0.3", Instant.now(clock));

        AppCredentialRepository.AppPage all = repository.page(null, 0, 20);
        assertThat(all.total()).isEqualTo(3);
        assertThat(all.content()).extracting(AppCredentialRepository.AppRow::appNo)
                .containsExactly("order-01", "order-02", "pay-01"); // 按编号稳定排序

        AppCredentialRepository.AppPage byNo = repository.page("ORDER-01", 0, 20); // 忽略大小写
        assertThat(byNo.total()).isEqualTo(1);
        assertThat(byNo.content().get(0).originCount()).isEqualTo(2);

        AppCredentialRepository.AppPage byName = repository.page("订单", 0, 20); // 名称模糊
        assertThat(byName.total()).isEqualTo(2);

        AppCredentialRepository.AppPage p1 = repository.page(null, 0, 2);
        AppCredentialRepository.AppPage p2 = repository.page(null, 2, 2);
        assertThat(p1.content()).hasSize(2);
        assertThat(p2.content()).hasSize(1);
        assertThat(p1.total()).isEqualTo(3);
        assertThat(p2.total()).isEqualTo(3);
    }

    @Test
    void loadAllForAuth_bringsAppsAndOrigins_inOneView() {
        repository.insert(app("a-1", "n", 1));
        repository.insert(app("a-2", "n", 0));
        repository.addOrigin("a-1", "1.1.1.1", Instant.now(clock));

        List<AppCredentialRepository.AuthApp> all = repository.loadAllForAuth();
        assertThat(all).hasSize(2);
        AppCredentialRepository.AuthApp first = all.stream()
                .filter(a -> a.appNo().equals("a-1")).findFirst().orElseThrow();
        assertThat(first.enabled()).isTrue();
        assertThat(first.secretHash()).startsWith("pbkdf2$");
        assertThat(first.origins()).containsExactly("1.1.1.1");
        assertThat(all.stream().filter(a -> a.appNo().equals("a-2")).findFirst().orElseThrow()
                .origins()).isEmpty(); // 空名单 = 不限
    }
}
