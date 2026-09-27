package com.apigw.proxy.auth.user;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网关自签的用户登录令牌：紧凑序列化 JWT（HS256），只用 JDK 自带的 HMAC-SHA256 实现，
 * 不引第三方 JWT 库。令牌由我们自己签发、自己验，算法固定，没有「协商 alg」的余地。
 *
 * 结构：base64url(header) . base64url(payload) . base64url(HMACSHA256(header.payload, secret))
 * - header 固定 {@code {"alg":"HS256","typ":"JWT"}}；
 * - payload 至少含 {@code sub}（用户标识）、{@code tenant}（租户标识）、
 *   {@code exp}（过期时刻，epoch 秒）、{@code iat}（签发时刻，epoch 秒）。
 *
 * 验签是「真验签」，不是把令牌拆开看一眼字段：
 * 1. 必须恰好三段、每段都是合法 base64url；
 * 2. header 解出来必须是对象，且 alg 严格等于 HS256、typ 严格等于 JWT——
 *    {@code alg:none}、换成别的算法、塞「永不过期」之类的私字段统统不认，
 *    杜绝「改头部冒充无签名」「用公钥当 HMAC 密钥」这类经典伪造；
 * 3. 用配置密钥对「前两段的原始字节」重算 HMAC-SHA256，与第三段常量时间逐字节比较，
 *    差一个 bit 都拒；
 * 4. exp 必须存在且是整数秒；当前时刻 {@code now >= exp} 即过期——
 *    <strong>正好压在过期那一刻也算过期</strong>，没有任何宽限钟；
 * 5. sub / tenant 必须是非空字符串（其余字段不关心，也不影响判定）。
 *
 * 任何一样不过，统一抛 {@link InvalidTokenException}：错误原因只留在服务端日志，
 * 不外抛给调用方（过滤器统一回同一种 401 文案）。
 */
public final class JwtUserToken {

    static final String ALG = "HS256";
    static final String TYP = "JWT";
    static final String CLAIM_SUBJECT = "sub";
    static final String CLAIM_TENANT = "tenant";
    static final String CLAIM_EXPIRES_AT = "exp";
    static final String CLAIM_ISSUED_AT = "iat";

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private JwtUserToken() {
    }

    /** 验签通过的令牌内容：用户标识、租户标识、过期时刻（都是验出来的可信值）。 */
    public record Principal(String userId, String tenantId, Instant expiresAt) {
    }

    /** 令牌签发器：网关侧（或与网关共享密钥的签发方）用它造令牌。 */
    public static final class Signer {

        private final byte[] secret;
        private final Clock clock;
        private final ObjectMapper mapper;

        public Signer(String secret, Clock clock) {
            this.secret = secret.getBytes(StandardCharsets.UTF_8);
            this.clock = clock;
            this.mapper = new ObjectMapper();
        }

        /** 签发一张 ttl 后过期的令牌。 */
        public String issue(String userId, String tenantId, Duration ttl) {
            Instant now = Instant.now(clock);
            long nowSeconds = now.getEpochSecond();
            long expSeconds = now.plus(ttl).getEpochSecond();
            return issue(userId, tenantId, nowSeconds, expSeconds);
        }

        /** 按给定 iat/exp（epoch 秒）签发，主要留给测试精确构造过期边界。 */
        public String issue(String userId, String tenantId, long iatEpochSecond, long expEpochSecond) {
            if (userId == null || userId.isBlank() || tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException("用户标识与租户标识都不能为空");
            }
            try {
                String header = encode(mapper.writeValueAsBytes(Map.of("alg", ALG, "typ", TYP)));
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put(CLAIM_SUBJECT, userId);
                payload.put(CLAIM_TENANT, tenantId);
                payload.put(CLAIM_ISSUED_AT, iatEpochSecond);
                payload.put(CLAIM_EXPIRES_AT, expEpochSecond);
                String body = header + "." + encode(mapper.writeValueAsBytes(payload));
                return body + "." + encode(hmac(body.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                throw new IllegalStateException("令牌签发失败：" + e.getMessage(), e);
            }
        }

        private byte[] hmac(byte[] data) throws Exception {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(data);
        }
    }

    /** 令牌验签器：每笔请求调用一次 {@link #verify}，无状态、线程安全。 */
    public static final class Verifier {

        private final byte[] secret;
        private final Clock clock;
        private final ObjectMapper mapper;

        public Verifier(String secret, Clock clock) {
            this.secret = secret.getBytes(StandardCharsets.UTF_8);
            this.clock = clock;
            this.mapper = new ObjectMapper();
        }

        /**
         * 完整校验。任何格式/签名/时间/声明问题都抛 {@link InvalidTokenException}，
         * 不区分细节对外，只在异常 message 里留服务端排障线索（不外抛）。
         */
        public Principal verify(String compact) {
            if (compact == null || compact.isBlank()) {
                throw new InvalidTokenException("EMPTY", "令牌为空");
            }
            // 只认恰好三段：多段、少段、空段都是伪造/损坏
            String[] parts = compact.split("\\.", -1);
            if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
                throw new InvalidTokenException("BAD_SEGMENTS", "令牌段数不对");
            }

            byte[] headerBytes = decodeSegment(parts[0], "header");
            byte[] payloadBytes = decodeSegment(parts[1], "payload");
            byte[] signature = decodeSegment(parts[2], "signature");

            // 1) 头部必须钉死 alg=HS256、typ=JWT：不接受 alg 协商，不接受 none
            Map<?, ?> header = readJson(headerBytes, "header");
            if (!ALG.equals(header.get("alg")) || !TYP.equals(header.get("typ"))) {
                throw new InvalidTokenException("BAD_HEADER", "令牌头部 alg/typ 不被接受");
            }

            // 2) 真验签：重算 HMAC 并常量时间比对（签名输入是前两段的原始 ASCII 字节）
            byte[] expected = hmac((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(expected, signature)) {
                throw new InvalidTokenException("BAD_SIGNATURE", "令牌签名不匹配");
            }

            // 3) 签名过了才看载荷（伪造载荷必然过不了上一步）
            Map<?, ?> payload = readJson(payloadBytes, "payload");

            Object expRaw = payload.get(CLAIM_EXPIRES_AT);
            if (!(expRaw instanceof Number exp)) {
                throw new InvalidTokenException("BAD_EXP", "令牌缺少数值型 exp");
            }
            long expSeconds = exp.longValue();
            // 边界卡死：now == exp 即过期；不引入任何 leeway（宽限），过期后一秒都不能用
            long nowSeconds = Instant.now(clock).getEpochSecond();
            if (nowSeconds >= expSeconds) {
                throw new InvalidTokenException("EXPIRED", "令牌已过期");
            }

            String userId = requiredString(payload, CLAIM_SUBJECT);
            String tenantId = requiredString(payload, CLAIM_TENANT);
            return new Principal(userId, tenantId, Instant.ofEpochSecond(expSeconds));
        }

        private String requiredString(Map<?, ?> payload, String claim) {
            Object v = payload.get(claim);
            if (!(v instanceof String s) || s.isBlank()) {
                throw new InvalidTokenException("MISSING_CLAIM:"+claim, "令牌缺少必填声明：" + claim);
            }
            return s;
        }

        private Map<?, ?> readJson(byte[] bytes, String what) {
            try {
                Map<?, ?> map = mapper.readValue(bytes, Map.class);
                if (map == null) {
                    throw new InvalidTokenException("BAD_JSON", "令牌" + what + "不是 JSON 对象");
                }
                return map;
            } catch (InvalidTokenException e) {
                throw e;
            } catch (Exception e) {
                throw new InvalidTokenException("BAD_JSON", "令牌" + what + "不是合法 JSON");
            }
        }

        private byte[] decodeSegment(String segment, String what) {
            try {
                return URL_DECODER.decode(segment);
            } catch (IllegalArgumentException e) {
                throw new InvalidTokenException("BAD_BASE64", "令牌" + what + "段不是合法 base64url");
            }
        }

        private byte[] hmac(byte[] data) {
            try {
                Mac mac = Mac.getInstance(HMAC_ALGORITHM);
                mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
                return mac.doFinal(data);
            } catch (Exception e) {
                throw new IllegalStateException("HMAC 计算失败（网关侧配置问题）", e);
            }
        }
    }

    static String encode(byte[] bytes) {
        return URL_ENCODER.encodeToString(bytes);
    }
}
