package com.apigw.interfaces.rest.app.vo;

import java.io.Serializable;

/** 来路名单增删请求体。ip 必须是单个 IPv4/IPv6 字面量（不收主机名、网段）。 */
public record OriginIpVO(String ip) implements Serializable {
}
