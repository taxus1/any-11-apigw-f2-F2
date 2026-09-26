package com.apigw.domain.accesslog;

import java.util.List;

/**
 * 流水仓储端口：批量落库 + 条件分页查询。
 *
 * 实现侧（JDBC）必须保证：
 * - saveBatch 在单个事务里 executeBatch，整批成功或整批失败，不允许「半条记录/半批可见」；
 * - page 的总数与当前页数据在同一只读事务里取，二者严格对同一筛选口径，
 *   不能出现 total 与 content 对不上。
 */
public interface AccessLogRepository {

    /**
     * 整批写入。失败时抛异常，由异步写线程兜住记日志（调用方不是请求线程，且失败不影响转发）。
     */
    void saveBatch(List<AccessLogEntry> entries);

    /** 条件分页查询；offset 从 0 开始，limit 为每页条数（已由上层封顶）。 */
    AccessLogPage page(AccessLogQuery query, long offset, int limit);

    /** 一页结果：当前页内容 + 满足筛选条件的总条数（totalPages 由上层 PageResult 统一算）。 */
    record AccessLogPage(List<AccessLogEntry> content, long total) {
    }
}
