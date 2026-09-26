package com.apigw.domain.app;

/**
 * 应用编号重复：与「其他业务失败」分开，便于上层翻译成确定的占用提示而不是笼统系统异常。
 * 并发建同号最终由数据库唯一索引拒绝，表现成这个异常。
 */
public class DuplicateAppNoException extends RuntimeException {

    public DuplicateAppNoException(String appNo) {
        super("应用编号已被占用（停用的应用也占号）：" + appNo);
    }
}
