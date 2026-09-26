package com.apigw.proxy.auth;

/**
 * 应用凭据/来路名单变更事件。
 *
 * 管理接口对应用或名单的任何写操作成功后发布：本实例的鉴权快照收到即重载，
 * 「停用、改名单、密钥过期」等变化对**之后到达的新请求**立刻按新数据判定。
 * 多实例间由 {@code AppCredentialCatalog} 的定时兜底刷新收敛。
 *
 * @param appNo 变化涉及的应用编号（仅用于日志）
 * @param reason 变化原因（停用/启用/来路增删等）
 */
public record AppCredentialChangedEvent(String appNo, String reason) {

    public static AppCredentialChangedEvent created(String appNo) {
        return new AppCredentialChangedEvent(appNo, "created");
    }

    public static AppCredentialChangedEvent enabled(String appNo, int target) {
        return new AppCredentialChangedEvent(appNo, target == 1 ? "enabled" : "disabled");
    }

    public static AppCredentialChangedEvent originChanged(String appNo, String action) {
        return new AppCredentialChangedEvent(appNo, "origin-" + action);
    }
}
