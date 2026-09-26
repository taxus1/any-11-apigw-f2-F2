package com.apigw.interfaces.rest.app.vo;

import com.apigw.domain.app.AppCredentialRepository;

import java.io.Serializable;
import java.time.Instant;

/**
 * 应用列表行：摘要 + 来路条数。服务端用 SQL 一次带出 originCount，前端不用逐行再查。
 */
public record AppRowVO(Long id,
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

    public static AppRowVO of(AppCredentialRepository.AppRow row, Instant now) {
        boolean expired = row.secretExpiresAt() != null && !now.isBefore(row.secretExpiresAt());
        return new AppRowVO(
                row.id(),
                row.appNo(),
                row.appName(),
                row.secretExpiresAt(),
                row.secretExpiresAt() == null,
                expired,
                row.enabled(),
                row.contact(),
                row.remark(),
                row.createdBy(),
                row.createdAt(),
                row.originCount());
    }
}
