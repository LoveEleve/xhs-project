package com.myxhs.payment.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 支付宝渠道契约模拟配置（C-003 / ITER-003）
 * <p>
 * 本地模拟支付宝公钥体系的<b>两对密钥</b>（真实世界同样是两对）：
 * <ul>
 *   <li><b>应用密钥对</b>（merchant-private/public-key）：商户签请求 → 模拟服务器验请求</li>
 *   <li><b>支付宝密钥对</b>（alipay-private/public-key）：模拟服务器签通知 → 端点验通知</li>
 * </ul>
 * 签名/验签流程与线上完全一致（RSA2 + 字典序 + Base64），仅密钥为本地自产（MOCK-DEV-ONLY）。
 * 接入真实支付宝时：替换为商户实际密钥与应用公钥（配置项结构不变）。
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "pay.alipay.mock")
public class AlipayMockProperties {

    /** 应用 ID（二次校验比对项） */
    private String appId;

    /** 商户 PID（二次校验比对项） */
    private String sellerId;

    /** 应用私钥（PKCS#8，Base64 主体）——商户签请求 */
    private String merchantPrivateKey;

    /** 应用公钥（X.509，Base64 主体）——模拟服务器验请求 */
    private String merchantPublicKey;

    /** 支付宝私钥（PKCS#8，Base64 主体）——模拟服务器签通知 */
    private String alipayPrivateKey;

    /** 支付宝公钥（X.509，Base64 主体）——端点验通知 */
    private String alipayPublicKey;

    /** 模拟服务器通知投递地址（默认本机支付服务端口；单测中由投递器 mock 替代） */
    private String notifyUrl = "http://localhost:19012/api/payment/notify/alipay";

    /**
     * 通知重试节奏（简化版，默认 5s/15s/60s——官方为 4m/10m/10m/1h/2h/6h/15h/25h）。
     * 保留语义：间隔递增、notify_id 不变、超过 {@link #retryMaxAttempts} 放弃（靠查单兜底）。
     */
    private List<String> retryIntervals = new ArrayList<>(List.of("5s", "15s", "60s"));

    /** 通知重试上限（官方 8 次；超限放弃，后台账记 undelivered） */
    private int retryMaxAttempts = 8;

    /** "用户付款"模拟成功率（0~1） */
    private double successRate = 0.9;

    /** "用户付款"模拟延迟下限（毫秒） */
    private long callbackDelayMinMs = 1000;

    /** "用户付款"模拟延迟上限（毫秒） */
    private long callbackDelayMaxMs = 3000;

    /**
     * 故障注入：退款调用异常（模拟"调用超时、结果未知"）。
     * <p>开启后 {@code alipay.trade.refund} 抛异常——上层应停留"退款中"，由恢复任务同 out 键重试
     * （"不知道"≠"没退成"）。默认关闭；测试/演练时开启。</p>
     */
    private boolean refundFaultInjection = false;
}
