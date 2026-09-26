package com.apigw.proxy.route;

/**
 * 路由配置变更事件：管理接口每次增/删/改成功后发布。
 *
 * 用 Spring 的进程内事件把「配置写成功」和「转发侧快照失效」解耦：
 * 应用服务只管发布事件，不直接依赖转发侧的目录组件。
 * 本实例收到事件立即重载，所以新建/修改/删除路由立刻能走通，不需要重启；
 * 多实例之间由 RouteCatalog 的定时轮询兜底收敛。
 *
 * @param routeNo 发生变更的路由编号（删除时是被删的编号），仅用于日志排查
 * @param reason 变更类型（CREATED/UPDATED/DELETED），仅用于日志排查
 */
public record RoutesChangedEvent(String routeNo, String reason) {

    public static RoutesChangedEvent created(String routeNo) {
        return new RoutesChangedEvent(routeNo, "CREATED");
    }

    public static RoutesChangedEvent updated(String routeNo) {
        return new RoutesChangedEvent(routeNo, "UPDATED");
    }

    public static RoutesChangedEvent deleted(String routeNo) {
        return new RoutesChangedEvent(routeNo, "DELETED");
    }
}
