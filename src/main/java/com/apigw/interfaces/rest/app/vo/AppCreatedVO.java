package com.apigw.interfaces.rest.app.vo;

import java.io.Serializable;

/**
 * 创建应用的响应：应用详情 + **一次性明文密钥**。
 *
 * 这是原密钥在整个系统里唯一一次露面。响应体里直接强调：请对方立即妥善保存，
 * 网关侧不留明文、也无法再次查看；丢了只能联系网关重新发。
 */
public record AppCreatedVO(AppVO app,
                           String secret,
                           String secretNotice) implements Serializable {
}
