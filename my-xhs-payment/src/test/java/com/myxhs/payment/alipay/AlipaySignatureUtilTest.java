package com.myxhs.payment.alipay;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 支付宝 RSA2 签名/验签工具单测
 * <p>
 * 验证口径：本地生成密钥对（与线上流程一致：PKCS#8 私钥 / X.509 公钥），
 * 覆盖官方签名规则的关键边界：空值剔除 / sign_type 剔除 / 字典序 / 中文与特殊字符 / 篡改 / 错钥。
 * </p>
 */
class AlipaySignatureUtilTest {

    private static String privateKeyB64;
    private static String publicKeyB64;
    private static String otherPublicKeyB64;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        privateKeyB64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        publicKeyB64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        KeyPair other = generator.generateKeyPair();
        otherPublicKeyB64 = Base64.getEncoder().encodeToString(other.getPublic().getEncoded());
    }

    private Map<String, String> notifyParams() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("notify_id", "NID-20261017-001");
        params.put("out_trade_no", "PAY20261017001");
        params.put("trade_no", "ALIPAY_TRADE_001");
        params.put("trade_status", "TRADE_SUCCESS");
        params.put("total_amount", "199.00");
        return params;
    }

    @Test
    @DisplayName("签名 → 验签 roundtrip 通过")
    void signAndVerifyRoundTrip() {
        Map<String, String> params = notifyParams();
        String sign = AlipaySignatureUtil.sign(params, privateKeyB64);
        assertThat(sign).isNotBlank();

        params.put("sign", sign);
        params.put("sign_type", "RSA2");
        assertThat(AlipaySignatureUtil.verify(params, publicKeyB64)).isTrue();
    }

    @Test
    @DisplayName("空值参数不参与签名（官方规则：参数值为空不参与签名）")
    void emptyValueExcluded() {
        Map<String, String> base = notifyParams();
        Map<String, String> withEmpty = notifyParams();
        withEmpty.put("subject", "");
        withEmpty.put("body", null);

        assertThat(AlipaySignatureUtil.sign(withEmpty, privateKeyB64))
                .isEqualTo(AlipaySignatureUtil.sign(base, privateKeyB64));
    }

    @Test
    @DisplayName("sign_type 不参与签名（官方验签规则：sign 与 sign_type 均剔除）")
    void signTypeExcluded() {
        Map<String, String> base = notifyParams();
        Map<String, String> withSignType = notifyParams();
        withSignType.put("sign_type", "RSA2");

        assertThat(AlipaySignatureUtil.sign(withSignType, privateKeyB64))
                .isEqualTo(AlipaySignatureUtil.sign(base, privateKeyB64));
    }

    @Test
    @DisplayName("待签串：字典序 + k=v& 拼接 + 剔除 sign/sign_type/空值")
    void buildSignContentRules() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("b_param", "2");
        params.put("a_param", "1");
        params.put("sign", "should-be-excluded");
        params.put("sign_type", "RSA2");
        params.put("empty_param", "");
        params.put("null_param", null);

        assertThat(AlipaySignatureUtil.buildSignContent(params)).isEqualTo("a_param=1&b_param=2");
    }

    @Test
    @DisplayName("中文与特殊字符（含 & = 空格）roundtrip 通过——两端对同一解码后参数计算签名")
    void chineseAndSpecialCharsRoundTrip() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("subject", "小红书订单-测试商品 A&B=限量版 2026");
        params.put("body", "含中文、空格、& 与 = 的值");
        String sign = AlipaySignatureUtil.sign(params, privateKeyB64);
        params.put("sign", sign);

        assertThat(AlipaySignatureUtil.verify(params, publicKeyB64)).isTrue();
    }

    @Test
    @DisplayName("篡改任一参数 → 验签失败")
    void tamperedParamFails() {
        Map<String, String> params = notifyParams();
        String sign = AlipaySignatureUtil.sign(params, privateKeyB64);
        params.put("sign", sign);

        params.put("total_amount", "0.01");
        assertThat(AlipaySignatureUtil.verify(params, publicKeyB64)).isFalse();
    }

    @Test
    @DisplayName("错误公钥 → 验签失败")
    void wrongPublicKeyFails() {
        Map<String, String> params = notifyParams();
        params.put("sign", AlipaySignatureUtil.sign(params, privateKeyB64));

        assertThat(AlipaySignatureUtil.verify(params, otherPublicKeyB64)).isFalse();
    }

    @Test
    @DisplayName("缺少 sign / sign 非法 Base64 → 验签失败（不抛错）")
    void missingOrMalformedSignFails() {
        Map<String, String> noSign = notifyParams();
        assertThat(AlipaySignatureUtil.verify(noSign, publicKeyB64)).isFalse();

        Map<String, String> badSign = notifyParams();
        badSign.put("sign", "not-base64!!!");
        assertThat(AlipaySignatureUtil.verify(badSign, publicKeyB64)).isFalse();
    }

    @Test
    @DisplayName("PEM 头尾/换行包裹的密钥同样可用（normalize 兼容）")
    void pemWrappedKeysWork() {
        Map<String, String> params = notifyParams();
        String pemPrivate = "-----BEGIN PRIVATE KEY-----\n" + privateKeyB64 + "\n-----END PRIVATE KEY-----";
        String pemPublic = "-----BEGIN PUBLIC KEY-----\n" + publicKeyB64 + "\n-----END PUBLIC KEY-----";

        String sign = AlipaySignatureUtil.sign(params, pemPrivate);
        assertThat(sign).isEqualTo(AlipaySignatureUtil.sign(params, privateKeyB64));

        params.put("sign", sign);
        assertThat(AlipaySignatureUtil.verify(params, pemPublic)).isTrue();
    }

    @Test
    @DisplayName("非法私钥 → 签名快速失败（配置错误不静默）")
    void invalidPrivateKeyFailsFast() {
        assertThatThrownBy(() -> AlipaySignatureUtil.sign(notifyParams(), "bad-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("签名失败");
    }
}
