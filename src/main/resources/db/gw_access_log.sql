-- ============================================================================
-- 网关访问流水表（一笔请求一行）
--
-- 说明：
-- 1. 状态码 status_code 的取值口径：记「最终回给调用方的 HTTP 状态码」。
--    - 正常转发：上游返回的状态码原样透传（含上游的 4xx/5xx）；
--    - 网关合成错误：NO_ROUTE=404、UPSTREAM_UNAVAILABLE=502、UPSTREAM_TIMEOUT=504、
--      CONFIG_UNAVAILABLE=503——上游失败/超时/没连上这些「拿不到上游状态码」的请求
--      也照样留痕，填的是网关实际回给调用方的那个码；
--    - 响应在写出状态行之前就中断、一个码都没产出的极端情况填 0（语义=未产生状态码），
--      绝不留 NULL，保证按状态码筛选不漏行。
-- 2. 应用编号 app_no 认不出来时为 NULL（不是空串）。
-- 3. 引擎 InnoDB：批量写入需要在单个事务里 executeBatch，要么整批可见、要么整批回滚。
-- ============================================================================
CREATE TABLE IF NOT EXISTS `gw_access_log` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键（不作为业务编号）',
    `request_id`  VARCHAR(64)  NOT NULL COMMENT '请求编号：优先沿用调用方 X-Trace-Id，没带则网关生成',
    `route_no`    VARCHAR(128) DEFAULT NULL COMMENT '命中的路由编号；未匹配到路由为 NULL',
    `app_no`      VARCHAR(64)  DEFAULT NULL COMMENT '调进来的应用编号（X-App-No），认不出来为 NULL',
    `client_ip`   VARCHAR(64)  NOT NULL COMMENT '客户端来源地址，取值顺序与鉴权/限流同一口径',
    `method`      VARCHAR(10)  NOT NULL COMMENT '请求方法',
    `path`        VARCHAR(2048) NOT NULL COMMENT '请求路径（应用内路径，不含 host）',
    `status_code` INT          NOT NULL COMMENT '最终回给调用方的状态码；一个码都没产出填 0',
    `elapsed_ms`  BIGINT       NOT NULL COMMENT '请求总耗时（毫秒），进来到响应出去',
    `occurred_at` DATETIME(3)  NOT NULL COMMENT '发生时间（请求到达网关的时刻，毫秒精度）',
    PRIMARY KEY (`id`),
    -- 查账最常见的形态：按时间段翻（可再叠路由/状态）。occurred_at 单列索引让范围扫描走索引，
    -- id 作为同一毫秒内的稳定次序，分页 ORDER BY occurred_at, id 与索引顺序一致，不会翻页串行/丢行
    KEY `idx_occurred_at` (`occurred_at`, `id`),
    -- 按路由编号 + 时间段组合：等值列在前、范围列在后，route_no 等值定位后再在索引内做时间范围
    KEY `idx_route_time` (`route_no`, `occurred_at`),
    -- 按状态码 + 时间段组合：同上，专查「某段时间的 5xx / 502 / 504」
    KEY `idx_status_time` (`status_code`, `occurred_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='网关访问流水（一笔请求一行）';
