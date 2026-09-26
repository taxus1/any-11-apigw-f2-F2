package com.apigw.interfaces.rest.app.vo;

import java.io.Serializable;

/**
 * 新建应用请求体。
 *
 * @param appNo           应用编号（业务唯一、建后不可改）
 * @param appName         应用名称
 * @param secretExpiresAt 密钥有效期截止时刻，ISO-8601；null/不传 = 长期有效
 * @param enabled         状态：1 启用（默认）/ 0 停用
 * @param contact         联系人
 * @param remark          备注
 */
public record AppCreateVO(String appNo,
                          String appName,
                          String secretExpiresAt,
                          Integer enabled,
                          String contact,
                          String remark) implements Serializable {
}
