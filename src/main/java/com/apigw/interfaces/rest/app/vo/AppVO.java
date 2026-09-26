package com.apigw.interfaces.rest.app.vo;

import java.io.Serializable;
import java.time.Instant;

/**
 * 应用详情/创建响应视图。
 *
 * 注意：**绝不回传密钥散列或明文**。明文密钥只在 {@link AppCreatedVO} 里随创建结果出现一次，
 * 这个详情对象里没有任何密钥字段，后台不存在「查看原密钥」。
 */
public record AppVO(Long id,
                    String appNo,
                    String appName,
                    Instant secretExpiresAt,
                    boolean secretNeverExpires,
                    boolean secretExpired,
                    Integer enabled,
                    String contact,
                    String remark,
                    String createdBy,
                    Instant createdAt,
                    int originCount) implements Serializable {
}
