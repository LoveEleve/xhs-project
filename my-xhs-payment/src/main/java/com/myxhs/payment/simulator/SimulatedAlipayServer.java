package com.myxhs.payment.simulator;

import com.myxhs.payment.alipay.AlipaySignatureUtil;
import com.myxhs.payment.alipay.AlipayTradeQueryResult;
import com.myxhs.payment.alipay.AlipayTradeStatus;
import com.myxhs.payment.alipay.RefundTradeResult;
import com.myxhs.payment.config.AlipayMockProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 模拟支付宝服务器（渠道替身 · C-003）
 * <p>
 * 角色：扮演<b>支付宝侧</b>——维护交易台账、接收商户请求（验商户签名）、
 * 发送异步通知（签支付宝签名 + HTTP 投递 + ACK 判断 + 简化节奏重试）。
 * </p>
 * <p>
 * 与真实契约的对应：
 * <ul>
 *   <li>请求方向（商户 → 本服务器）：进程内调用，<b>真实签名验签</b>（应用公钥）</li>
 *   <li>通知方向（本服务器 → 商户 notify_url）：<b>真实 HTTP POST + form 参数 + RSA2 签名 + ACK + 重试</b></li>
 *   <li>"用户付款"模拟：创建交易后延迟 + 成功率自动转 TRADE_SUCCESS（未中 = 用户未完成，停留 WAIT）；
 *       确定性触发 {@link #triggerPay}（脚本/联调用）</li>
 *   <li>关闭交易（close）→ 异步投递 TRADE_CLOSED 通知（真实支付宝在关单时也会推送通知）</li>
 *   <li>全额退款 → 交易置 TRADE_CLOSED（官方语义）+ 异步通知；退款幂等由 out_request_no 保证</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SimulatedAlipayServer {

    private static final DateTimeFormatter NOTIFY_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MockAlipayTradeRepository repository;
    private final AlipayNotifySender notifySender;
    private final AlipayMockProperties properties;

    // ==================== 请求方向（商户调用；进程内 + 真实验签） ====================

    /** alipay.trade.create：创建交易（台账建立；同 out_trade_no 幂等返回既有交易） */
    public String handleCreateTrade(Map<String, String> request) {
        verifyMerchantSign(request);
        String outTradeNo = requireParam(request, "out_trade_no");
        MockTradeRecord existing = repository.load(outTradeNo);
        if (existing != null) {
            return existing.getTradeNo();
        }

        long now = System.currentTimeMillis();
        MockTradeRecord record = new MockTradeRecord();
        record.setOutTradeNo(outTradeNo);
        record.setTradeNo("ALIPAY_TRADE_" + System.nanoTime());
        record.setOrderId(Long.parseLong(request.getOrDefault("order_id", "0")));
        record.setAmount(requireParam(request, "total_amount"));
        record.setRefundedAmount("0.00");
        record.setSubject(request.getOrDefault("subject", "订单支付"));
        record.setStatus(AlipayTradeStatus.WAIT_BUYER_PAY.name());
        record.setCreatedAt(now);
        record.setPayAfterAt(now + randomDelay());
        repository.save(record);
        log.info("[模拟支付宝] 交易创建: outTradeNo={}, tradeNo={}, amount={}",
                outTradeNo, record.getTradeNo(), record.getAmount());
        return record.getTradeNo();
    }

    /** alipay.trade.query：查单；台账不存在 → null（不可判定） */
    public AlipayTradeQueryResult handleQueryTrade(Map<String, String> request) {
        verifyMerchantSign(request);
        MockTradeRecord record = repository.load(requireParam(request, "out_trade_no"));
        if (record == null) {
            log.warn("[模拟支付宝] 查单：台账不存在（不可判定）: outTradeNo={}", request.get("out_trade_no"));
            return null;
        }
        return new AlipayTradeQueryResult(record.getTradeNo(), AlipayTradeStatus.fromCode(record.getStatus()));
    }

    /**
     * alipay.trade.close：关闭交易。
     * <p>已支付交易不可关闭（官方 ACQ.TRADE_HAS_SUCCESS 语义）→ 返回 false，调用方"先查后决"。</p>
     */
    public boolean handleCloseTrade(Map<String, String> request) {
        verifyMerchantSign(request);
        String outTradeNo = requireParam(request, "out_trade_no");
        if (!repository.tryLock(outTradeNo)) {
            log.warn("[模拟支付宝] 关单：交易处理中（锁被占）: outTradeNo={}", outTradeNo);
            return false;
        }
        MockTradeRecord record = repository.load(outTradeNo);
        if (record == null) {
            return true; // 幂等：不存在视为已关闭
        }
        if (AlipayTradeStatus.WAIT_BUYER_PAY.name().equals(record.getStatus())) {
            transitionTo(record, AlipayTradeStatus.TRADE_CLOSED);
            repository.save(record);
            // 商户主动关单（换渠道/超时处置/订单终结）→ 不投递业务通知：
            // ① 发起方已知悉关闭动作，通知无增量信息；
            // ② 避免竞态：通知若抢先到达，会把"待支付"旧单置为支付失败并联动订单取消，
            //    随后流程的"置已关闭(4)"UPDATE 落空 → 换渠道失败 + 订单被误取消。
            // 用户侧放弃/失败的 TRADE_CLOSED 通知由 triggerPay(false) 模拟（真实异步语义）。
            log.info("[模拟支付宝] 交易关闭(商户发起，不发通知): outTradeNo={}", outTradeNo);
            return true;
        }
        if (AlipayTradeStatus.TRADE_SUCCESS.name().equals(record.getStatus())) {
            log.info("[模拟支付宝] 交易已支付，不可关闭（ACQ.TRADE_HAS_SUCCESS）: outTradeNo={}", outTradeNo);
            return false;
        }
        return true; // 已关闭 → 幂等
    }

    /**
     * alipay.trade.refund：退款（同步返回）。
     * <p>幂等：同 out_request_no 只退一次；全额退款后交易置 TRADE_CLOSED（官方语义）。</p>
     */
    public RefundTradeResult handleRefundTrade(Map<String, String> request) {
        verifyMerchantSign(request);
        String outTradeNo = requireParam(request, "out_trade_no");
        String outRequestNo = requireParam(request, "out_request_no");
        BigDecimal refundAmount = new BigDecimal(requireParam(request, "refund_amount"));

        // 幂等：同 out_request_no 返回既有结果（"同号只退一次"）
        RefundTradeResult existing = repository.loadRefundResult(outRequestNo);
        if (existing != null) {
            log.info("[模拟支付宝] 退款幂等命中: outRequestNo={}", outRequestNo);
            return existing;
        }

        // 故障注入：模拟退款调用异常（结果未知——上层停留"退款中"，恢复任务同键重试）
        if (properties.isRefundFaultInjection()) {
            throw new IllegalStateException("模拟退款调用超时（故障注入）: outRequestNo=" + outRequestNo);
        }

        MockTradeRecord record = repository.load(outTradeNo);
        if (record == null) {
            return saveFailResult(outRequestNo, "ACQ.TRADE_NOT_EXIST", "交易不存在");
        }
        if (AlipayTradeStatus.WAIT_BUYER_PAY.name().equals(record.getStatus())) {
            return saveFailResult(outRequestNo, "ACQ.TRADE_NOT_ALLOW_REFUND", "交易未支付，不可退款");
        }
        if (AlipayTradeStatus.TRADE_CLOSED.name().equals(record.getStatus())) {
            return saveFailResult(outRequestNo, "ACQ.TRADE_HAS_CLOSE", "交易已关闭，不可退款");
        }

        // TRADE_SUCCESS：退款成功（同步返回 fund_change=true）
        BigDecimal total = new BigDecimal(record.getAmount());
        BigDecimal refunded = new BigDecimal(record.getRefundedAmount());
        if (refunded.add(refundAmount).compareTo(total) > 0) {
            return saveFailResult(outRequestNo, "ACQ.REFUND_AMT_NOT_EQUAL_TOTAL", "累计退款超过交易金额");
        }
        record.setRefundedAmount(refunded.add(refundAmount).toPlainString());
        RefundTradeResult result = RefundTradeResult.success("ALIPAY_REFUND_" + System.nanoTime());
        boolean fullyRefunded = new BigDecimal(record.getRefundedAmount()).compareTo(total) >= 0;
        if (fullyRefunded) {
            // 全额退款后交易关闭（官方语义）；关闭通知异步投递——
            // 注意：scheduleDelivery 必须在 save 之前（否则 nextRetryAt 不落库、通知永不投递）
            transitionTo(record, AlipayTradeStatus.TRADE_CLOSED);
            scheduleDelivery(record);
        }
        // 幂等结果先落（"同号只退一次"优先于台账记账）：
        // 崩溃后同键重试命中幂等返回既有结果，绝不重复出金（台账计数漂移仅影响超额防护，可容忍）
        repository.saveRefundResult(outRequestNo, result);
        repository.save(record);
        log.info("[模拟支付宝] 退款成功: outTradeNo={}, outRequestNo={}, amount={}, fullyRefunded={}",
                outTradeNo, outRequestNo, refundAmount, fullyRefunded);
        return result;
    }

    // ==================== "用户付款"模拟 ====================

    /**
     * 确定性触发付款（脚本/联调/测试用；对应"用户在收银台完成支付/放弃"）。
     *
     * @param success true=支付成功（TRADE_SUCCESS 通知）；false=支付失败（TRADE_CLOSED 通知）
     */
    public void triggerPay(String outTradeNo, boolean success) {
        if (!repository.tryLock(outTradeNo)) {
            log.warn("[模拟支付宝] 触发付款：交易处理中（锁被占）: outTradeNo={}", outTradeNo);
            return;
        }
        MockTradeRecord record = repository.load(outTradeNo);
        if (record == null) {
            log.warn("[模拟支付宝] 触发付款：交易不存在: outTradeNo={}", outTradeNo);
            return;
        }
        if (AlipayTradeStatus.WAIT_BUYER_PAY.name().equals(record.getStatus())) {
            record.setAutoPayRolled(true);
            transitionTo(record, success ? AlipayTradeStatus.TRADE_SUCCESS : AlipayTradeStatus.TRADE_CLOSED);
            repository.save(record);
            deliverNotify(record); // 触发场景同步投递：脚本/联调可立即观察结果
            return;
        }
        if (AlipayTradeStatus.TRADE_SUCCESS.name().equals(record.getStatus()) && !record.isNotifyDelivered()) {
            deliverNotify(record); // 已付款但通知未送达 → 补投递
            return;
        }
        log.info("[模拟支付宝] 触发付款：无需处理: outTradeNo={}, status={}", outTradeNo, record.getStatus());
    }

    /** 自动付款扫描：到点按成功率掷骰（一次性）；未中 = 用户未完成支付（停留 WAIT，由超时处置兜底） */
    @Scheduled(fixedDelay = 5000)
    public void simulateUserPayment() {
        long now = System.currentTimeMillis();
        for (MockTradeRecord scanned : repository.scanAll()) {
            if (!AlipayTradeStatus.WAIT_BUYER_PAY.name().equals(scanned.getStatus())
                    || scanned.isAutoPayRolled() || now < scanned.getPayAfterAt()) {
                continue;
            }
            if (!repository.tryLock(scanned.getOutTradeNo())) {
                continue;
            }
            MockTradeRecord record = repository.load(scanned.getOutTradeNo());
            if (record == null
                    || !AlipayTradeStatus.WAIT_BUYER_PAY.name().equals(record.getStatus())
                    || record.isAutoPayRolled() || System.currentTimeMillis() < record.getPayAfterAt()) {
                continue;
            }
            // 定时线程无入站 traceId：每单开新链路（投递→端点→MQ→订单全链可追）
            com.myxhs.common.trace.TraceContextHolder.startNewTrace();
            try {
                record.setAutoPayRolled(true);
                boolean success = ThreadLocalRandom.current().nextDouble() < properties.getSuccessRate();
                if (success) {
                    transitionTo(record, AlipayTradeStatus.TRADE_SUCCESS);
                    repository.save(record);
                    deliverNotify(record);
                } else {
                    repository.save(record);
                    log.info("[模拟支付宝] 用户未完成支付（掷骰未中）: outTradeNo={}", record.getOutTradeNo());
                }
            } finally {
                com.myxhs.common.trace.TraceContextHolder.clear();
            }
        }
    }

    /** 未送达通知的重试扫描（简化节奏；notify_id 不变；超上限放弃——查单兜底） */
    @Scheduled(fixedDelay = 5000)
    public void retryUndeliveredNotifies() {
        long now = System.currentTimeMillis();
        for (MockTradeRecord scanned : repository.scanAll()) {
            if (scanned.isNotifyDelivered() || scanned.getNextRetryAt() <= 0 || now < scanned.getNextRetryAt()) {
                continue;
            }
            if (!repository.tryLock(scanned.getOutTradeNo())) {
                continue;
            }
            MockTradeRecord record = repository.load(scanned.getOutTradeNo());
            if (record == null || record.isNotifyDelivered()
                    || record.getNextRetryAt() <= 0 || System.currentTimeMillis() < record.getNextRetryAt()) {
                continue;
            }
            // 定时线程无入站 traceId：重投也开新链路（与首次投递同口径可追）
            com.myxhs.common.trace.TraceContextHolder.startNewTrace();
            try {
                deliverNotify(record);
            } finally {
                com.myxhs.common.trace.TraceContextHolder.clear();
            }
        }
    }

    // ==================== 通知投递 ====================

    /** 同步投递一次（失败则按节奏安排重试） */
    private void deliverNotify(MockTradeRecord record) {
        Map<String, String> params = buildNotifyParams(record);
        params.put("sign", AlipaySignatureUtil.sign(params, properties.getAlipayPrivateKey()));
        boolean acked = false;
        try {
            acked = notifySender.send(params);
        } catch (Exception e) {
            log.warn("[模拟支付宝] 通知投递异常（按未 ACK 处理）: outTradeNo={}", record.getOutTradeNo(), e);
        }
        record.setNotifyAttempts(record.getNotifyAttempts() + 1);
        if (acked) {
            record.setNotifyDelivered(true);
            record.setNextRetryAt(0);
            log.info("[模拟支付宝] 通知已送达: outTradeNo={}, notifyId={}, attempts={}",
                    record.getOutTradeNo(), record.getNotifyId(), record.getNotifyAttempts());
        } else {
            scheduleRetry(record);
        }
        repository.save(record);
    }

    /** 异步安排投递（关单/全退场景：不阻塞调用方，由重试扫描投递——真实异步通知语义） */
    private void scheduleDelivery(MockTradeRecord record) {
        record.setNextRetryAt(System.currentTimeMillis());
    }

    private void scheduleRetry(MockTradeRecord record) {
        if (record.getNotifyAttempts() >= properties.getRetryMaxAttempts()) {
            log.warn("[模拟支付宝] 通知重试上限({})放弃投递——查单兜底: outTradeNo={}, notifyId={}",
                    properties.getRetryMaxAttempts(), record.getOutTradeNo(), record.getNotifyId());
            record.setNextRetryAt(0);
            return;
        }
        long interval = retryIntervalMillis(record.getNotifyAttempts() - 1);
        record.setNextRetryAt(System.currentTimeMillis() + interval);
        log.info("[模拟支付宝] 通知未 ACK，安排重试: outTradeNo={}, attempts={}, nextRetryIn={}ms",
                record.getOutTradeNo(), record.getNotifyAttempts(), interval);
    }

    /** 构造通知参数（官方参数集；notify_id 取台账——生成一次、重试不变） */
    private Map<String, String> buildNotifyParams(MockTradeRecord record) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("notify_id", record.getNotifyId());
        params.put("notify_type", "trade_status_sync");
        params.put("notify_time", LocalDateTime.now().format(NOTIFY_TIME_FORMAT));
        params.put("out_trade_no", record.getOutTradeNo());
        params.put("trade_no", record.getTradeNo());
        params.put("trade_status", record.getStatus());
        params.put("total_amount", record.getAmount());
        params.put("app_id", properties.getAppId());
        params.put("seller_id", properties.getSellerId());
        params.put("subject", record.getSubject());
        params.put("sign_type", "RSA2");
        return params;
    }

    // ==================== 内部工具 ====================

    /** 状态迁移：生成新 notify_id（重试不变）+ 重置投递态 */
    private void transitionTo(MockTradeRecord record, AlipayTradeStatus status) {
        record.setStatus(status.name());
        record.setNotifyId("NID-" + record.getOutTradeNo() + "-" + System.nanoTime());
        record.setNotifyDelivered(false);
        record.setNotifyAttempts(0);
        record.setNextRetryAt(0);
    }

    private RefundTradeResult saveFailResult(String outRequestNo, String errorCode, String errorMsg) {
        RefundTradeResult result = RefundTradeResult.fail(errorCode, errorMsg);
        repository.saveRefundResult(outRequestNo, result);
        log.info("[模拟支付宝] 退款失败: outRequestNo={}, code={}, msg={}", outRequestNo, errorCode, errorMsg);
        return result;
    }

    /** 商户请求验签（应用公钥）——失败即拒绝（模拟服务器不信任未签名请求） */
    private void verifyMerchantSign(Map<String, String> request) {
        if (!AlipaySignatureUtil.verify(request, properties.getMerchantPublicKey())) {
            throw new IllegalStateException("模拟支付宝服务器：商户请求验签失败");
        }
    }

    private String requireParam(Map<String, String> request, String name) {
        String value = request.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("模拟支付宝服务器：缺少必填参数 " + name);
        }
        return value;
    }

    private long randomDelay() {
        long min = properties.getCallbackDelayMinMs();
        long max = Math.max(min, properties.getCallbackDelayMaxMs());
        return min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1);
    }

    /** 解析重试间隔（"5s"/"500ms"/纯数字=秒）；超出列表长度取最后一档 */
    private long retryIntervalMillis(int index) {
        var intervals = properties.getRetryIntervals();
        if (intervals == null || intervals.isEmpty()) {
            return 5000L;
        }
        String value = intervals.get(Math.min(Math.max(index, 0), intervals.size() - 1));
        try {
            if (value.endsWith("ms")) {
                return Long.parseLong(value.substring(0, value.length() - 2));
            }
            if (value.endsWith("s")) {
                return Long.parseLong(value.substring(0, value.length() - 1)) * 1000L;
            }
            return Long.parseLong(value) * 1000L;
        } catch (NumberFormatException e) {
            log.warn("[模拟支付宝] 重试间隔配置非法（按 5s 处理）: {}", value);
            return 5000L;
        }
    }
}
