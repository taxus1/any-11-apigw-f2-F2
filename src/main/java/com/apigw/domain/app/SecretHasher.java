package com.apigw.domain.app;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 应用密钥的散列与校验。安全口径：
 *
 * 1. **只存不可逆散列**：库里 {@code secret_hash} 存的是 PBKDF2-HMAC-SHA256
 *    （每应用独立随机盐、高迭代次数）的结果，原始密钥只在新建成功时回显一次，
 *    此后谁（含我们自己后台、DBA）都无法从库里还原出原密钥，也没有「查看原密钥」口子。
 * 2. 为什么不用带盐 SHA-256 一次哈希：密钥是高熵随机串，但 PBKDF2 的逐应用随机盐 +
 *    数万次迭代仍显著抬高「拖库后离线爆破」的成本，是 JDK 自带、无需额外依赖的标准选择。
 * 3. 常量时间比对：{@link #matches} 用 {@link MessageDigest#isEqual}，
 *    避免按字节早退的比较泄露密钥前缀信息。
 *
 * 存储格式（自描述，便于以后调参/换算法而不与旧数据打架）：
 * <pre>
 *   pbkdf2$&lt;迭代次数&gt;$&lt;base64url 盐&gt;$&lt;base64url 散列&gt;
 * </pre>
 */
public final class SecretHasher {

    /** 算法标识，写在散列串最前面，将来升级算法时能区分新旧数据。 */
    public static final String ALGO = "pbkdf2";
    public static final int ITERATIONS = 120_000;
    /** PBKDF2 输出位数与盐长度；盐长 16 字节已足够保证各应用散列不撞。 */
    public static final int KEY_BITS = 256;
    public static final int SALT_BYTES = 16;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DEC = Base64.getUrlDecoder();

    private SecretHasher() {
    }

    /** 为一个明文密钥生成自描述散列串（含新随机盐）。 */
    public static String hash(String rawSecret) {
        if (rawSecret == null || rawSecret.isEmpty()) {
            throw new IllegalArgumentException("密钥不能为空");
        }
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] dk = pbkdf2(rawSecret.toCharArray(), salt, ITERATIONS);
        return ALGO + "$" + ITERATIONS + "$" + B64.encodeToString(salt) + "$" + B64.encodeToString(dk);
    }

    /**
     * 校验明文密钥与散列串是否匹配。按散列串里记录的迭代次数/盐重新计算后常量时间比对；
     * 散列串格式损坏时一律视为不匹配（绝不抛出把内部细节带出去）。
     */
    public static boolean matches(String rawSecret, String stored) {
        if (rawSecret == null || stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$", -1);
        if (parts.length != 4 || !ALGO.equals(parts[0])) {
            return false;
        }
        final int iterations;
        final byte[] salt;
        final byte[] expected;
        try {
            iterations = Integer.parseInt(parts[1]);
            salt = B64_DEC.decode(parts[2]);
            expected = B64_DEC.decode(parts[3]);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (iterations <= 0 || salt.length == 0 || expected.length == 0) {
            return false;
        }
        byte[] actual = pbkdf2(rawSecret.toCharArray(), salt, iterations);
        return MessageDigest.isEqual(actual, expected);
    }

    private static byte[] pbkdf2(char[] secret, byte[] salt, int iterations) {
        try {
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            PBEKeySpec spec = new PBEKeySpec(secret, salt, iterations, KEY_BITS);
            try {
                return skf.generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (Exception e) {
            // JDK 标配算法，正常不会缺失；缺失属于平台级故障，直接炸出来而不是放过校验
            throw new IllegalStateException("当前 JDK 不支持 PBKDF2WithHmacSHA256", e);
        }
    }
}
