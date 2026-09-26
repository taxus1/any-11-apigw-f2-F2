-- ============================================================================
-- 第三方接入：应用凭据表 + 来路名单表
--
-- 安全口径（务必和代码一起看，别只看表）：
-- 1. secret_hash 存的是密钥的「不可逆」散列（PBKDF2-HMAC-SHA256，每应用独立随机盐、
--    高迭代），原始密钥只在新建成功的响应里回显一次，库里、日志里、后台任何接口都
--    不再出现原密钥——谁（包括我们自己）都无法从库里把原密钥捞回来。
-- 2. app_no 业务唯一：靠 uk_app_no 唯一索引兜底，并发重复建号由数据库直接拒绝，
--    不是「先 SELECT 再 INSERT」那种有并发窗口的查法。
-- 3. enabled 只取 0/1；secret_expires_at 为 NULL 表示长期有效，非 NULL 时过期即拒。
-- 4. 来路名单（gw_app_origin）空 = 不限制来源；有行则严格按 IP 字面量精确匹配，
--    不做任何前缀/网段匹配（192.168.1.1 绝不匹配 192.168.1.10）。
--    存的是规范化后的 IP 字面量（IPv6 压缩/大小写差异在入库前归一），
--    uk_app_ip 让同一条来路地址天然不重复、删除也精确定位。
-- 5. 不给来路表加数据库外键：应用不做物理删除（只有停用），名单行不会变孤儿；
--    应用层在同一事务里保证引用一致即可。
-- ============================================================================
CREATE TABLE IF NOT EXISTS `gw_app` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键（不作为业务编号）',
    `app_no`            VARCHAR(64)  NOT NULL COMMENT '应用编号：业务唯一，建后不可改；调用方以 X-App-No 上送',
    `app_name`          VARCHAR(128) NOT NULL COMMENT '应用名称（可用于列表展示与模糊查找）',
    `secret_hash`       VARCHAR(256) NOT NULL COMMENT '密钥的不可逆散列（PBKDF2 带随机盐），原始密钥不落库',
    `secret_expires_at` DATETIME(3)  DEFAULT NULL COMMENT '密钥有效期截止时刻；NULL=长期有效，非 NULL 过期即拒',
    `enabled`           TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1 启用，0 停用；停用后其凭据立刻失效',
    `contact`           VARCHAR(128) DEFAULT NULL COMMENT '联系人',
    `remark`            VARCHAR(512) DEFAULT NULL COMMENT '备注',
    `created_by`        VARCHAR(128) DEFAULT NULL COMMENT '创建人',
    `created_at`        DATETIME(3)  NOT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    -- 业务编号唯一：并发建同号由唯一索引直接拒一个（停用的也占号，因为不做物理删除）
    UNIQUE KEY `uk_app_no` (`app_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='接入网关的第三方应用凭据（密钥仅存散列）';

CREATE TABLE IF NOT EXISTS `gw_app_origin` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `app_no`     VARCHAR(64) NOT NULL COMMENT '所属应用编号（gw_app.app_no）',
    `ip`         VARCHAR(64) NOT NULL COMMENT '来路地址（规范化 IPv4/IPv6 字面量），严格精确匹配',
    `created_at` DATETIME(3) NOT NULL COMMENT '加入名单时间',
    PRIMARY KEY (`id`),
    -- 同一应用下同一条地址只许有一行：加入幂等、删除精确定位
    UNIQUE KEY `uk_app_ip` (`app_no`, `ip`),
    -- 按应用列出/统计名单走索引
    KEY `idx_app_no` (`app_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='应用来路名单（空=不限制来源；非空则精确放行）';
