package com.apigw.interfaces.rest.app.vo;

import java.io.Serializable;
import java.util.List;

/**
 * 来路名单视图：应用编号 + 当前名单（规范化 IP，稳定排序）。
 * 名单为空数组即语义「不限制任何来源」。
 */
public record OriginListVO(String appNo, int count, boolean restrict, List<String> origins)
        implements Serializable {

    public static OriginListVO of(String appNo, List<String> origins) {
        // restrict=true 表示「只允许名单内来源」；空名单 restrict=false 表示不限
        return new OriginListVO(appNo, origins.size(), !origins.isEmpty(), List.copyOf(origins));
    }
}
