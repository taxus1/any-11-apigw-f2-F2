package com.apigw.domain.app;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 第三方应用 + 来路名单的持久化端口（接口在领域层，JDBC 实现在基础设施层）。
 *
 * 写口径：
 * - {@link #insert} 把应用主记录作为一个事务落库；应用编号唯一索引兜底并发重复建号，
 *   抛 {@link DuplicateAppNoException}；
 * - 状态切换与来路名单增删都是「存在性校验 + 写入」在**同一事务**里完成；
 * - 来路名单增删做成幂等：重复加/重复删不报错（靠 (app_no, ip) 唯一键 + 影响行数）。
 *
 * 读口径：
 * - 分页的 count 与取数在**同一只读事务**，总数与本页严格对得上；
 * - {@link #loadAllForAuth()} 给转发鉴权的内存快照用：应用 + 名单一次读齐，
 *   两份数据在同一事务视图里，不会出现「应用状态是新的、名单还是旧的」。
 */
public interface AppCredentialRepository {

    /** 列表行：应用摘要 + 来路条数（SQL 一次带出，前端不用逐行再查）。密钥散列不进列表。 */
    record AppRow(Long id,
                  String appNo,
                  String appName,
                  Instant secretExpiresAt,
                  int enabled,
                  String contact,
                  String remark,
                  String createdBy,
                  Instant createdAt,
                  int originCount) {
    }

    /** 分页结果：本页数据 + 筛选口径内的真实总数。 */
    record AppPage(List<AppRow> content, long total) {
    }

    /** 鉴权快照里的一个应用：散列、有效期、状态、来路名单一次带齐。 */
    record AuthApp(String appNo,
                   String secretHash,
                   Instant secretExpiresAt,
                   boolean enabled,
                   List<String> origins) {
    }

    /** 新建应用主记录；编号被唯一索引拒绝时抛 {@link DuplicateAppNoException}。 */
    void insert(ClientApp app);

    Optional<ClientApp> findByAppNo(String appNo);

    /**
     * 分页列表。keyword 非空时在 app_no / app_name 上做忽略大小写的包含匹配；
     * 按 app_no 稳定排序。count 与取数在同一只读事务内。
     */
    AppPage page(String keyword, long offset, int limit);

    /** 把启用状态更新成 targetEnabled；返回受影响行数（0=应用不存在）。幂等：已是目标态也算成功，由上层判存在。 */
    int updateEnabled(String appNo, int targetEnabled);

    /** 幂等加入一条来路（已存在不报错、不重复插）；应用不存在抛业务异常。 */
    void addOrigin(String appNo, String canonicalIp, Instant now);

    /** 幂等删除一条来路（本来就没有也算成功）；应用不存在抛业务异常。 */
    void removeOrigin(String appNo, String canonicalIp);

    /** 读全部应用及其来路名单（同一事务视图），供鉴权内存快照。 */
    List<AuthApp> loadAllForAuth();
}
