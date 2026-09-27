package com.apigw.common.time;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 全系统统一时钟：常驻装配（不依赖任何功能开关），生产走系统 UTC 时钟；
 * 需要验证过期边界的测试可自行 new 固定 Clock 传入构造器，或用测试配置覆盖本 bean。
 *
 * 提成常驻 bean 是为了避免「接入鉴权」与「用户登录鉴权」两个条件装配各自定义
 * 同名 Clock 而在同时开启时冲突。
 */
@Configuration
public class GatewayTimeConfig {

    @Bean
    Clock gatewayClock() {
        return Clock.systemUTC();
    }
}
