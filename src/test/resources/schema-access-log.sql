-- H2（MODE=MySQL）下的访问流水表，结构与 src/main/resources/db/gw_access_log.sql 对齐，
-- 用于仓储/查询的真实 SQL 集成测试，不依赖外部 MySQL。
CREATE TABLE IF NOT EXISTS gw_access_log (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    request_id   VARCHAR(64)  NOT NULL,
    route_no     VARCHAR(128) DEFAULT NULL,
    app_no       VARCHAR(64)  DEFAULT NULL,
    client_ip    VARCHAR(64)  NOT NULL,
    method       VARCHAR(10)  NOT NULL,
    path         VARCHAR(2048) NOT NULL,
    status_code  INT          NOT NULL,
    elapsed_ms   BIGINT       NOT NULL,
    occurred_at  TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_occurred_at (occurred_at, id),
    KEY idx_route_time (route_no, occurred_at),
    KEY idx_status_time (status_code, occurred_at)
);
