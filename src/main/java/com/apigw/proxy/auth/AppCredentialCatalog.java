package com.apigw.proxy.auth;

import com.apigw.domain.app.AppCredentialRepository;
import com.apigw.domain.app.SecretHasher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 转发鉴权侧的「应用凭据 + 来路名单」内存快照。
 *
 * 转发是高频路径，不可能每笔请求查库，所以内存里缓存一份全量凭据（应用通常不多），
 * 每笔请求只做一次 O(1) 哈希表查找 + PBKDF2 校验。
 *
 * 新鲜度与 {@code RouteCatalog} 同一套路：
 * 1. 管理接口写成功后发 {@link AppCredentialChangedEvent}，收到事件立即重载——
 *    **名单/状态一改，之后的新请求就按新数据判定**，没有宽限期；
 * 2. {@link #scheduledRefresh()} 按 {@code apigw.app-auth.refresh-interval}（默认 10s）兜底，
 *    多实例部署时别的实例改了配置，本实例在一个周期内收敛。
 *
 * 容错（安全侧从严）：
 * - 重载失败时若已有旧快照，沿用上一份继续服务（库的一时抖动不该把所有调用方挡在门外），
 *   同时打 warn；停用/删名单这类变更本实例会因事件即时重载，事件丢了最坏 10s 兜底；
 * - **从没加载成功过时没有任何凭据可用，fail-closed 直接拒**，回 503 配置不可用，
 *   绝不在「凭据读不出来」时裸放行。
 *
 * JDBC 是阻塞调用，所有读库统一切到 boundedElastic，绝不占 Netty 事件循环。
 */
@Slf4j
public class AppCredentialCatalog {

    private final AppCredentialRepository repository;
    private final Clock clock;

    private volatile Snapshot snapshot;

    public AppCredentialCatalog(AppCredentialRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * 当次请求的鉴权判定。返回 {@link Decision}：
     * - 快照尚未加载成功：{@link Outcome#CONFIG_UNAVAILABLE}（fail-closed）；
     * - 应用编号不存在：{@link Outcome#UNKNOWN_APP}；
     * - 其余按状态/有效期/密钥/来路逐项判定（算法口径与领域聚合一致）。
     */
    public Mono<Decision> authenticate(String appNo, String rawSecret, String canonicalClientIp) {
        return Mono.fromCallable(() -> {
            Snapshot current = this.snapshot;
            if (current == null) {
                return new Decision(Outcome.CONFIG_UNAVAILABLE, null);
            }
            AuthAppView app = current.byAppNo().get(appNo);
            if (app == null) {
                return new Decision(Outcome.UNKNOWN_APP, null);
            }
            Instant now = Instant.now(clock);
            if (!app.enabled()) {
                return new Decision(Outcome.DISABLED, appNo);
            }
            if (app.secretExpiresAt() != null && !now.isBefore(app.secretExpiresAt())) {
                return new Decision(Outcome.SECRET_EXPIRED, appNo);
            }
            if (!SecretHasher.matches(rawSecret, app.secretHash())) {
                return new Decision(Outcome.BAD_SECRET, appNo);
            }
            // 空名单 = 不限制；非空严格精确匹配（双方都是规范化字面量）
            if (!app.origins().isEmpty() && !app.origins().contains(canonicalClientIp)) {
                return new Decision(Outcome.ORIGIN_FORBIDDEN, appNo);
            }
            return new Decision(Outcome.ALLOWED, appNo);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 变更事件：立即重载，停用/改名单对下一笔新请求即时生效。 */
    @EventListener(AppCredentialChangedEvent.class)
    public void onChanged(AppCredentialChangedEvent event) {
        log.debug("收到应用凭据变更事件（{} {}），立即重载鉴权快照", event.appNo(), event.reason());
        refresh().subscribe(
                n -> log.info("鉴权快照已按变更事件重载，当前应用 {} 个", n),
                err -> log.warn("鉴权快照变更后重载失败，继续沿用上一份：{}", err.toString()));
    }

    /** 兜底轮询：多实例间最终一致。 */
    @Scheduled(fixedDelayString = "${apigw.app-auth.refresh-interval-ms:10000}")
    public void scheduledRefresh() {
        refresh().subscribe(
                n -> log.debug("鉴权快照定时刷新完成，当前应用 {} 个", n),
                err -> log.debug("鉴权快照定时刷新失败，沿用上一份：{}", err.toString()));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        refresh().subscribe(
                n -> log.info("鉴权快照预热完成，当前应用 {} 个", n),
                err -> log.warn("鉴权快照预热失败（库未就绪？），将在有请求时重试：{}", err.toString()));
    }

    /** 拉一份新快照；失败保留旧快照（从未成功过时 snapshot 仍为 null，鉴权 fail-closed）。 */
    public Mono<Integer> refresh() {
        return Mono.fromCallable(repository::loadAllForAuth)
                .subscribeOn(Schedulers.boundedElastic())
                .map(apps -> {
                    Map<String, AuthAppView> byNo = new HashMap<>(apps.size() * 2);
                    for (AppCredentialRepository.AuthApp a : apps) {
                        Set<String> origins = Set.copyOf(a.origins());
                        byNo.put(a.appNo(), new AuthAppView(
                                a.appNo(), a.secretHash(), a.secretExpiresAt(), a.enabled(), origins));
                    }
                    this.snapshot = new Snapshot(Map.copyOf(byNo), System.currentTimeMillis());
                    return byNo.size();
                })
                // 失败不外抛：旧快照沿用；从没成功过时 authenticate 见 snapshot=null 直接拒
                .onErrorResume(err -> {
                    log.warn("加载鉴权快照失败：{}", err.toString());
                    return Mono.just(this.snapshot == null ? 0 : this.snapshot.byAppNo().size());
                });
    }

    public boolean hasSnapshot() {
        return snapshot != null;
    }

    /** 测试/启动期需要同步拿到首份快照时用。 */
    public void refreshBlock(Duration timeout) {
        refresh().block(timeout);
    }

    public enum Outcome {
        ALLOWED,
        UNKNOWN_APP,
        DISABLED,
        SECRET_EXPIRED,
        BAD_SECRET,
        ORIGIN_FORBIDDEN,
        CONFIG_UNAVAILABLE
    }

    public record Decision(Outcome outcome, String authenticatedAppNo) {

        public boolean allowed() {
            return outcome == Outcome.ALLOWED;
        }
    }

    private record AuthAppView(String appNo, String secretHash, Instant secretExpiresAt,
                               boolean enabled, Set<String> origins) {
    }

    private record Snapshot(Map<String, AuthAppView> byAppNo, long loadedAtMs) {
    }
}
