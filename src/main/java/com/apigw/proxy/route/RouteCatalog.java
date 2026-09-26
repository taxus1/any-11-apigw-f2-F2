package com.apigw.proxy.route;

import com.apigw.domain.route.GatewayRoute;
import com.apigw.infrastructure.store.RouteStore;
import com.apigw.proxy.config.GatewayProxyProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * 转发侧的「当前可用路由快照」。
 *
 * 转发是高频读路径，不可能每个请求都去 Redis 拉全量路由，所以这里在内存里缓存一份
 * 「已启用 + 至少一条匹配条件」的路由快照，匹配器直接在快照上做内存匹配。
 *
 * 新鲜度靠两件事保证，配置改完不用重启：
 * 1. 管理接口增删改后发 {@link RoutesChangedEvent}，收到事件立即重载（本实例即时生效）；
 * 2. {@link #scheduledRefresh()} 按固定周期兜底轮询——多实例部署时，别的实例改了配置
 *    本实例收不到它的进程内事件，轮询保证最终一致。
 *
 * 容错原则：Redis 一时连不上不能拖垮转发——重载失败时沿用上一份好快照继续服务，
 * 只在「从没加载成功过」时才对转发流量回 503；加载是异步的，绝不阻塞请求线程。
 * 并发请求同时触发懒加载时，共用同一个进行中的 Mono，不会打成雷群。
 */
@Slf4j
@Component
public class RouteCatalog {

    private final RouteStore routeStore;
    private final Duration ttl;

    /** 最近一份可用快照；启动后首次加载成功前为 null。 */
    private volatile Snapshot snapshot;
    /** 进行中的加载，多个请求并发触发时复用它。 */
    private Mono<List<GatewayRoute>> inflight;

    public RouteCatalog(RouteStore routeStore, GatewayProxyProperties properties) {
        this.routeStore = routeStore;
        // 兜底周期刷新后，快照 TTL 略大于刷新周期；两者配合：事件负责即时，TTL/轮询负责兜底
        this.ttl = properties.routeRefreshInterval().multipliedBy(3);
    }

    /**
     * 当前可用路由快照：新鲜就直接给；过期了拉一份新的并更新快照。
     * 加载失败时 load() 内部沿用上一份好快照继续服务；只有「从未加载成功过」才会把错误冒出来，
     * 由过滤器回 503 CONFIG_UNAVAILABLE。
     */
    public Mono<List<GatewayRoute>> routes() {
        Snapshot current = this.snapshot;
        if (current != null && !isStale(current)) {
            return Mono.just(current.routes());
        }
        // 过期或首次：拉新的并更新快照；失败时 load() 内部回落到旧快照，只有从没成功过才会真正报错
        return refresh();
    }

    private boolean isStale(Snapshot current) {
        return System.currentTimeMillis() - current.loadedAtMs() > ttl.toMillis();
    }

    /** 配置变更事件：立即重载，不等下一个轮询周期。 */
    @EventListener(RoutesChangedEvent.class)
    public void onRoutesChanged(RoutesChangedEvent event) {
        log.debug("收到路由变更事件（{}），立即重载路由快照", event.reason());
        refresh().subscribe(
                list -> log.info("路由快照已按变更事件重载，当前可用路由 {} 条", list.size()),
                err -> log.warn("路由变更后重载失败，继续沿用上一份快照：{}", err.toString()));
    }

    /** 兜底轮询：别的实例改了配置时，靠它在一个周期内收敛。固定 10s，可由同名属性覆盖。 */
    @Scheduled(fixedDelayString = "${apigw.proxy.route-refresh-interval-ms:10000}")
    public void scheduledRefresh() {
        refresh().subscribe(
                list -> log.debug("路由快照定时刷新完成，当前可用路由 {} 条", list.size()),
                err -> log.debug("路由快照定时刷新失败，沿用上一份快照：{}", err.toString()));
    }

    /** 启动就绪后先拉一次，避免首个请求承担冷启动延迟。 */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        refresh().subscribe(
                list -> log.info("路由快照预热完成，当前可用路由 {} 条", list.size()),
                err -> log.warn("路由快照预热失败（Redis 未就绪？），将在有请求时重试：{}", err.toString()));
    }

    /** 强制拉一份新快照；失败保留旧快照。 */
    public Mono<List<GatewayRoute>> refresh() {
        return load()
                .doOnNext(list -> this.snapshot = new Snapshot(List.copyOf(list), System.currentTimeMillis()));
    }

    /**
     * 从 Redis 读全量，过滤出「启用且有条件」的路由。
     * 停用的不参与匹配；没有任何条件的路由语义不明（等于全放行），与 SCG 装载侧口径一致，不转发。
     */
    private Mono<List<GatewayRoute>> load() {
        Mono<List<GatewayRoute>> task = inflight;
        if (task == null) {
            synchronized (this) {
                task = inflight;
                if (task == null) {
                    task = routeStore.findAll()
                            .filter(r -> Integer.valueOf(1).equals(r.getEnabled()))
                            .filter(r -> r.getConditions() != null && !r.getConditions().isEmpty())
                            .collectList()
                            .doFinally(sig -> {
                                synchronized (RouteCatalog.this) {
                                    inflight = null;
                                }
                            })
                            // 失败时若有旧快照就用旧的，只有启动期首次失败才真正「无配置」
                            .onErrorResume(err -> {
                                log.warn("加载路由快照失败：{}", err.toString());
                                Snapshot last = this.snapshot;
                                return last == null ? Mono.error(err) : Mono.just(last.routes());
                            })
                            .cache();
                    inflight = task;
                }
            }
        }
        return task;
    }

    private record Snapshot(List<GatewayRoute> routes, long loadedAtMs) {
    }
}
