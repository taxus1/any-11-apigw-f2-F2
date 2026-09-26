package com.apigw.domain.app;

import com.apigw.common.exception.BizException;
import com.apigw.common.web.ClientIpResolver;
import lombok.Getter;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 聚合根：一个接入网关的第三方应用（凭据 + 状态 + 来路名单）。
 *
 * 聚合守住的不变量：
 * 1. {@code appNo} 非空、只含字母数字与 {@code . _ -}、最长 64，且建后不可改
 *    （与路由编号、{@code X-App-No} 头的白名单同一套字符集）；
 * 2. {@code appName} 非空、最长 128；
 * 3. {@code enabled} 只有 0/1；
 * 4. {@code secretHash} 永远是 {@link SecretHasher} 生成的散列，明文密钥不进聚合的持久态；
 * 5. {@code secretExpiresAt} 为空=长期有效，非空则「到点即失效」（有效期截止时刻本身也算过期）；
 * 6. 来路地址必须是合法 IPv4/IPv6 字面量，入库即按 {@link ClientIpResolver#canonicalize} 归一；
 *    名单是 Set，天然不重；名单为空语义是「不限制来源」。
 *
 * 鉴权判定 {@link #credentialAllows} 也放在聚合上：状态、有效期、来路三件事的口径
 * 只有这一处，过滤器和任何以后的调用方都不允许再各写一份。
 */
@Getter
public class ClientApp {

    /** 应用编号：与 GatewayHeaders 的 X-App-No 白名单一致。 */
    private static final Pattern APP_NO_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /** 单个应用来路名单上限：防止管理误操作灌出超大集合拖垮每请求比对。 */
    public static final int MAX_ORIGINS = 200;

    private Long id;
    private String appNo;    private String appName;
    private String secretHash;
    private Instant secretExpiresAt;
    private int enabled;
    private String contact;
    private String remark;
    private String createdBy;
    private Instant createdAt;

    /** 来路名单：存规范化 IP 字面量；空集合 = 不限制来源。 */
    private final Set<String> origins = new LinkedHashSet<>();

    /**
     * 仓储重建：字段全部来自持久态。散列原样带回（不再哈希），有效期不做「必须在未来」校验
     * （存着的本来就可能已过期），来路名单按已归一的字面量直接装入。
     */
    public static ClientApp reconstitute(Long id, String appNo, String appName, String secretHash,
                                         Instant secretExpiresAt, int enabled,
                                         String contact, String remark, String createdBy,
                                         Instant createdAt, List<String> origins) {
        ClientApp app = new ClientApp();
        app.id = id;
        // 编号已存在且传入同号，assignAppNo 不会拦；格式仍过一遍防止脏数据进内存
        app.assignAppNo(appNo);
        if (secretHash == null || secretHash.isBlank()) {
            throw new IllegalStateException("应用 " + appNo + " 的密钥散列缺失，数据不完整");
        }
        app.secretHash = secretHash;
        app.appName = appName;
        app.secretExpiresAt = secretExpiresAt;
        app.changeEnabled(enabled);
        app.contact = contact;
        app.remark = remark;
        app.createdBy = createdBy;
        app.createdAt = createdAt;
        if (origins != null) {
            app.origins.addAll(origins);
        }
        return app;
    }

    /**
     * 新建应用的工厂。密钥在应用层生成明文、在这里散列；明文绝不保存，
     * 由应用层在创建响应里回显仅此一次。
     */
    public static ClientApp create(String appNo, String appName, String rawSecret,
                                   Instant secretExpiresAt, Integer enabled,
                                   String contact, String remark, String createdBy,
                                   Clock clock) {
        ClientApp app = new ClientApp();
        app.assignAppNo(appNo);
        app.rename(appName);
        app.resetSecret(rawSecret);
        app.assignExpiry(secretExpiresAt, clock);
        app.changeEnabled(enabled);
        app.changeContact(contact);
        app.changeRemark(remark);
        app.changeCreatedBy(createdBy);
        app.createdAt = Instant.now(clock);
        return app;
    }

    /** 建号：非空、格式合法；建后不可改（仓储重建时也走这里，传入同号不会被拦）。 */
    public void assignAppNo(String appNo) {
        if (appNo == null || appNo.isBlank()) {
            throw new BizException("应用编号不能为空");
        }
        String v = appNo.trim();
        if (!APP_NO_PATTERN.matcher(v).matches()) {
            throw new BizException("应用编号只能包含字母、数字、点、下划线、短横，最长 64 位");
        }
        if (this.appNo != null && !this.appNo.equals(v)) {
            throw new BizException("应用编号建后不可修改（现有编号 " + this.appNo + "，不能改成 " + v + "）");
        }
        this.appNo = v;
    }

    /** 回填数据库生成的自增主键（仅供仓储 insert 后调用）。 */
    public void assignId(Long id) {
        this.id = id;
    }

    public void rename(String appName) {
        if (appName == null || appName.isBlank()) {
            throw new BizException("应用名称不能为空");
        }
        String v = appName.trim();
        if (v.length() > 128) {
            throw new BizException("应用名称最长 128 位");
        }
        this.appName = v;
    }

    /** 换发密钥：传入新明文，立即散列覆盖。原明文无法找回，符合「只发一次」约定。 */
    public void resetSecret(String rawSecret) {
        if (rawSecret == null || rawSecret.isBlank()) {
            throw new BizException("密钥不能为空");
        }
        String v = rawSecret.trim();
        // 密钥是网关生成的高熵随机串，这里只做兜底长度检查
        if (v.length() < 16 || v.length() > 128) {
            throw new BizException("密钥长度需在 16~128 位之间");
        }
        this.secretHash = SecretHasher.hash(v);
    }

    /** 配置有效期：null=长期有效；非空必须是未来时刻，建一个「出生即过期」的密钥没有意义。 */
    public void assignExpiry(Instant expiresAt, Clock clock) {
        if (expiresAt != null && !expiresAt.isAfter(Instant.now(clock))) {
            throw new BizException("密钥有效期必须是一个未来时刻");
        }
        this.secretExpiresAt = expiresAt;
    }

    /** 1 启用 / 0 停用，别的值不收。 */
    public void changeEnabled(Integer enabled) {
        if (enabled == null) {
            this.enabled = 1;
            return;
        }
        if (enabled != 0 && enabled != 1) {
            throw new BizException("状态只能是 0（停用）或 1（启用），收到的是：" + enabled);
        }
        this.enabled = enabled;
    }

    public void changeContact(String contact) {
        this.contact = trimOrNull(contact, 128, "联系人");
    }

    public void changeRemark(String remark) {
        this.remark = trimOrNull(remark, 512, "备注");
    }

    public void changeCreatedBy(String createdBy) {
        this.createdBy = trimOrNull(createdBy, 128, "创建人");
    }

    private static String trimOrNull(String value, int maxLen, String label) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return null;
        }
        if (v.length() > maxLen) {
            throw new BizException(label + "最长 " + maxLen + " 位");
        }
        return v;
    }

    // ---- 来路名单（聚合内的一致性：只能经下面的方法改）----

    /**
     * 加入一条来路地址。地址必须合法，统一归一再入集合——
     * IPv6 的大小写/压缩差异视为同一条，非法字面量直接拒（主机名、网段、CIDR 都不收）。
     *
     * @return 归一后的地址；{@code added=false} 表示名单里已有同地址（加入天然幂等）
     */
    public OriginChange addOrigin(String rawIp) {
        String ip = normalizeOrigin(rawIp);
        if (origins.size() >= MAX_ORIGINS && !origins.contains(ip)) {
            throw new BizException("单个应用来路名单最多 " + MAX_ORIGINS + " 条");
        }
        boolean added = origins.add(ip);
        return new OriginChange(ip, added);
    }

    /**
     * 删除一条来路地址。
     *
     * @return 归一后的地址；{@code removed=false} 表示本来就没有（删除天然幂等）
     */
    public OriginChange removeOrigin(String rawIp) {
        String ip = normalizeOrigin(rawIp);
        boolean removed = origins.remove(ip);
        return new OriginChange(ip, removed);
    }

    /** 来路变更结果：规范化地址 + 是否真的发生了增/删（幂等判定靠它）。 */
    public record OriginChange(String canonicalIp, boolean changed) {
    }

    /**
     * 仓储装载已归一的地址：库里存的就是规范化结果，不能再过一遍校验把数据拒之门外。
     * 仅供持久层重建聚合使用。
     */
    public void addOriginPristine(String canonicalIp) {
        if (canonicalIp != null && !canonicalIp.isBlank()) {
            origins.add(canonicalIp.trim());
        }
    }

    /** 名单内容，按字典序稳定输出，方便前端展示与测试断言。 */
    public List<String> sortedOrigins() {
        return origins.stream().sorted(Comparator.naturalOrder()).toList();
    }

    /** 校验 + 归一来路地址：只接受 IP 字面量，绝不收主机名或 {@code 1.2.3.0/24} 这种网段。 */
    private static String normalizeOrigin(String rawIp) {
        if (rawIp == null || rawIp.isBlank()) {
            throw new BizException("来路地址不能为空");
        }
        String v = rawIp.trim();
        if (v.indexOf('/') >= 0) {
            throw new BizException("来路地址只收单个 IP 字面量，不收网段/CIDR：" + v);
        }
        String canonical = ClientIpResolver.canonicalize(v);
        if (canonical == null) {
            throw new BizException("来路地址不是合法的 IPv4/IPv6 字面量（不收主机名）：" + v);
        }
        return canonical;
    }

    // ---- 鉴权判定（全项目唯一口径）----

    /**
     * 当次请求的凭据是否放行。
     *
     * @param rawSecret 调用方上送的密钥明文（X-App-Secret）
     * @param clientIp  按 {@link ClientIpResolver} 取到的来源地址（与流水/限流同一口径）
     * @param now       当前时刻（便于测试过期边界）
     * @return 非 null=拒绝原因；null=放行
     */
    public Rejection credentialAllows(String rawSecret, String clientIp, Instant now) {
        if (enabled != 1) {
            return Rejection.DISABLED;
        }
        if (secretExpiresAt != null && !now.isBefore(secretExpiresAt)) {
            return Rejection.SECRET_EXPIRED;
        }
        if (!SecretHasher.matches(rawSecret, secretHash)) {
            return Rejection.BAD_SECRET;
        }
        // 名单为空 = 不限制；非空则严格精确匹配（双方都已规范化，不存在前缀误伤）
        if (!origins.isEmpty() && (clientIp == null || !origins.contains(clientIp))) {
            return Rejection.ORIGIN_FORBIDDEN;
        }
        return null;
    }

    /** 拒绝原因：映射到过滤器对外的错误码与文案，内部状态不外泄。 */
    public enum Rejection {
        DISABLED,
        SECRET_EXPIRED,
        BAD_SECRET,
        ORIGIN_FORBIDDEN
    }
}
