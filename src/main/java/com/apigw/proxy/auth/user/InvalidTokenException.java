package com.apigw.proxy.auth.user;

/**
 * 令牌校验失败。统一一种异常，不细分「签名错 / 过期 / 缺声明」对外暴露，
 * 过滤器对所有失败都回同一种 401 固定文案；细节只写服务端日志。
 */
public class InvalidTokenException extends RuntimeException {

    private final String code;

    public InvalidTokenException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
