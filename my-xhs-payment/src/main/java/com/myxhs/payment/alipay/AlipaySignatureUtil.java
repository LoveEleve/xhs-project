package com.myxhs.payment.alipay;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * 支付宝 RSA2 签名/验签工具
 * <p>
 * 依据支付宝官方"签名规则"实现（RSA2 = SHA256withRSA）：
 * <ol>
 *   <li>剔除 {@code sign} 与 {@code sign_type} 参数（官方验签规则明文规定两者均不参与）</li>
 *   <li>剔除<b>值为空</b>的参数（官方规则："参数值为空不参与签名"）</li>
 *   <li>参数名按 ASCII 码字典序排序，按 {@code k1=v1&k2=v2} 格式拼接为待签串</li>
 *   <li>SHA256withRSA 签名 → 标准 Base64</li>
 * </ol>
 * 待签串使用<b>已解码的参数值</b>（form 参数由容器解码后传入；请求方向在本项目内为进程内调用，
 * 值原样传递）。这与线上行为一致：两端对同一份解码后的参数计算签名，值内含 {@code &} 也不影响
 * （拼接歧义不影响"同串比对"——两端拼出的字符串完全相同）。
 * </p>
 * <p>
 * 密钥格式：PKCS#8 私钥 / X.509 公钥的 Base64 主体（可带或不带 PEM 头尾，换行会被忽略）。
 * </p>
 */
@Slf4j
public final class AlipaySignatureUtil {

    private static final String SIGN_TYPE_RSA2 = "SHA256withRSA";
    private static final String SIGN_PARAM = "sign";
    private static final String SIGN_TYPE_PARAM = "sign_type";

    private AlipaySignatureUtil() {
    }

    /**
     * 构造待签名字符串：剔除 sign/sign_type 与空值参数 → 参数名字典序 → {@code k=v&k=v}。
     */
    public static String buildSignContent(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        // TreeMap 天然字典序（ASCII 码排序）
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isEmpty()) {
                continue;
            }
            if (SIGN_PARAM.equals(key) || SIGN_TYPE_PARAM.equals(key)) {
                continue;
            }
            if (value == null || value.isEmpty()) {
                continue;
            }
            sorted.put(key, value);
        }
        StringBuilder content = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (content.length() > 0) {
                content.append('&');
            }
            content.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return content.toString();
    }

    /**
     * RSA2 签名（SHA256withRSA → 标准 Base64）。
     *
     * @param params     待签名参数（sign/sign_type 会被剔除；空值不参与）
     * @param privateKey PKCS#8 私钥（Base64 主体）
     * @return 签名字符串
     * @throws IllegalStateException 密钥非法或算法不可用（配置错误，快速失败）
     */
    public static String sign(Map<String, String> params, String privateKey) {
        try {
            Signature signature = Signature.getInstance(SIGN_TYPE_RSA2);
            signature.initSign(parsePrivateKey(privateKey));
            signature.update(buildSignContent(params).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // Base64 非法字符 → IllegalArgumentException；密钥规格/算法异常 → GeneralSecurityException
            throw new IllegalStateException("支付宝 RSA2 签名失败（密钥或算法配置错误）: " + e.getMessage(), e);
        }
    }

    /**
     * RSA2 验签：从参数中取 {@code sign}，以同一规则重建待验签串比对。
     * <p>任何异常（签名缺失/密钥非法/Base64 非法）均返回 {@code false}——验签失败即"不可信"，不区分原因抛错。</p>
     *
     * @param params    通知/请求原始参数（必须原样，不得增删）
     * @param publicKey X.509 公钥（Base64 主体）
     * @return 验签是否通过
     */
    public static boolean verify(Map<String, String> params, String publicKey) {
        if (params == null || params.isEmpty()) {
            return false;
        }
        String sign = params.get(SIGN_PARAM);
        if (sign == null || sign.isEmpty()) {
            log.warn("[支付宝验签] 缺少 sign 参数，验签失败");
            return false;
        }
        try {
            Signature signature = Signature.getInstance(SIGN_TYPE_RSA2);
            signature.initVerify(parsePublicKey(publicKey));
            signature.update(buildSignContent(params).getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(sign));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            log.warn("[支付宝验签] 验签异常（按失败处理）: {}", e.getMessage());
            return false;
        }
    }

    private static PrivateKey parsePrivateKey(String key) throws GeneralSecurityException {
        byte[] der = Base64.getDecoder().decode(normalizeKey(key));
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static PublicKey parsePublicKey(String key) throws GeneralSecurityException {
        byte[] der = Base64.getDecoder().decode(normalizeKey(key));
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    /** 去掉 PEM 头尾与所有空白，得到纯 Base64 主体。 */
    private static String normalizeKey(String key) {
        if (key == null) {
            throw new IllegalArgumentException("密钥为空");
        }
        return key.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
    }
}
