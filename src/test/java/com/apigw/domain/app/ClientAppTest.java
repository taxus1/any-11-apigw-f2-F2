package com.apigw.domain.app;

import com.apigw.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 应用聚合不变量与鉴权判定测试（无需库）：
 * - 编号规则与建后不可改；
 * - 密钥必须散列存储，明文不进散列字段；
 * - 有效期为空=长期有效，到期即拒（截止时刻本身算过期）；
 * - 来路名单：非法地址/主机名/网段被拒、IPv6 写法归并、空名单=不限、非空严格精确；
 * - 增删来路幂等；
 * - 停用/启用、错密钥、来路不在名单的拒绝分类。
 */
class ClientAppTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"), ZoneOffset.UTC);
    private final String secret = "abcdefghijklmnopqrstuvwxyz0123456789ABCD";

    private ClientApp newApp(Instant expiresAt, Integer enabled) {
        return ClientApp.create("app-01", "应用一", secret, expiresAt, enabled,
                "张三", "备注", "admin", clock);
    }

    @Test
    void create_hashesSecret_andKeepsMetadata() {
        ClientApp app = newApp(null, 1);
        assertThat(app.getAppNo()).isEqualTo("app-01");
        assertThat(app.getAppName()).isEqualTo("应用一");
        assertThat(app.getEnabled()).isEqualTo(1);
        assertThat(app.getCreatedBy()).isEqualTo("admin");
        assertThat(app.getCreatedAt()).isEqualTo(Instant.parse("2026-09-26T00:00:00Z"));
        // 散列字段里没有明文，且是 pbkdf2 自描述格式
        assertThat(app.getSecretHash()).startsWith("pbkdf2$").doesNotContain(secret);
    }

    @Test
    void appNo_isValidated_andImmutableAfterCreation() {
        ClientApp app = newApp(null, 1);
        assertThatThrownBy(() -> app.assignAppNo("app-02"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("建后不可修改");
        // 仓储重建传同号不拦
        app.assignAppNo("app-01");
        assertThatThrownBy(() -> ClientApp.create("bad no", "n", secret, null, 1,
                null, null, null, clock)).hasMessageContaining("应用编号");
    }

    @Test
    void expiry_nullMeansForever_pastValueRejected_expiredPointRejects() {
        assertThat(newApp(null, 1).credentialAllows(secret, "1.1.1.1",
                Instant.parse("2099-01-01T00:00:00Z"))).isNull();

        // 不能建一个出生即过期的密钥
        assertThatThrownBy(() -> newApp(Instant.parse("2026-09-25T00:00:00Z"), 1))
                .isInstanceOf(BizException.class);

        ClientApp app = newApp(Instant.parse("2026-10-01T00:00:00Z"), 1);
        // 截止前一刻放行
        assertThat(app.credentialAllows(secret, "1.1.1.1",
                Instant.parse("2026-09-30T23:59:59Z"))).isNull();
        // 到了截止时刻本身即过期
        assertThat(app.credentialAllows(secret, "1.1.1.1",
                Instant.parse("2026-10-01T00:00:00Z")))
                .isEqualTo(ClientApp.Rejection.SECRET_EXPIRED);
    }

    @Test
    void enabled_toggle_andBadSecret() {
        ClientApp app = newApp(null, 1);
        assertThat(app.credentialAllows(secret, "9.9.9.9", Instant.now(clock))).isNull();
        assertThat(app.credentialAllows("wrong-secret-00000000000000000", "9.9.9.9",
                Instant.now(clock))).isEqualTo(ClientApp.Rejection.BAD_SECRET);

        app.changeEnabled(0);
        // 停用最先判：即使密钥、来路都对也拒
        assertThat(app.credentialAllows(secret, "9.9.9.9", Instant.now(clock)))
                .isEqualTo(ClientApp.Rejection.DISABLED);
        app.changeEnabled(1);
        assertThat(app.credentialAllows(secret, "9.9.9.9", Instant.now(clock))).isNull();
    }

    @Test
    void emptyOriginList_meansAnySourceAllowed() {
        ClientApp app = newApp(null, 1);
        assertThat(app.getOrigins()).isEmpty();
        // 任意来源（含 IPv6、XFF 背后的地址）都放行
        assertThat(app.credentialAllows(secret, "203.0.113.77", Instant.now(clock))).isNull();
        assertThat(app.credentialAllows(secret, "2001:db8::77", Instant.now(clock))).isNull();
    }

    @Test
    void nonEmptyOriginList_strictExactMatch() {
        ClientApp app = newApp(null, 1);
        assertThat(app.addOrigin("192.168.1.1").changed()).isTrue();

        Instant now = Instant.now(clock);
        // 精确命中放行
        assertThat(app.credentialAllows(secret, "192.168.1.1", now)).isNull();
        // 关键：.10 不能被 .1 放进来
        assertThat(app.credentialAllows(secret, "192.168.1.10", now))
                .isEqualTo(ClientApp.Rejection.ORIGIN_FORBIDDEN);
        assertThat(app.credentialAllows(secret, "192.168.1.2", now))
                .isEqualTo(ClientApp.Rejection.ORIGIN_FORBIDDEN);
        // 来源地址解析不出（unknown 之类）时也不能放
        assertThat(app.credentialAllows(secret, null, now))
                .isEqualTo(ClientApp.Rejection.ORIGIN_FORBIDDEN);
    }

    @Test
    void originAddRemove_areIdempotent_andCanonicalizeIpv6() {
        ClientApp app = newApp(null, 1);
        // IPv6 大小写/压缩视为同一条：第二次加不算变更
        assertThat(app.addOrigin("2001:DB8::1").changed()).isTrue();
        assertThat(app.addOrigin("2001:db8:0:0:0:0:0:1").changed()).isFalse();
        assertThat(app.getOrigins()).containsExactly("2001:0db8:0000:0000:0000:0000:0000:0001");

        // 用另一种写法发起请求：过滤器会先 canonicalize，这里按同一口径传入规范值，命中名单
        assertThat(app.credentialAllows(secret,
                com.apigw.common.web.ClientIpResolver.canonicalize("2001:db8::1"),
                Instant.now(clock))).isNull();

        // 删除幂等：删一次 true，再删 false（过滤器/应用层传入的都是 canonicalize 后的规范串）
        String v6 = com.apigw.common.web.ClientIpResolver.canonicalize("2001:db8::1");
        assertThat(app.removeOrigin(v6).changed()).isTrue();
        assertThat(app.removeOrigin(v6).changed()).isFalse();
        assertThat(app.getOrigins()).isEmpty();
    }

    @Test
    void originValidation_rejectsHostnameCidrAndGarbage() {
        ClientApp app = newApp(null, 1);
        assertThatThrownBy(() -> app.addOrigin("order-svc")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> app.addOrigin("192.168.1.0/24")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> app.addOrigin("999.1.1.1")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> app.addOrigin("  ")).isInstanceOf(BizException.class);
    }

    @Test
    void rejectsInvalidStateValues() {
        ClientApp app = newApp(null, 1);
        assertThatThrownBy(() -> app.changeEnabled(9)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> ClientApp.create("a", "", secret, null, 1,
                null, null, null, clock)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> ClientApp.create("a", "n", "short", null, 1,
                null, null, null, clock)).isInstanceOf(BizException.class);
    }
}
