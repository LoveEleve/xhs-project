package com.myxhs.payment.service;

import com.myxhs.common.exception.BizException;
import com.myxhs.common.id.IdGeneratorUtil;
import com.myxhs.common.metrics.BusinessMetrics;
import com.myxhs.common.response.R;
import com.myxhs.common.response.ResultCode;
import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.payment.config.PaymentConfig;
import com.myxhs.payment.dto.request.PayCreateRequest;
import com.myxhs.payment.dto.request.RefundRequest;
import com.myxhs.payment.dto.response.PaymentVO;
import com.myxhs.payment.entity.Payment;
import com.myxhs.payment.entity.Refund;
import com.myxhs.payment.mapper.PaymentMapper;
import com.myxhs.payment.mapper.RefundMapper;
import com.myxhs.payment.feign.OrderFeignClient;
import com.myxhs.payment.strategy.PayChannelStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 支付服务 — 支付模块核心业务逻辑
 * <p>
 * 职责：
 * 1. 创建支付单（状态机：待支付 → 支付成功/支付失败）
 * 2. 处理支付回调（异步通知）
 * 3. 退款流程（状态机：退款中 → 退款成功/退款失败）
 * 4. 处理退款回调
 * 5. 支付超时检查（定时任务兜底）
 * 6. 退款超时检查（定时任务兜底）
 * 7. 对账机制
 * </p>
 * <p>
 * 幂等性设计：
 * - 创建支付单：Redis SETNX 防重复支付（Key: payment:paying:{orderId}, TTL: 30min）
 * - 处理回调：乐观锁更新支付状态（WHERE status = 待支付）
 * - 退款：Redis SETNX 防重复退款（Key: payment:refunding:{paymentId}, TTL: 7d）
 * - 退款回调：乐观锁更新退款状态（WHERE status = 退款中）
 * </p>
 * <p>
 * 分布式考虑：
 * - 所有定时任务加分布式锁（Redisson），防止多实例重复执行
 * - 状态更新使用乐观锁（WHERE status = 当前状态），保证幂等
 * - Redis 幂等键 + 乐观锁双重保障，极端并发也不会重复操作
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final JdbcTemplate paymentJdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final RocketMQTemplate rocketMQTemplate;
    private final Map<Integer, PayChannelStrategy> payChannelStrategyMap;
    private final DefaultRedisScript<Long> paymentTimeoutScript;
    private final OrderFeignClient orderFeignClient;
    private final IdGeneratorUtil idGeneratorUtil;
    private final BusinessMetrics businessMetrics;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /** Lua 脚本：安全释放分布式锁（只释放自己持有的锁） */
    private static final String UNLOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('del', KEYS[1]) " +
                    "else return 0 end";

    // ==================== 常量 ====================

    /** 支付中状态 Redis Key 前缀（防重复支付） */
    private static final String PAYING_KEY_PREFIX = "payment:paying:";
    /** 支付中状态 TTL（与订单超时时间一致：30 分钟） */
    private static final Duration PAYING_KEY_TTL = Duration.ofMinutes(30);

    /** 退款中状态 Redis Key 前缀（防重复退款） */
    private static final String REFUNDING_KEY_PREFIX = "payment:refunding:";
    /** 退款中状态 TTL（7 天，覆盖退款处理周期） */
    private static final Duration REFUNDING_KEY_TTL = Duration.ofDays(7);

    /** 支付状态：待支付 */
    private static final int STATUS_PENDING = 0;
    /** 支付状态：支付成功 */
    private static final int STATUS_SUCCESS = 1;
    /** 支付状态：支付失败 */
    private static final int STATUS_FAIL = 2;
    /** 支付状态：已退款 */
    private static final int STATUS_REFUNDED = 3;

    /** 退款状态：退款中 */
    private static final int REFUND_STATUS_PROCESSING = 0;
    /** 退款状态：退款成功 */
    private static final int REFUND_STATUS_SUCCESS = 1;
    /** 退款状态：退款失败 */
    private static final int REFUND_STATUS_FAIL = 2;
    /** 退款状态：退款关闭 */
    private static final int REFUND_STATUS_CLOSED = 3;

    /** 支付超时时间（毫秒）— 与订单超时关单保持一致 */
    private static final long PAY_TIMEOUT_MS = 30 * 60 * 1000L;

    // ==================== 1. 创建支付单 ====================

    /**
     * 创建支付单
     * <p>
     * 流程：
     * 1. 幂等校验：Redis SETNX 防止重复支付
     * 2. 插入支付记录（status=0 待支付）
     * 3. 设置 Redis 支付状态缓存（用于超时检测）
     * 4. 调用策略模式发起支付
     * 5. Mock 模式：pay() 同步返回 → 直接标记成功
     *    支付宝/微信：pay() 异步返回 → 等待回调
     * </p>
     *
     * @param request 支付创建请求
     * @return 支付单VO
     */
    public R<PaymentVO> pay(PayCreateRequest request) {
        Long orderId = request.getOrderId();
        Long userId = request.getUserId();
        BigDecimal amount = request.getAmount();
        Integer payType = request.getPayType();
        String payingKey = PAYING_KEY_PREFIX + orderId;
        String statusKey = "payment:status:" + orderId;
        Long paymentRecordId = null;

        // 1. 幂等校验：同一订单不能重复发起支付
        Boolean setSuccess = redisTemplate.opsForValue()
                .setIfAbsent(payingKey, String.valueOf(userId), PAYING_KEY_TTL);
        if (Boolean.FALSE.equals(setSuccess)) {
            log.warn("[支付] 重复支付请求: orderId={}, userId={}", orderId, userId);
            throw new BizException(ResultCode.IDEMPOTENT_REJECT, "请勿重复支付");
        }

        try {
            // 2. 生成支付流水号
            String paymentNo = generatePaymentNo();

            // 3. 插入支付记录（status=0 待支付）
            Payment payment = new Payment();
            payment.setId(generatePaymentId());
            payment.setOrderId(orderId);
            payment.setUserId(userId);
            payment.setPaymentNo(paymentNo);
            payment.setAmount(amount);
            payment.setPayType(payType);
            payment.setStatus(STATUS_PENDING);
            payment.setDeleted(0);
            payment.setCreatedAt(LocalDateTime.now());
            payment.setUpdatedAt(LocalDateTime.now());
            paymentJdbcTemplate.update(
                    "INSERT INTO t_payment (id, order_id, user_id, payment_no, amount, pay_type, status, deleted, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    payment.getId(), payment.getOrderId(), payment.getUserId(),
                    payment.getPaymentNo(), payment.getAmount(), payment.getPayType(),
                    payment.getStatus(), payment.getDeleted(),
                    payment.getCreatedAt(), payment.getUpdatedAt()
            );
            paymentRecordId = payment.getId();
            log.info("[支付] 支付记录已创建: paymentNo={}, orderId={}, amount={}", paymentNo, orderId, amount);

            // 4. 设置 Redis 支付状态缓存（用于超时检测定时任务）
            redisTemplate.opsForValue().set(statusKey, "0", PAYING_KEY_TTL);

            // 5. 通过策略模式调用对应支付渠道
            PayChannelStrategy strategy = payChannelStrategyMap.get(payType);
            if (strategy == null) {
                log.error("[支付] 未找到支付渠道策略: payType={}", payType);
                throw new BizException(ResultCode.PAYMENT_FAIL, "不支持的支付方式");
            }
            String tradeNo = strategy.pay(orderId, amount, paymentNo);
            log.info("[支付] 支付请求已发送: paymentNo={}, tradeNo={}, payType={}", paymentNo, tradeNo, payType);

            // 6. Mock 模式：同步标记成功
            if (isMockMode(payType)) {
                handlePaySuccessInternal(orderId, userId, paymentNo, tradeNo);
            }

            return R.ok(buildPaymentVO(payment));

        } catch (BizException e) {
            // 业务异常：清理 SETNX 锁 + 支付状态缓存，DB 记录由独立补偿逻辑处理
            redisTemplate.delete(payingKey);
            redisTemplate.delete(statusKey);
            // 删除已插入的支付记录（避免脏数据残留）
            if (paymentRecordId != null) {
                try {
                    paymentJdbcTemplate.update("DELETE FROM t_payment WHERE id = ?", paymentRecordId);
                } catch (Exception deleteEx) {
                    log.error("[支付] 清理支付记录失败: paymentId={}", paymentRecordId, deleteEx);
                }
            }
            throw e;
        } catch (Exception e) {
            // 非预期异常：同样清理 Redis 和 DB 记录
            log.error("[支付] 创建支付单异常: orderId={}, userId={}", orderId, userId, e);
            redisTemplate.delete(payingKey);
            redisTemplate.delete(statusKey);
            if (paymentRecordId != null) {
                try {
                    paymentJdbcTemplate.update("DELETE FROM t_payment WHERE id = ?", paymentRecordId);
                } catch (Exception deleteEx) {
                    log.error("[支付] 清理支付记录失败: paymentId={}", paymentRecordId, deleteEx);
                }
            }
            throw new BizException(ResultCode.INTERNAL_ERROR, "支付失败: " + e.getMessage());
        }
    }

    // ==================== 2. 处理支付回调 ====================

    /**
     * 处理支付回调（异步通知）
     * <p>
     * 由 PayCallbackController 接收第三方回调后调用此方法。
     * 乐观锁保证幂等：只有"待支付"状态的支付单才能更新为"支付成功"。
     * </p>
     *
     * @param paymentNo 支付流水号
     * @param tradeNo   第三方交易号
     * @param success   是否支付成功
     */
    @Transactional(rollbackFor = Exception.class)
    public void handlePayCallback(String paymentNo, String tradeNo, boolean success) {
        // 1. 查询支付单
        Payment payment = findByPaymentNo(paymentNo);
        if (payment == null) {
            log.warn("[支付回调] 支付单不存在: paymentNo={}", paymentNo);
            return; // 幂等：不抛异常，静默返回
        }

        // 2. 幂等校验：只有"待支付"状态才能处理回调
        if (payment.getStatus() != STATUS_PENDING) {
            log.info("[支付回调] 支付单已处理: paymentNo={}, status={}", paymentNo, payment.getStatus());
            return;
        }

        if (success) {
            handlePaySuccessInternal(payment.getOrderId(), payment.getUserId(), paymentNo, tradeNo);
        } else {
            handlePayFailInternal(payment.getOrderId(), payment.getUserId(), paymentNo);
        }
    }

    /**
     * 内部方法：处理支付成功
     * <p>
     * 乐观锁更新支付状态 + 发送支付成功消息到 MQ。
     * </p>
     */
    private void handlePaySuccessInternal(Long orderId, Long userId, String paymentNo, String tradeNo) {
        // 乐观锁：只有 status=0(待支付) 的记录才会被更新
        int updated = paymentJdbcTemplate.update(
                "UPDATE t_payment SET status = ?, paid_at = ?, updated_at = ? " +
                        "WHERE order_id = ? AND status = ?",
                STATUS_SUCCESS, LocalDateTime.now(), LocalDateTime.now(),
                orderId, STATUS_PENDING
        );

        if (updated == 0) {
            log.info("[支付成功] 乐观锁冲突（已处理）: orderId={}, paymentNo={}", orderId, paymentNo);
            return;
        }

        // 更新 Redis 支付状态缓存
        redisTemplate.opsForValue().set("payment:status:" + orderId, "1", PAYING_KEY_TTL);

        // 删除幂等键（支付完成后允许该订单再次支付，如退款后重新支付）
        redisTemplate.delete(PAYING_KEY_PREFIX + orderId);

        log.info("[支付成功] orderId={}, paymentNo={}, tradeNo={}", orderId, paymentNo, tradeNo);

        businessMetrics.recordPaymentCallback("success");

        // 发送支付成功消息到 MQ（订单服务消费后更新订单状态为"已支付"）
        sendPayResultMq(orderId, userId, true, tradeNo);
    }

    /**
     * 内部方法：处理支付失败
     */
    private void handlePayFailInternal(Long orderId, Long userId, String paymentNo) {
        int updated = paymentJdbcTemplate.update(
                "UPDATE t_payment SET status = ?, updated_at = ? " +
                        "WHERE order_id = ? AND status = ?",
                STATUS_FAIL, LocalDateTime.now(),
                orderId, STATUS_PENDING
        );

        if (updated > 0) {
            redisTemplate.opsForValue().set("payment:status:" + orderId, "2", PAYING_KEY_TTL);
            redisTemplate.delete(PAYING_KEY_PREFIX + orderId);
            log.info("[支付失败] orderId={}, paymentNo={}", orderId, paymentNo);
            businessMetrics.recordPaymentCallback("fail");
            sendPayResultMq(orderId, userId, false, null);
        }
    }

    // ==================== 3. 退款流程 ====================

    /**
     * 发起退款
     * <p>
     * 流程：
     * 1. 幂等校验：Redis SETNX 防止重复退款
     * 2. 校验支付单状态（必须是"支付成功"）
     * 3. 校验退款金额（不能超过支付金额 - 已退金额）
     * 4. 创建退款单（status=0 退款中）
     * 5. 调用策略模式发起退款
     * 6. Mock 模式：同步标记退款成功
     * </p>
     */
    @Transactional(rollbackFor = Exception.class)
    public R<Void> refund(RefundRequest request) {
        Long paymentId = request.getPaymentId();
        Long userId = request.getUserId();
        BigDecimal refundAmount = request.getRefundAmount();
        String refundingKey = REFUNDING_KEY_PREFIX + paymentId;
        Long refundRecordId = null;

        // 1. 幂等校验：同一支付单不能重复退款
        Boolean setSuccess = redisTemplate.opsForValue()
                .setIfAbsent(refundingKey, String.valueOf(userId), REFUNDING_KEY_TTL);
        if (Boolean.FALSE.equals(setSuccess)) {
            log.warn("[退款] 重复退款请求: paymentId={}, userId={}", paymentId, userId);
            throw new BizException(ResultCode.IDEMPOTENT_REJECT, "请勿重复退款");
        }

        try {
            // 2. 查询支付单
            Payment payment = findById(paymentId);
            if (payment == null) {
                throw new BizException(ResultCode.PAYMENT_FAIL, "支付单不存在");
            }
            if (payment.getStatus() != STATUS_SUCCESS) {
                throw new BizException(ResultCode.ORDER_STATUS_ERROR, "支付单状态不允许退款");
            }

            // 3. 校验退款金额
            BigDecimal alreadyRefunded = getRefundedAmount(paymentId);
            BigDecimal maxRefundable = payment.getAmount().subtract(alreadyRefunded);
            if (refundAmount.compareTo(maxRefundable) > 0) {
                throw new BizException(ResultCode.PAYMENT_FAIL, "退款金额超过可退金额");
            }

            // 4. 创建退款单
            String refundNo = generateRefundNo();
            Refund refund = new Refund();
            refund.setId(generateRefundId());
            refund.setPaymentId(paymentId);
            refund.setOrderId(payment.getOrderId());
            refund.setUserId(userId);
            refund.setRefundNo(refundNo);
            refund.setRefundAmount(refundAmount);
            refund.setReason(request.getReason());
            refund.setStatus(REFUND_STATUS_PROCESSING);
            refund.setRefundType(request.getRefundType() != null ? request.getRefundType() : 1);
            refund.setRefundChannel(1); // 默认原路退回
            refund.setDeleted(0);
            refund.setCreatedAt(LocalDateTime.now());
            refund.setUpdatedAt(LocalDateTime.now());
            paymentJdbcTemplate.update(
                    "INSERT INTO t_refund (id, payment_id, order_id, user_id, refund_no, refund_amount, reason, status, refund_type, refund_channel, deleted, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    refund.getId(), refund.getPaymentId(), refund.getOrderId(),
                    refund.getUserId(), refund.getRefundNo(), refund.getRefundAmount(),
                    refund.getReason(), refund.getStatus(), refund.getRefundType(),
                    refund.getRefundChannel(), refund.getDeleted(),
                    refund.getCreatedAt(), refund.getUpdatedAt()
            );
            refundRecordId = refund.getId();
            log.info("[退款] 退款单已创建: refundNo={}, paymentId={}, refundAmount={}", refundNo, paymentId, refundAmount);

            // 5. 通过策略模式调用对应支付渠道的退款
            PayChannelStrategy strategy = payChannelStrategyMap.get(payment.getPayType());
            if (strategy == null) {
                throw new BizException(ResultCode.PAYMENT_FAIL, "不支持的支付方式");
            }
            String refundTradeNo = strategy.refund(paymentId, refundNo, refundAmount, request.getReason());
            log.info("[退款] 退款请求已发送: refundNo={}, refundTradeNo={}", refundNo, refundTradeNo);

            // 6. Mock 模式：同步标记退款成功
            if (isMockMode(payment.getPayType())) {
                handleRefundSuccessInternal(refundNo);
            }

            return R.ok();

        } catch (BizException e) {
            // 业务异常：清理 SETNX 锁（事务已回滚 DB 的 INSERT）
            redisTemplate.delete(refundingKey);
            throw e;
        } catch (Exception e) {
            // 非预期异常：清理 SETNX 锁
            log.error("[退款] 退款异常: paymentId={}, userId={}", paymentId, userId, e);
            redisTemplate.delete(refundingKey);
            throw new BizException(ResultCode.INTERNAL_ERROR, "退款失败: " + e.getMessage());
        }
    }

    // ==================== 4. 处理退款回调 ====================

    /**
     * 处理退款回调
     * <p>
     * 乐观锁保证幂等：只有"退款中"状态的退款单才能更新。
     * </p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleRefundCallback(String refundNo, boolean success) {
        // 1. 查询退款单
        Refund refund = findByRefundNo(refundNo);
        if (refund == null) {
            log.warn("[退款回调] 退款单不存在: refundNo={}", refundNo);
            return;
        }

        // 2. 幂等校验
        if (refund.getStatus() != REFUND_STATUS_PROCESSING) {
            log.info("[退款回调] 退款单已处理: refundNo={}, status={}", refundNo, refund.getStatus());
            return;
        }

        if (success) {
            handleRefundSuccessInternal(refundNo);
        } else {
            handleRefundFailInternal(refundNo);
        }
    }

    /**
     * 内部方法：处理退款成功
     * <p>
     * 乐观锁更新退款状态 + 更新支付单状态为"已退款" + 发送退款成功消息到 MQ。
     * </p>
     */
    private void handleRefundSuccessInternal(String refundNo) {
        // 1. 乐观锁更新退款单状态
        int updated = paymentJdbcTemplate.update(
                "UPDATE t_refund SET status = ?, success_at = ?, updated_at = ? " +
                        "WHERE refund_no = ? AND status = ?",
                REFUND_STATUS_SUCCESS, LocalDateTime.now(), LocalDateTime.now(),
                refundNo, REFUND_STATUS_PROCESSING
        );
        if (updated == 0) {
            log.info("[退款成功] 乐观锁冲突（已处理）: refundNo={}", refundNo);
            return;
        }

        // 2. 查询退款单详情（获取关联的支付单信息）
        Refund refund = findByRefundNo(refundNo);

        // 3. 更新支付单状态为"已退款"
        paymentJdbcTemplate.update(
                "UPDATE t_payment SET status = ?, updated_at = ? WHERE id = ?",
                STATUS_REFUNDED, LocalDateTime.now(), refund.getPaymentId()
        );

        // 4. 清理 Redis
        redisTemplate.delete(REFUNDING_KEY_PREFIX + refund.getPaymentId());
        redisTemplate.opsForValue().set("payment:status:" + refund.getOrderId(), "3", PAYING_KEY_TTL);

        log.info("[退款成功] refundNo={}, paymentId={}, orderId={}", refundNo, refund.getPaymentId(), refund.getOrderId());

        // 5. 发送退款成功消息到 MQ（订单服务消费后触发：恢复库存 + 退还优惠券 + 更新订单状态为"已退款"）
        sendRefundResultMq(refund.getOrderId(), refund.getUserId(), true, refundNo);
    }

    /**
     * 内部方法：处理退款失败
     */
    private void handleRefundFailInternal(String refundNo) {
        int updated = paymentJdbcTemplate.update(
                "UPDATE t_refund SET status = ?, updated_at = ? " +
                        "WHERE refund_no = ? AND status = ?",
                REFUND_STATUS_FAIL, LocalDateTime.now(),
                refundNo, REFUND_STATUS_PROCESSING
        );
        if (updated > 0) {
            Refund refund = findByRefundNo(refundNo);
            redisTemplate.delete(REFUNDING_KEY_PREFIX + refund.getPaymentId());
            log.info("[退款失败] refundNo={}", refundNo);
            sendRefundResultMq(refund.getOrderId(), refund.getUserId(), false, refundNo);
        }
    }

    // ==================== 5. 支付超时检查（定时任务兜底） ====================

    /**
     * 支付超时检查
     * <p>
     * 定时任务：每 30 秒扫描一次，检查是否超时。
     * 使用 Lua 脚本保证原子性：检查状态 + 更新状态 + 返回结果。
     * </p>
     * <p>
     * 分布式考虑：加分布式锁保证只有一个实例执行。
     * </p>
     */
    public void checkPaymentTimeout() {
        // 分布式锁：防止多实例重复执行
        String lockKey = "payment:lock:timeout-check";
        String lockValue = java.util.UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, Duration.ofSeconds(30));
        if (Boolean.FALSE.equals(locked)) {
            log.debug("[支付超时检查] 未获取到分布式锁，跳过本次检查");
            return;
        }

        try {
            // Lua 脚本原子操作：检查 Redis 中的支付状态，如果待支付且超时则标记为失败
            Long timeout = System.currentTimeMillis() - PAY_TIMEOUT_MS;
            Long result = redisTemplate.execute(paymentTimeoutScript,
                    java.util.Collections.singletonList("payment:status:*"),
                    String.valueOf(timeout), String.valueOf(System.currentTimeMillis()));

            log.debug("[支付超时检查] 扫描完成: result={}", result);
        } finally {
            // Lua 安全释放锁（只释放自己持有的锁，防止误删其他实例的锁）
            safeUnlock(lockKey, lockValue);
        }
    }

    // ==================== 6. 退款超时检查 ====================

    /**
     * 退款超时检查
     * <p>
     * 定时任务：每 60 秒扫描一次，检查退款中超时（15 天）的退款单。
     * 超时的退款单标记为"退款关闭"。
     * </p>
     * <p>
     * 分布式安全：加分布式锁保证只有一个实例执行。
     * </p>
     */
    public void checkRefundTimeout() {
        String lockKey = "payment:lock:refund-timeout-check";
        String lockValue = java.util.UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, Duration.ofSeconds(60));
        if (Boolean.FALSE.equals(locked)) {
            return;
        }

        try {
            // 查询退款中超时（15天）的退款单
            LocalDateTime timeoutThreshold = LocalDateTime.now().minusDays(15);
            List<RefundTimeoutRecord> timeoutRefunds = paymentJdbcTemplate.query(
                    "SELECT id, refund_no, payment_id, order_id, user_id FROM t_refund " +
                            "WHERE status = 0 AND created_at < ? AND deleted = 0",
                    (rs, rowNum) -> new RefundTimeoutRecord(
                            rs.getLong("id"),
                            rs.getString("refund_no"),
                            rs.getLong("payment_id"),
                            rs.getLong("order_id"),
                            rs.getLong("user_id")
                    ),
                    timeoutThreshold
            );

            for (RefundTimeoutRecord record : timeoutRefunds) {
                // 乐观锁更新为"退款关闭"
                int updated = paymentJdbcTemplate.update(
                        "UPDATE t_refund SET status = ?, updated_at = ? " +
                                "WHERE id = ? AND status = ?",
                        REFUND_STATUS_CLOSED, LocalDateTime.now(),
                        record.id(), REFUND_STATUS_PROCESSING
                );
                if (updated > 0) {
                    redisTemplate.delete(REFUNDING_KEY_PREFIX + record.paymentId());
                    log.info("[退款超时] 退款单已关闭: refundNo={}", record.refundNo());
                    sendRefundResultMq(record.orderId(), record.userId(), false, record.refundNo());
                }
            }
        } finally {
            safeUnlock(lockKey, lockValue);
        }
    }

    /**
     * 退款超时记录 DTO
     */
    private record RefundTimeoutRecord(Long id, String refundNo, Long paymentId, Long orderId, Long userId) {
    }

    /**
     * Lua 安全释放分布式锁
     * <p>
     * 只有当锁的 value 等于当前持有者的 value 时才删除。
     * 防止业务执行超时后误删其他实例的锁。
     * </p>
     */
    private void safeUnlock(String lockKey, String lockValue) {
        try {
            org.springframework.data.redis.core.script.DefaultRedisScript<Long> script =
                    new org.springframework.data.redis.core.script.DefaultRedisScript<>(UNLOCK_SCRIPT, Long.class);
            redisTemplate.execute(script, java.util.Collections.singletonList(lockKey), lockValue);
        } catch (Exception e) {
            log.warn("[支付] 释放锁异常(不影响业务): key={}", lockKey, e);
        }
    }

    // ==================== 7. 对账机制 ====================

    /**
     * 对账：核对支付单与订单状态是否一致
     * <p>
     * 定时任务：每天凌晨执行。
     * 对账逻辑：
     * 1. 查询所有支付成功的支付单
     * 2. 通过 Feign 调用订单服务查询对应订单状态
     * 3. 如果订单不是"已支付"状态，记录告警日志
     * 4. 发现不一致时记录告警日志，由人工介入或自动修复
     * </p>
     * <p>
     * 分布式安全：加分布式锁保证只有一个实例执行。
     * </p>
     */
    public void reconcile() {
        String lockKey = "payment:lock:reconcile";
        String lockValue = java.util.UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, Duration.ofMinutes(5));
        if (Boolean.FALSE.equals(locked)) {
            return;
        }

        try {
            log.info("[对账] 开始执行支付对账...");
            // 查询所有支付成功的记录
            List<ReconcileRecord> records = paymentJdbcTemplate.query(
                    "SELECT order_id, payment_no FROM t_payment " +
                            "WHERE status = 1 AND deleted = 0",
                    (rs, rowNum) -> new ReconcileRecord(
                            rs.getLong("order_id"),
                            rs.getString("payment_no")
                    )
            );

            int inconsistent = 0;
            for (ReconcileRecord record : records) {
                try {
                    R<BigDecimal> payAmountResult = orderFeignClient.getOrderPayAmount(record.orderId());

                    if (payAmountResult == null || !payAmountResult.isSuccess()) {
                        log.error("[对账] 订单服务不可达: orderId={}, paymentNo={}, code={}",
                                record.orderId(), record.paymentNo(),
                                payAmountResult != null ? payAmountResult.getCode() : -1);
                        continue;
                    }

                    BigDecimal payAmount = payAmountResult.getData();
                    if (payAmount != null && payAmount.compareTo(BigDecimal.ZERO) > 0) {
                        log.error("[对账] 不一致: 支付成功但订单仍待支付, orderId={}, paymentNo={}",
                                record.orderId(), record.paymentNo());
                        inconsistent++;
                        orderFeignClient.notifyPaySuccess(record.orderId(), record.paymentNo());
                        log.info("[对账] 已触发补偿通知: orderId={}", record.orderId());
                    } else {
                        log.debug("[对账] 一致: orderId={}, paymentNo={}", record.orderId(), record.paymentNo());
                    }
                } catch (Exception e) {
                    log.error("[对账] 处理异常: orderId={}, paymentNo={}", record.orderId(), record.paymentNo(), e);
                }
            }

            if (inconsistent > 0) {
                log.warn("[对账] 发现 {} 条不一致记录，已触发补偿通知", inconsistent);
            }
            log.info("[对账] 支付对账完成: totalRecords={}, inconsistent={}", records.size(), inconsistent);
        } finally {
            safeUnlock(lockKey, lockValue);
        }
    }

    /**
     * 对账记录 DTO
     */
    private record ReconcileRecord(Long orderId, String paymentNo) {
    }

    // ==================== 8. 查询接口 ====================

    /**
     * 根据 orderId 查询支付状态
     */
    public R<PaymentVO> getPaymentStatus(Long orderId) {
        Payment payment = findByOrderId(orderId);
        if (payment == null) {
            return R.fail(ResultCode.PAYMENT_FAIL, "支付单不存在");
        }
        return R.ok(buildPaymentVO(payment));
    }

    // ==================== 私有辅助方法 ====================

    private Payment findById(Long id) {
        return paymentJdbcTemplate.query(
                "SELECT id, order_id, user_id, payment_no, amount, pay_type, status, paid_at, deleted, created_at, updated_at " +
                        "FROM t_payment WHERE id = ? AND deleted = 0",
                rs -> {
                    if (rs.next()) {
                        Payment p = new Payment();
                        p.setId(rs.getLong("id"));
                        p.setOrderId(rs.getLong("order_id"));
                        p.setUserId(rs.getLong("user_id"));
                        p.setPaymentNo(rs.getString("payment_no"));
                        p.setAmount(rs.getBigDecimal("amount"));
                        p.setPayType(rs.getInt("pay_type"));
                        p.setStatus(rs.getInt("status"));
                        p.setPaidAt(rs.getTimestamp("paid_at") != null ? rs.getTimestamp("paid_at").toLocalDateTime() : null);
                        p.setDeleted(rs.getInt("deleted"));
                        p.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
                        p.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
                        return p;
                    }
                    return null;
                },
                id
        );
    }

    private Payment findByOrderId(Long orderId) {
        return paymentJdbcTemplate.query(
                "SELECT id, order_id, user_id, payment_no, amount, pay_type, status, paid_at, deleted, created_at, updated_at " +
                        "FROM t_payment WHERE order_id = ? AND deleted = 0 ORDER BY created_at DESC LIMIT 1",
                rs -> {
                    if (rs.next()) {
                        Payment p = new Payment();
                        p.setId(rs.getLong("id"));
                        p.setOrderId(rs.getLong("order_id"));
                        p.setUserId(rs.getLong("user_id"));
                        p.setPaymentNo(rs.getString("payment_no"));
                        p.setAmount(rs.getBigDecimal("amount"));
                        p.setPayType(rs.getInt("pay_type"));
                        p.setStatus(rs.getInt("status"));
                        p.setPaidAt(rs.getTimestamp("paid_at") != null ? rs.getTimestamp("paid_at").toLocalDateTime() : null);
                        p.setDeleted(rs.getInt("deleted"));
                        p.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
                        p.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
                        return p;
                    }
                    return null;
                },
                orderId
        );
    }

    private Payment findByPaymentNo(String paymentNo) {
        return paymentJdbcTemplate.query(
                "SELECT id, order_id, user_id, payment_no, amount, pay_type, status, paid_at, deleted, created_at, updated_at " +
                        "FROM t_payment WHERE payment_no = ? AND deleted = 0",
                rs -> {
                    if (rs.next()) {
                        Payment p = new Payment();
                        p.setId(rs.getLong("id"));
                        p.setOrderId(rs.getLong("order_id"));
                        p.setUserId(rs.getLong("user_id"));
                        p.setPaymentNo(rs.getString("payment_no"));
                        p.setAmount(rs.getBigDecimal("amount"));
                        p.setPayType(rs.getInt("pay_type"));
                        p.setStatus(rs.getInt("status"));
                        p.setPaidAt(rs.getTimestamp("paid_at") != null ? rs.getTimestamp("paid_at").toLocalDateTime() : null);
                        p.setDeleted(rs.getInt("deleted"));
                        p.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
                        p.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
                        return p;
                    }
                    return null;
                },
                paymentNo
        );
    }

    private Refund findByRefundNo(String refundNo) {
        return paymentJdbcTemplate.query(
                "SELECT id, payment_id, order_id, user_id, refund_no, refund_amount, reason, status, refund_type, refund_channel, success_at, deleted, created_at, updated_at " +
                        "FROM t_refund WHERE refund_no = ? AND deleted = 0",
                rs -> {
                    if (rs.next()) {
                        Refund r = new Refund();
                        r.setId(rs.getLong("id"));
                        r.setPaymentId(rs.getLong("payment_id"));
                        r.setOrderId(rs.getLong("order_id"));
                        r.setUserId(rs.getLong("user_id"));
                        r.setRefundNo(rs.getString("refund_no"));
                        r.setRefundAmount(rs.getBigDecimal("refund_amount"));
                        r.setReason(rs.getString("reason"));
                        r.setStatus(rs.getInt("status"));
                        r.setRefundType(rs.getInt("refund_type"));
                        r.setRefundChannel(rs.getInt("refund_channel"));
                        r.setSuccessAt(rs.getTimestamp("success_at") != null ? rs.getTimestamp("success_at").toLocalDateTime() : null);
                        r.setDeleted(rs.getInt("deleted"));
                        r.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
                        r.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
                        return r;
                    }
                    return null;
                },
                refundNo
        );
    }

    private BigDecimal getRefundedAmount(Long paymentId) {
        return paymentJdbcTemplate.query(
                "SELECT COALESCE(SUM(refund_amount), 0) FROM t_refund " +
                        "WHERE payment_id = ? AND status = 1 AND deleted = 0",
                rs -> {
                    if (rs.next()) {
                        return rs.getBigDecimal(1);
                    }
                    return BigDecimal.ZERO;
                },
                paymentId
        );
    }

    /**
     * 生成支付流水号（Redis 自增，格式：PAY20260516000001）
     * <p>
     * 使用 IdGeneratorUtil.nextSerialNo 保证：
     * 1. 全局唯一（Redis INCR 原子自增）
     * 2. 趋势递增（日期前缀 + 序号）
     * 3. 高并发安全（Redis 单线程模型保证 INCR 原子性）
     * </p>
     */
    private String generatePaymentNo() {
        return idGeneratorUtil.nextSerialNo("PAY");
    }

    /**
     * 生成退款流水号（Redis 自增，格式：REFUND20260516000001）
     */
    private String generateRefundNo() {
        return idGeneratorUtil.nextSerialNo("REFUND");
    }

    /**
     * 生成支付记录 ID（雪花算法）
     * <p>
     * 使用 MyBatis-Plus IdWorker 雪花算法保证：
     * 1. 全局唯一（WorkerId + 时间戳 + 序列号）
     * 2. 趋势递增（有利于 B+ 树索引性能）
     * 3. 本地生成无网络开销（极高并发下性能优异）
     * </p>
     */
    private Long generatePaymentId() {
        return idGeneratorUtil.nextId();
    }

    /**
     * 生成退款记录 ID（雪花算法）
     */
    private Long generateRefundId() {
        return idGeneratorUtil.nextId();
    }

    private boolean isMockMode(Integer payType) {
        return payType != null && payType == 99;
    }

    private PaymentVO buildPaymentVO(Payment payment) {
        String statusDesc;
        switch (payment.getStatus()) {
            case 0: statusDesc = "待支付"; break;
            case 1: statusDesc = "支付成功"; break;
            case 2: statusDesc = "支付失败"; break;
            case 3: statusDesc = "已退款"; break;
            default: statusDesc = "未知"; break;
        }
        return PaymentVO.builder()
                .id(payment.getId())
                .orderId(payment.getOrderId())
                .paymentNo(payment.getPaymentNo())
                .amount(payment.getAmount())
                .payType(payment.getPayType())
                .status(payment.getStatus())
                .statusDesc(statusDesc)
                .paidAt(payment.getPaidAt())
                .createdAt(payment.getCreatedAt())
                .build();
    }

    // ==================== MQ 消息发送 ====================

    private void sendPayResultMq(Long orderId, Long userId, boolean success, String tradeNo) {
        try {
            String topic = "PAY_RESULT_TOPIC";
            String tag = success ? "PAY_SUCCESS" : "PAY_FAIL";
            String destination = topic + ":" + tag;
            String key = "PAY_" + orderId;

            Map<String, Object> bodyMap = new java.util.LinkedHashMap<>();
            bodyMap.put("orderId", orderId);
            bodyMap.put("userId", userId);
            bodyMap.put("success", success);
            bodyMap.put("tradeNo", tradeNo != null ? tradeNo : "");
            String body = objectMapper.writeValueAsString(bodyMap);
            Message<String> message = MessageBuilder.withPayload(body)
                    .setHeader("KEYS", key)
                    .build();
            rocketMQTemplate.syncSend(destination, message);
            log.info("[支付结果MQ] 发送成功: topic={}, tag={}, orderId={}", topic, tag, orderId);
        } catch (Exception e) {
            log.error("[支付结果MQ] 发送失败: orderId={}", orderId, e);
            // MQ 发送失败不影响支付状态更新，由对账兜底
        }
    }

    private void sendRefundResultMq(Long orderId, Long userId, boolean success, String refundNo) {
        try {
            String topic = "REFUND_RESULT_TOPIC";
            String tag = success ? "REFUND_SUCCESS" : "REFUND_FAIL";
            String destination = topic + ":" + tag;
            String key = "REFUND_" + orderId;

            Map<String, Object> bodyMap = new java.util.LinkedHashMap<>();
            bodyMap.put("orderId", orderId);
            bodyMap.put("userId", userId);
            bodyMap.put("success", success);
            bodyMap.put("refundNo", refundNo != null ? refundNo : "");
            String body = objectMapper.writeValueAsString(bodyMap);
            Message<String> message = MessageBuilder.withPayload(body)
                    .setHeader("KEYS", key)
                    .build();
            rocketMQTemplate.syncSend(destination, message);
            log.info("[退款结果MQ] 发送成功: topic={}, tag={}, orderId={}", topic, tag, orderId);
        } catch (Exception e) {
            log.error("[退款结果MQ] 发送失败: orderId={}", orderId, e);
        }
    }
}
