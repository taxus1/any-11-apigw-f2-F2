package com.apigw.application.app;

import com.apigw.common.exception.BizException;
import com.apigw.domain.app.AppCredentialRepository;
import com.apigw.domain.app.ClientApp;
import com.apigw.domain.app.DuplicateAppNoException;
import com.apigw.proxy.auth.AppCredentialChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 应用服务用例测试（假仓储在内存里模拟 SQL 语义：唯一索引、INSERT IGNORE、存在性校验）。
 * 覆盖：创建返回一次性密钥且库态只有散列、重复编号被拦、停用/启用幂等并发事件、
 * 来路增删幂等与即时变更事件、分页参数护栏。
 */
class ClientAppServiceTest {

    private FakeRepository repo;
    private RecordingEventPublisher events;
    private ClientAppService service;
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        repo = new FakeRepository();
        events = new RecordingEventPublisher();
        service = new ClientAppService(repo, events, clock);
    }

    @Test
    void create_returnsOneTimePlainSecret_andStoresOnlyHash() {
        ClientAppService.CreatedApp created =
                service.create("app-1", "应用", null, 1, "c", "r", "admin");
        assertThat(created.plainSecret()).hasSize(43); // 32 字节 base64url 无填充
        assertThat(created.app().getSecretHash()).startsWith("pbkdf2$");
        assertThat(created.app().getSecretHash()).doesNotContain(created.plainSecret());

        // 仓储里的持久态同样不含明文
        ClientApp stored = repo.map.get("app-1");
        assertThat(stored.getSecretHash()).doesNotContain(created.plainSecret());
        assertThat(events.last()).extracting(AppCredentialChangedEvent::reason).isEqualTo("created");
    }

    @Test
    void create_duplicateAppNo_rejected() {
        service.create("dup", "第一个", null, 1, null, null, null);
        assertThatThrownBy(() -> service.create("dup", "第二个", null, 1, null, null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已被占用");
        assertThat(repo.map).hasSize(1);
    }

    @Test
    void create_rejectsPastExpiry_viaAggregate() {
        assertThatThrownBy(() -> service.create("a", "n",
                Instant.parse("2020-01-01T00:00:00Z"), 1, null, null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未来时刻");
    }

    @Test
    void disableAndEnable_areIdempotent_andPublishEvents() {
        service.create("app-1", "n", null, 1, null, null, null);
        service.disable("app-1");
        service.disable("app-1"); // 重复停用不出错
        assertThat(repo.map.get("app-1").getEnabled()).isZero();
        service.enable("app-1");
        service.enable("app-1");
        assertThat(repo.map.get("app-1").getEnabled()).isOne();

        List<String> reasons = events.reasons();
        assertThat(reasons).contains("disabled", "enabled");

        assertThatThrownBy(() -> service.disable("ghost"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void originAddRemove_idempotent_publishOnlyOnActualChange() {
        service.create("app-1", "n", null, 1, null, null, null);

        assertThat(service.addOrigin("app-1", "2001:DB8::1")).isTrue();
        assertThat(service.addOrigin("app-1", "2001:db8:0:0:0:0:0:1")).isFalse(); // 归并，无事件
        assertThat(service.listOrigins("app-1"))
                .containsExactly("2001:0db8:0000:0000:0000:0000:0000:0001");

        assertThat(service.removeOrigin("app-1", "2001:db8::1")).isTrue();
        assertThat(service.removeOrigin("app-1", "2001:db8::1")).isFalse(); // 已删，无事件
        assertThat(service.listOrigins("app-1")).isEmpty();

        long originEvents = events.all().stream()
                .filter(e -> e.reason().startsWith("origin-")).count();
        assertThat(originEvents).isEqualTo(2); // 一次 add + 一次 remove
    }

    @Test
    void addOrigin_rejectsNonIp() {
        service.create("app-1", "n", null, 1, null, null, null);
        assertThatThrownBy(() -> service.addOrigin("app-1", "192.168.1.0/24"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.addOrigin("app-1", "backend.svc"))
                .isInstanceOf(BizException.class);
    }

    @Test
    void page_clampsSize_andCountsExactly() {
        for (int i = 0; i < 25; i++) {
            service.create("app-" + String.format("%02d", i), "应用" + i, null, 1,
                    null, null, null);
        }
        var page = service.page(0, 99999, null);
        assertThat(page.pageSize()).isEqualTo(200); // 封顶
        assertThat(page.total()).isEqualTo(25);
        assertThat(page.totalPages()).isEqualTo(1);

        var p1 = service.page(1, 20, "应用");
        var p2 = service.page(2, 20, "应用");
        assertThat(p1.content()).hasSize(20);
        assertThat(p2.content()).hasSize(5);
        assertThat(p1.total()).isEqualTo(25);
        assertThat(p2.total()).isEqualTo(25);

        var empty = service.page(1, 20, "不存在的关键字xyz");
        assertThat(empty.total()).isZero();
        assertThat(empty.content()).isEmpty();
    }

    /** 内存假仓储：模拟唯一索引、INSERT IGNORE 幂等、来路归属与存在性校验。 */
    static class FakeRepository implements AppCredentialRepository {
        final Map<String, ClientApp> map = new HashMap<>();
        final Map<String, Set<String>> origins = new HashMap<>();
        private long seq = 0;

        @Override
        public void insert(ClientApp app) {
            if (map.containsKey(app.getAppNo())) {
                throw new DuplicateAppNoException(app.getAppNo());
            }
            app.assignId(++seq);
            map.put(app.getAppNo(), app);
            origins.put(app.getAppNo(), new LinkedHashSet<>());
        }

        @Override
        public Optional<ClientApp> findByAppNo(String appNo) {
            ClientApp app = map.get(appNo);
            if (app == null) {
                return Optional.empty();
            }
            origins.getOrDefault(appNo, Set.of()).forEach(app::addOriginPristine);
            return Optional.of(app);
        }

        @Override
        public AppPage page(String keyword, long offset, int limit) {
            List<AppRow> all = map.keySet().stream().sorted()
                    .filter(no -> keyword == null
                            || no.toLowerCase().contains(keyword.toLowerCase())
                            || map.get(no).getAppName().toLowerCase().contains(keyword.toLowerCase()))
                    .map(no -> {
                        ClientApp a = map.get(no);
                        return new AppRow(a.getId(), a.getAppNo(), a.getAppName(),
                                a.getSecretExpiresAt(), a.getEnabled(), a.getContact(),
                                a.getRemark(), a.getCreatedBy(), a.getCreatedAt(),
                                origins.get(no).size());
                    })
                    .toList();
            List<AppRow> slice = all.stream().skip(offset).limit(limit).toList();
            return new AppPage(slice, all.size());
        }

        @Override
        public int updateEnabled(String appNo, int targetEnabled) {
            ClientApp a = map.get(appNo);
            if (a == null) {
                return 0;
            }
            a.changeEnabled(targetEnabled);
            return 1;
        }

        @Override
        public void addOrigin(String appNo, String canonicalIp, Instant now) {
            if (!map.containsKey(appNo)) {
                throw new BizException(404, "应用不存在：" + appNo);
            }
            origins.get(appNo).add(canonicalIp);
        }

        @Override
        public void removeOrigin(String appNo, String canonicalIp) {
            if (!map.containsKey(appNo)) {
                throw new BizException(404, "应用不存在：" + appNo);
            }
            origins.get(appNo).remove(canonicalIp);
        }

        @Override
        public List<AuthApp> loadAllForAuth() {
            return map.values().stream()
                    .map(a -> new AuthApp(a.getAppNo(), a.getSecretHash(), a.getSecretExpiresAt(),
                            a.getEnabled() == 1, List.copyOf(origins.get(a.getAppNo()))))
                    .toList();
        }
    }

    static class RecordingEventPublisher implements ApplicationEventPublisher {
        final List<AppCredentialChangedEvent> events = new ArrayList<>();

        @Override
        public void publishEvent(Object event) {
            if (event instanceof AppCredentialChangedEvent e) {
                events.add(e);
            }
        }

        AppCredentialChangedEvent last() {
            return events.get(events.size() - 1);
        }

        List<AppCredentialChangedEvent> all() {
            return List.copyOf(events);
        }

        List<String> reasons() {
            return events.stream().map(AppCredentialChangedEvent::reason).toList();
        }
    }
}
