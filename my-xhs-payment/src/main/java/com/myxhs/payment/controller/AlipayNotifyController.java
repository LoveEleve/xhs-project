package com.myxhs.payment.controller;

import com.myxhs.payment.alipay.AlipayNotifyParams;
import com.myxhs.payment.alipay.AlipayTradeClient;
import com.myxhs.payment.alipay.AlipayTradeStatus;
import com.myxhs.payment.config.AlipayMockProperties;
import com.myxhs.payment.entity.Payment;
import com.myxhs.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * 支付宝异步通知端点（渠道契约对齐 · C-003）
 * <p>
 * 官方契约形态：支付宝服务器 <b>POST form 表单</b>到 notify_url，商户：
 * <ol>
 *   <li><b>验签</b>（RSA2，支付宝公钥）——验签即鉴权（本端点无内部调用头：支付宝不会带）</li>
 *   <li><b>二次校验</b>：① out_trade_no 对应支付单存在 ② total_amount 与本地金额一致
 *       ③ app_id / seller_id 与配置一致——任一不过 = 异常通知，应答 fail 并告警（不处理）</li>
 *   <li><b>四态分流</b>：TRADE_SUCCESS/TRADE_FINISHED → 支付成功链路；
 *       TRADE_CLOSED → <b>双语义</b>（本地待支付=未付款关闭→置失败；已成功/已退款=全退后关闭→幂等）；
 *       WAIT_BUYER_PAY → 忽略</li>
 *   <li><b>明文应答</b>：{@code "success"}（7 字符，官方要求）→ 渠道不重试；其他/异常 → 渠道按节奏重试</li>
 * </ol>
 * 幂等：渠道重试（notify_id 不变）与并发通知由 {@link PaymentService#handlePayCallback} 的
 * 状态守卫（CAS）保证——重复通知应答 success，不重复处置。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/payment/notify")
@RequiredArgsConstructor
public class AlipayNotifyController {

    /** 官方要求的成功应答（明文，非 JSON） */
    private static final String ACK_SUCCESS = "success";

    /** 失败应答：渠道将按节奏重试 */
    private static final String ACK_FAIL = "fail";

    private final AlipayTradeClient alipayTradeClient;
    private final AlipayMockProperties alipayMockProperties;
    private final PaymentService paymentService;

    /**
     * 支付宝异步通知入口。
     * <p>form 参数由容器完成解码后注入；验签必须使用原样参数（见 {@link AlipayNotifyParams#getRaw()}）。</p>
     */
    @PostMapping(value = "/alipay", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public String notifyAlipay(@RequestParam Map<String, String> params) {
        try {
            return doNotify(params);
        } catch (Exception e) {
            // 处理异常 → 应答 fail：让渠道重试兜瞬时故障（通知会放弃，查单不会——对账核查分支兜底）
            log.error("[支付宝通知] 处理异常（应答 fail 触发渠道重试）: notifyId={}, outTradeNo={}",
                    params.get("notify_id"), params.get("out_trade_no"), e);
            return ACK_FAIL;
        }
    }

    private String doNotify(Map<String, String> params) {
        AlipayNotifyParams notify = AlipayNotifyParams.fromMap(params);
        log.info("[支付宝通知] 收到: notifyId={}, notifyType={}, outTradeNo={}, tradeNo={}, tradeStatus={}",
                notify.getNotifyId(), notify.getNotifyType(), notify.getOutTradeNo(),
                notify.getTradeNo(), notify.getTradeStatus());

        // 1. 验签（RSA2 + 支付宝公钥）——验签失败 = 伪造或配置错误，不处理
        if (!alipayTradeClient.verifyNotify(params)) {
            log.error("[支付宝通知] 验签失败（伪造或配置错误）: notifyId={}, outTradeNo={}",
                    notify.getNotifyId(), notify.getOutTradeNo());
            return ACK_FAIL;
        }

        // 2. 二次校验：状态可解析
        AlipayTradeStatus status = notify.status();
        if (status == null) {
            log.error("[支付宝通知] 二次校验失败：trade_status 不可解析: tradeStatus={}, outTradeNo={}",
                    notify.getTradeStatus(), notify.getOutTradeNo());
            return ACK_FAIL;
        }

        // 2.1 二次校验：支付单存在
        Payment payment = paymentService.findPaymentByNo(notify.getOutTradeNo());
        if (payment == null) {
            log.error("[支付宝通知] 二次校验失败：支付单不存在: outTradeNo={}", notify.getOutTradeNo());
            return ACK_FAIL;
        }

        // 2.2 二次校验：金额一致
        if (!amountMatches(payment, notify.getTotalAmount())) {
            log.error("[支付宝通知] 二次校验失败：金额不符: outTradeNo={}, 本地={}, 通知={}",
                    notify.getOutTradeNo(), payment.getAmount(), notify.getTotalAmount());
            return ACK_FAIL;
        }

        // 2.3 二次校验：商户一致（app_id / seller_id）
        if (!Objects.equals(alipayMockProperties.getAppId(), notify.getAppId())
                || !Objects.equals(alipayMockProperties.getSellerId(), notify.getSellerId())) {
            log.error("[支付宝通知] 二次校验失败：商户不符: outTradeNo={}, appId={}, sellerId={}",
                    notify.getOutTradeNo(), notify.getAppId(), notify.getSellerId());
            return ACK_FAIL;
        }

        // 3. 四态分流
        switch (status) {
            case TRADE_SUCCESS, TRADE_FINISHED ->
                    // FINISHED（不可退款）映射为成功：本地无"不可退款"概念，差异注释记录（AlipayTradeStatus.isPaid）
                    paymentService.handlePayCallback(notify.getOutTradeNo(), notify.getTradeNo(), true);
            case TRADE_CLOSED ->
                    // 双语义分流：本地待支付 = 未付款关闭 → 置失败（verify_method=1 渠道确认）；
                    // 本地已成功/已退款 = 全退后关闭 → handlePayCallback 状态守卫幂等 no-op（不误置失败）
                    paymentService.handlePayCallback(notify.getOutTradeNo(), notify.getTradeNo(), false);
            case WAIT_BUYER_PAY ->
                    log.info("[支付宝通知] 等待付款态通知，无需处理: outTradeNo={}", notify.getOutTradeNo());
        }
        return ACK_SUCCESS;
    }

    /** 二次校验金额比对（官方 total_amount 与本地支付单金额一致；格式非法按不通过处理） */
    private boolean amountMatches(Payment payment, String totalAmount) {
        if (payment.getAmount() == null || totalAmount == null || totalAmount.isBlank()) {
            return false;
        }
        try {
            return payment.getAmount().compareTo(new BigDecimal(totalAmount)) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
