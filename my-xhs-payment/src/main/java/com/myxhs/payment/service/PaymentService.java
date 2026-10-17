package com.myxhs.payment.service;

import com.myxhs.common.exception.BizException;
import com.myxhs.common.id.IdGeneratorUtil;
import com.myxhs.common.metrics.BusinessMetrics;
import com.myxhs.common.response.R;
import com.myxhs.common.response.ResultCode;
import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.payment.dto.request.PayCreateRequest;
import com.myxhs.payment.dto.request.RefundByOrderRequest;
import com.myxhs.payment.dto.request.RefundRequest;
import com.myxhs.payment.dto.response.PaymentVO;
import com.myxhs.payment.dto.response.RefundVO;
import com.myxhs.payment.entity.Payment;
import com.myxhs.payment.entity.Refund;
import com.myxhs.payment.feign.OrderFeignClient;
import com.myxhs.payment.strategy.PayChannelStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
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
import java.util.concurrent.TimeUnit;

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
    private final StringRedisTemplate stringRedisTemplate;
    private final RocketMQTemplate rocketMQTemplate;
    private final RedissonClient redissonClient;
    private final Map<Integer, PayChannelStrategy> payChannelStrategyMap;
    private final com.myxhs.payment.simulator.PayCallbackSimulator callbackSimulator;
    private final OrderFeignClient orderFeignClient;
    private final IdGeneratorUtil idGeneratorUtil;
    private final BusinessMetrics businessMetrics;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final com.myxhs.payment.mapper.PaymentEventMapper paymentEventMapper;

    /** 支付事件落库线程池（可观测性）：DiscardPolicy——事件可容忍丢失，绝不影响资金主流程 */
    private static final java.util.concurrent.ExecutorService PAYMENT_EVENT_EXECUTOR =
            new com.myxhs.common.trace.MdcAwareExecutorService(
                    new java.util.concurrent.ThreadPoolExecutor(
                            1, 2, 60, java.util.concurrent.TimeUnit.SECONDS,
                            new java.util.concurrent.LinkedBlockingQueue<>(500),
                            r -> { Thread t = new Thread(r, "pay-event"); t.setDaemon(true); return t; },
                            // 事件可容忍丢失，但不能静默：拒绝时打日志（排障可查"事件为何缺行"）
                            (r, executor) -> log.error("[支付事件] 线程池拒绝(队列满), 事件丢失: poolSize={}, queue={}",
                                    executor.getPoolSize(), executor.getQueue().size())
                    ));

    /** Lua 脚本：安全释放分布式锁（只释放自己持有的锁） */
    private static final String UNLOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                    "return redis.call('del', KEYS[1]) " +
                    "else return 0 end";

    // ==================== 常量 ====================

    /** 退款中状态 Redis Key 前缀（防重复退款） */
    private static final java.time.format.DateTimeFormatter TIME_FORMATTER =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String REFUNDING_KEY_PREFIX = "myxhs:payment:refunding:";
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
    /** 支付状态：已关闭（ITER-002 A1：换渠道关旧 / 订单终结关闭 pending；未支付≠失败） */
    private static final int STATUS_CLOSED = 4;

    /** 置失败核实方式：渠道已确认未支付（ITER-002 阶段④，GAP-2） */
    private static final int VERIFY_METHOD_CHANNEL_UNPAID = 1;
    /** 置失败核实方式：超时未核实（存量数据标记；由对账核查分支重查渠道） */
    private static final int VERIFY_METHOD_UNVERIFIED = 2;

    /** 支付超时阈值（毫秒；ITER-002 阶段④配置化——原硬编码常量） */
    @org.springframework.beans.factory.annotation.Value("${pay.timeout.ms:1800000}")
    private long payTimeoutMs = 30 * 60 * 1000L;

    /** 退款中超时天数（ITER-002 阶段④配置化——原硬编码 15） */
    @org.springframework.beans.factory.annotation.Value("${refund.timeout.days:15}")
    private int refundTimeoutDays = 15;

    /** 退款状态：退款中 */
    private static final int REFUND_STATUS_PROCESSING = 0;
    /** 退款状态：退款成功 */
    private static final int REFUND_STATUS_SUCCESS = 1;
    /** 退款状态：退款失败 */
    private static final int REFUND_STATUS_FAIL = 2;
    /** 退款状态：退款关闭 */
    private static final int REFUND_STATUS_CLOSED = 3;
    // ITER-002 阶段④：PAY_TIMEOUT_MS 常量已由配置化字段 payTimeoutMs 取代（@Value pay.timeout.ms）

    // ==================== 1. 创建支付单 ====================

    /**
     * 创建支付单（ITER-002 A1：一订单最多一条"活着的"支付单）
     * <p>
     * 流程：
     * 1. 幂等：Redisson 分布式锁（同订单串行）+ DB 唯一索引 uk_order_pending 兜底（纵深防御）
     * 2. P1-1 回查订单（仅待付款可支付）+ 金额校验（订单实付为准）
     * 3. 查 pending：同渠道 → 复用（返回同一 paymentNo）；换渠道 → 关旧渠道交易 + 旧单置已关闭(4) → 新建
     * 4. 插入支付记录（status=0）+ 调渠道（Mock 同步成功 / 支付宝微信异步等待回调）
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
        Long paymentRecordId = null;

        // 记录支付尝试
        businessMetrics.recordPaymentCallback("attempt");

        // 1. 幂等校验：Redisson 分布式锁 + DB 乐观锁双重保障
        //    Redisson 对 Sentinel 主从切换有更好的支持（自动重试 + Watchdog 续期），
        //    即使主从切换导致锁短暂丢失，DB 乐观锁（WHERE status = 待支付）作为最终防线。
        RLock payLock = redissonClient.getLock("myxhs:lock:payment:pay:" + orderId);
        boolean locked = false;
        try {
            locked = payLock.tryLock(3, 10, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("[支付] 获取分布式锁超时: orderId={}, userId={}", orderId, userId);
                throw new BizException(ResultCode.INTERNAL_ERROR, "系统繁忙，请稍后再试");
            }

            // 【P1-1】支付前回查订单状态：仅待付款(0)订单可支付，防止对已取消/已支付的订单发起支付（钱货两空）
            R<Integer> statusResp = orderFeignClient.getOrderStatus(orderId);
            Integer orderStatus = (statusResp != null && statusResp.isSuccess()) ? statusResp.getData() : null;
            if (orderStatus == null) {
                // 订单不存在或订单服务不可用：无法确认订单可支付，为资金安全拒绝支付
                log.warn("[支付] 无法确认订单状态，拒绝支付: orderId={}", orderId);
                throw new BizException(ResultCode.ORDER_NOT_FOUND, "订单不存在或不可支付");
            }
            if (orderStatus != 0) {
                log.warn("[支付] 订单状态非待付款，拒绝支付: orderId={}, orderStatus={}", orderId, orderStatus);
                throw new BizException(ResultCode.ORDER_STATUS_ERROR, "订单当前状态不允许支付");
            }

            // 金额校验（P1-1 补强）：与订单实付金额比对——order 侧 /pay-amount 注释明确"供支付服务校验"，
            // 原实现完全信任上游 amount，金额错误无兜底（篡改/传错都会按错额记账）
            R<java.math.BigDecimal> amountResp = orderFeignClient.getOrderPayAmount(orderId);
            java.math.BigDecimal orderPayAmount = (amountResp != null && amountResp.isSuccess())
                    ? amountResp.getData() : null;
            if (orderPayAmount == null) {
                log.warn("[支付] 无法确认订单金额，拒绝支付: orderId={}", orderId);
                throw new BizException(ResultCode.ORDER_NOT_FOUND, "订单金额不可用，暂不能支付");
            }
            if (request.getAmount() == null || request.getAmount().compareTo(orderPayAmount) != 0) {
                log.error("[支付] 支付金额与订单实付不一致，拒绝支付: orderId={}, 请求={}, 订单={}",
                        orderId, request.getAmount(), orderPayAmount);
                throw new BizException(ResultCode.PARAM_INVALID, "支付金额与订单金额不一致");
            }

            // 2. 一订单最多一条"活着的"支付单（ITER-002 A1）：
            //    - 同渠道 → 复用（返回同一 paymentNo，补记 REUSE 事件；刷新待回调标记）
            //    - 换渠道 → 关旧渠道交易 + 旧单置已关闭(4) → 继续新建
            Payment pending = findPendingByOrderId(orderId);
            if (pending != null && pending.getPayType() != null && pending.getPayType().equals(payType)) {
                recordPaymentEvent(pending.getPaymentNo(), orderId, userId, "REUSE", null, null);
                if (!isMockMode(payType)) {
                    // 刷新待回调标记（幂等覆盖；保证复用后模拟回调链路仍在）
                    callbackSimulator.registerCallback(pending.getPaymentNo(), payType);
                }
                log.info("[支付] 复用既有支付单: orderId={}, paymentNo={}, payType={}",
                        orderId, pending.getPaymentNo(), payType);
                return R.ok(buildPaymentVO(pending));
            }
            if (pending != null) {
                closePaymentForSwitch(pending);
            }

            // 3. 生成支付流水号 + 插入支付记录（status=0 待支付）
            String paymentNo = generatePaymentNo();
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
            try {
                paymentJdbcTemplate.update(
                        "INSERT INTO t_payment (id, order_id, user_id, payment_no, amount, pay_type, status, deleted, created_at, updated_at) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        payment.getId(), payment.getOrderId(), payment.getUserId(),
                        payment.getPaymentNo(), payment.getAmount(), payment.getPayType(),
                        payment.getStatus(), payment.getDeleted(),
                        payment.getCreatedAt(), payment.getUpdatedAt()
                );
            } catch (org.springframework.dao.DuplicateKeyException dup) {
                // uk_order_pending 兜底（并发纵深防御：锁失效/多实例时钟漂移等）：重查复用既有 pending
                Payment existing = findPendingByOrderId(orderId);
                if (existing != null) {
                    if (!isMockMode(existing.getPayType())) {
                        callbackSimulator.registerCallback(existing.getPaymentNo(), existing.getPayType());
                    }
                    log.warn("[支付] 并发建单冲突，复用既有支付单: orderId={}, paymentNo={}", orderId, existing.getPaymentNo());
                    return R.ok(buildPaymentVO(existing));
                }
                throw dup; // 非 pending 唯一键冲突（如 uk_payment_no）→ 向上抛
            }
            paymentRecordId = payment.getId();
            log.info("[支付] 支付记录已创建: paymentNo={}, orderId={}, amount={}", paymentNo, orderId, amount);

            // 可观测性：支付单创建事件（异步落库，失败不影响资金主流程）
            recordPaymentEvent(paymentNo, orderId, userId, "CREATE", null, null);

            // 4. 通过策略模式调用对应支付渠道
            PayChannelStrategy strategy = payChannelStrategyMap.get(payType);
            if (strategy == null) {
                log.error("[支付] 未找到支付渠道策略: payType={}", payType);
                throw new BizException(ResultCode.PAYMENT_FAIL, "不支持的支付方式");
            }
            // 【零元单】全额券抵扣等场景 payAmount=0：渠道均要求金额>0，无渠道可调 →
            // 直接走支付成功路径（原实现 amount 校验拒绝 0 → 零元单永远发不起支付，只能等超时关单）
            if (amount != null && amount.compareTo(java.math.BigDecimal.ZERO) == 0) {
                log.info("[支付] 零元订单(全额抵扣)，跳过渠道直接记账成功: orderId={}, paymentNo={}", orderId, paymentNo);
                handlePaySuccessInternal(orderId, userId, paymentNo, "ZERO_AMOUNT");
                // 本地对象同步状态，返回 VO 与 DB 一致（原实现返回时仍显示"待支付"）
                payment.setStatus(STATUS_SUCCESS);
                payment.setPaidAt(LocalDateTime.now());
                return R.ok(buildPaymentVO(payment));
            }

            String tradeNo = strategy.pay(orderId, amount, paymentNo);
            log.info("[支付] 支付请求已发送: paymentNo={}, tradeNo={}, payType={}", paymentNo, tradeNo, payType);

            // 6. Mock 模式：同步标记成功；异步模式（支付宝/微信）：登记到模拟器，由定时任务发回调闭环
            if (isMockMode(payType)) {
                handlePaySuccessInternal(orderId, userId, paymentNo, tradeNo);
                // 本地对象同步状态（原实现同步成功后 VO 仍显示"待支付"）
                payment.setStatus(STATUS_SUCCESS);
                payment.setPaidAt(LocalDateTime.now());
            } else {
                // 修复：此前漏掉 registerCallback，导致异步支付回调永远不触发、订单停留在待付款
                callbackSimulator.registerCallback(paymentNo, payType);
            }

            return R.ok(buildPaymentVO(payment));

        } catch (BizException e) {
            // 业务异常：清理本次新建的支付记录（复用/冲突分支未产生新记录，paymentRecordId 为 null，天然不误删既有 pending）
            businessMetrics.recordPaymentCallback("fail");
            cleanupFailedPaymentRecord(paymentRecordId);
            throw e;
        } catch (Exception e) {
            // 非预期异常：同样清理本次新建记录
            businessMetrics.recordPaymentCallback("fail");
            log.error("[支付] 创建支付单异常: orderId={}, userId={}", orderId, userId, e);
            cleanupFailedPaymentRecord(paymentRecordId);
            throw new BizException(ResultCode.INTERNAL_ERROR, "支付失败: " + e.getMessage());
        } finally {
            // 安全释放 Redisson 分布式锁
            if (locked && payLock.isHeldByCurrentThread()) {
                payLock.unlock();
            }
        }
    }

    /** 清理本次新建但未完成的支付记录（仅针对本次插入；复用/冲突分支 paymentRecordId 为 null） */
    private void cleanupFailedPaymentRecord(Long paymentRecordId) {
        if (paymentRecordId == null) {
            return;
        }
        try {
            paymentJdbcTemplate.update("DELETE FROM t_payment WHERE id = ? AND status = 0 AND deleted = 0", paymentRecordId);
        } catch (Exception deleteEx) {
            log.error("[支付] 清理支付记录失败: paymentId={}", paymentRecordId, deleteEx);
        }
    }

    /**
     * 换渠道：关闭旧渠道交易 + 旧单置已关闭(4)（ITER-002 A1 阶段 2）
     * <p>
     * 顺序与语义：
     * 1. 先关渠道交易——关失败即中止切换（避免"渠道旧交易仍可支付 + 本地已关闭"的错位）；
     * 2. 旧单置已关闭（乐观锁；0 行 = 竞态已变 → 抛出让重试）；
     * 3. 补记 CLOSE 事件（归因：CHANNEL_SWITCH）。
     * </p>
     */
    private void closePaymentForSwitch(Payment old) {
        PayChannelStrategy oldStrategy = payChannelStrategyMap.get(old.getPayType());
        if (oldStrategy == null) {
            throw new BizException(ResultCode.PAYMENT_FAIL, "旧支付渠道不可用，无法切换");
        }
        try {
            oldStrategy.close(old.getPaymentNo());
        } catch (Exception e) {
            log.error("[支付] 关闭旧渠道交易失败，中止换渠道: orderId={}, paymentNo={}", old.getOrderId(), old.getPaymentNo(), e);
            throw new BizException(ResultCode.PAYMENT_FAIL, "渠道交易关闭失败，请稍后重试");
        }
        int closed = paymentJdbcTemplate.update(
                "UPDATE t_payment SET status = ?, updated_at = ? WHERE id = ? AND status = ? AND deleted = 0",
                STATUS_CLOSED, LocalDateTime.now(), old.getId(), STATUS_PENDING);
        if (closed == 0) {
            throw new BizException(ResultCode.PAYMENT_FAIL, "支付单状态已变化，请刷新后重试");
        }
        recordPaymentEvent(old.getPaymentNo(), old.getOrderId(), old.getUserId(), "CLOSE", "CHANNEL_SWITCH", "换渠道关闭旧支付单");
        log.info("[支付] 旧支付单已关闭(换渠道): orderId={}, paymentNo={}", old.getOrderId(), old.getPaymentNo());
    }

    /**
     * 订单终结：关闭该订单"活着的"（pending）支付单（ITER-002 阶段⑤ / 06 §七 阶段 3）
     * <p>
     * 由 {@link com.myxhs.payment.consumer.OrderTerminationConsumer} 订阅订单终结事件调用。
     * 幂等：无 pending → no-op；乐观锁条件更新（并发下只有一个赢家）；
     * 渠道 close 失败不阻断本地推进（记日志，对账兜底）。
     * </p>
     */
    public void closePendingOnTermination(Long orderId) {
        Payment pending = findPendingByOrderId(orderId);
        if (pending == null) {
            return; // 幂等：无可关闭的 pending（已支付/已关闭/无支付单）
        }
        try {
            PayChannelStrategy strategy = pending.getPayType() != null ? payChannelStrategyMap.get(pending.getPayType()) : null;
            if (strategy != null) {
                strategy.close(pending.getPaymentNo());
            }
        } catch (Exception e) {
            log.error("[支付-订单终结] 关闭渠道交易失败(对账兜底): orderId={}, paymentNo={}",
                    orderId, pending.getPaymentNo(), e);
        }
        int closed = paymentJdbcTemplate.update(
                "UPDATE t_payment SET status = ?, updated_at = ? WHERE id = ? AND status = ? AND deleted = 0",
                STATUS_CLOSED, LocalDateTime.now(), pending.getId(), STATUS_PENDING);
        if (closed > 0) {
            recordPaymentEvent(pending.getPaymentNo(), orderId, pending.getUserId(), "CLOSE", "ORDER_TERMINATED", "订单终结关闭 pending 支付单");
            log.info("[支付-订单终结] pending 支付单已关闭: orderId={}, paymentNo={}", orderId, pending.getPaymentNo());
        }
    }

    // ==================== 2. 处理支付回调 ====================

    /**
     * 处理支付回调（异步通知）
     * <p>
     * 由 PayCallbackSimulator 模拟回调（真实渠道接入时由回调端点调用，需补验签）后调用此方法。
     * 乐观锁保证幂等：只有"待支付"状态的支付单才能更新为"支付成功"。
     * </p>
     *
     * @param paymentNo 支付流水号
     * @param tradeNo   第三方交易号
     * @param success   是否支付成功
     */
    @Transactional(transactionManager = "paymentTransactionManager", rollbackFor = Exception.class)
    public void handlePayCallback(String paymentNo, String tradeNo, boolean success) {
        // 1. 查询支付单
        Payment payment = findByPaymentNo(paymentNo);
        if (payment == null) {
            log.warn("[支付回调] 支付单不存在: paymentNo={}", paymentNo);
            return; // 幂等：不抛异常，静默返回
        }

        // 2. 幂等校验：只有"待支付"状态才能处理回调
        if (payment.getStatus() != STATUS_PENDING) {
            // 迟到成功：支付单已离开"待支付"但渠道实际成功（用户已付）。两种场景：
            // ① 支付超时 Job 已置失败(2)（原修复：原实现静默 return → "用户已付、系统记失败、无退款通道"）；
            // ② 换渠道/订单终结已关闭(4)（深 Review 补充：渠道 close 与用户支付的竞态——真渠道边界）。
            // 统一：条件翻转 2|4→1 后复用成功链路——订单若已取消/不允许支付会被业务拒绝 → 自动全额退款；
            // 订单仍在待付款则正常支付成功（换渠道场景遗留的另一条 pending 由超时处置自清理）。
            if (success && (payment.getStatus() == STATUS_FAIL || payment.getStatus() == STATUS_CLOSED)) {
                int previousStatus = payment.getStatus();
                int flipped = paymentJdbcTemplate.update(
                        "UPDATE t_payment SET status = ?, paid_at = ?, updated_at = ? "
                                + "WHERE id = ? AND status = ? AND deleted = 0",
                        STATUS_SUCCESS, LocalDateTime.now(), LocalDateTime.now(), payment.getId(), previousStatus);
                if (flipped > 0) {
                    log.warn("[支付回调] 迟到成功(支付单已{}), 受理并走成功链路: paymentNo={}",
                            previousStatus == STATUS_FAIL ? "置失败" : "关闭", paymentNo);
                    // 注意：必须直接调 afterPaySuccess——此时状态已是 1，handlePaySuccessInternal 的
                    // "WHERE status=0" 守卫会失败并提前 return（通知/自动退款都不会执行）
                    afterPaySuccess(payment.getOrderId(), payment.getUserId(), paymentNo, tradeNo);
                    return;
                }
            }
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
                        "WHERE order_id = ? AND status = ? AND deleted = 0",
                STATUS_SUCCESS, LocalDateTime.now(), LocalDateTime.now(),
                orderId, STATUS_PENDING
        );

        if (updated == 0) {
            log.info("[支付成功] 乐观锁冲突（已处理）: orderId={}, paymentNo={}", orderId, paymentNo);
            return;
        }
        afterPaySuccess(orderId, userId, paymentNo, tradeNo);
    }

    /**
     * 支付成功后的副作用（通知订单 / MQ / 事件 / 自动退款）
     * <p>
     * 独立成方法的原因：迟到成功路径已把状态 2→1 翻转，不能再走
     * {@link #handlePaySuccessInternal} 的 "WHERE status=0" 守卫（否则守卫失败直接 return，
     * 通知与自动退款都不会执行——这正是"用户已付却既没记账也没退款"的坑）。
     * </p>
     */
    private void afterPaySuccess(Long orderId, Long userId, String paymentNo, String tradeNo) {
        // ITER-002 A1：原"支付中/状态"Redis 死键（只写不读）已随复用改造清理——防重由 uk_order_pending + 状态机承担

        log.info("[支付成功] orderId={}, paymentNo={}, tradeNo={}", orderId, paymentNo, tradeNo);

        // 可观测性：支付成功事件（状态已落定后记录）
        recordPaymentEvent(paymentNo, orderId, userId, "PAY_SUCCESS", null, null);

        businessMetrics.recordPaymentCallback("success");

        // 先同步通知订单服务支付成功，确认订单域接受这笔支付后再发 MQ。
        // P1-1：若订单明确返回业务失败（已取消/已支付，非瞬态503）→ 自动退款，避免钱货两空
        // 只有在订单接受后才发 MQ，否则 MQ 已发但退款后订单消费者可能误处理。
        boolean businessRejected = false;
        try {
            R<Void> nr = orderFeignClient.notifyPaySuccess(orderId, tradeNo);
            businessRejected = (nr != null && !nr.isSuccess()
                    && nr.getCode() != ResultCode.SERVICE_UNAVAILABLE.getCode());
        } catch (Exception e) {
            // 注意：order 侧 PayResultConsumer 已兜底消费 PAY_RESULT_TOPIC（Feign 同步失败/503 时收敛），
            // 真正兜底是 XXL-Job paymentNotifyCompensateJob（扫描 status=1 超窗未收敛的支付单重发通知）
            log.error("[支付成功] 通知订单服务失败(兜底依赖 paymentNotifyCompensateJob): orderId={}, tradeNo={}", orderId, tradeNo, e);
        }
        // 订单接收成功或 Feign 临时故障（503）时都发 MQ（预留，当前无消费端，正式兜底为 XXL-Job 补偿）；
        // 业务拒绝时不发 MQ 避免退款后乱序
        if (!businessRejected) {
            sendPayResultMq(orderId, userId, true, tradeNo);
        }
        if (businessRejected) {
            try {
                Payment p = findByPaymentNo(paymentNo);
                if (p != null && p.getUserId().equals(userId)) {
                    log.warn("[支付成功] 订单状态不允许支付(竞态取消/关单)，自动退款: orderId={}, paymentId={}", orderId, p.getId());
                    RefundRequest rr = new RefundRequest();
                    rr.setPaymentId(p.getId());
                    rr.setUserId(p.getUserId());
                    rr.setRefundAmount(p.getAmount());
                    rr.setReason("订单已取消/状态不允许支付，自动退款");
                    refund(rr);
                }
            } catch (Exception re) {
                log.error("[支付成功] 自动退款失败(需人工/对账处理): orderId={}", orderId, re);
            }
        }
    }

    /**
     * 内部方法：处理支付失败
     */
    private void handlePayFailInternal(Long orderId, Long userId, String paymentNo) {
        int updated = paymentJdbcTemplate.update(
                "UPDATE t_payment SET status = ?, verify_method = ?, updated_at = ? " +
                        "WHERE order_id = ? AND status = ? AND deleted = 0",
                STATUS_FAIL, VERIFY_METHOD_CHANNEL_UNPAID, LocalDateTime.now(),
                orderId, STATUS_PENDING
        );

        if (updated > 0) {
            log.info("[支付失败] orderId={}, paymentNo={}", orderId, paymentNo);
            // 可观测性：支付失败事件（Mock 渠道失败原因：渠道返回失败）
            recordPaymentEvent(paymentNo, orderId, userId, "PAY_FAIL", "CHANNEL_REJECT", "支付渠道返回失败");
            businessMetrics.recordPaymentCallback("fail");
            sendPayResultMq(orderId, userId, false, null);
            // 同步通知订单服务支付失败
            try {
                orderFeignClient.notifyPayFail(orderId);
            } catch (Exception e) {
                log.error("[支付失败] 通知订单服务失败(订单MQ消费者会兜底): orderId={}", orderId, e);
            }
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
    /**
     * 按订单查询退款单（内部：供售后"卡死/未知结果"恢复时核对支付域事实）
     * <p>
     * 售后重试前先查这里，用"是否已有成功退款"决定是置完成还是重新发起，
     * 避免 Feign 超时等"未知结果"场景下的盲目重试。
     * </p>
     */
    public R<java.util.List<RefundVO>> listRefundsByOrder(Long orderId) {
        java.util.List<RefundVO> list = paymentJdbcTemplate.query(
                "SELECT refund_no, payment_id, order_id, refund_amount, status, refund_type, reason, created_at, success_at"
                        + " FROM t_refund WHERE order_id = ? AND deleted = 0 ORDER BY created_at ASC",
                (rs, rowNum) -> {
                    RefundVO vo = new RefundVO();
                    vo.setRefundNo(rs.getString("refund_no"));
                    vo.setPaymentId(rs.getLong("payment_id"));
                    vo.setOrderId(rs.getLong("order_id"));
                    vo.setRefundAmount(rs.getBigDecimal("refund_amount"));
                    vo.setStatus(rs.getInt("status"));
                    vo.setRefundType(rs.getInt("refund_type"));
                    vo.setReason(rs.getString("reason"));
                    vo.setCreatedAt(formatTime(rs.getTimestamp("created_at")));
                    vo.setSuccessAt(formatTime(rs.getTimestamp("success_at")));
                    return vo;
                }, orderId);
        return R.ok(list);
    }

    private String formatTime(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime().format(TIME_FORMATTER);
    }

    /**
     * 按订单维度发起退款（售后场景）
     * <p>
     * 上游只持有 orderId：由支付域解析 orderId → 支付单，再复用 {@link #refund(RefundRequest)}
     * 的全套校验（分布式锁、退款中标记、归属校验、可退金额校验、累计退款语义），
     * 不在上游暴露支付域内部主键。
     * </p>
     */
    @Transactional(transactionManager = "paymentTransactionManager", rollbackFor = Exception.class)
    public R<String> refundByOrder(RefundByOrderRequest request, Long userId) {
        Payment payment = findByOrderId(request.getOrderId());
        if (payment == null) {
            return R.fail(ResultCode.PAYMENT_FAIL, "支付单不存在，无法退款");
        }
        Long ownerId = userId != null ? userId : payment.getUserId();
        if (!ownerId.equals(payment.getUserId())) {
            log.warn("[退款] 按订单退款归属校验失败: orderId={}, userId={}, paymentUserId={}",
                    request.getOrderId(), userId, payment.getUserId());
            return R.fail(ResultCode.FORBIDDEN, "支付单归属校验失败");
        }
        RefundRequest refundRequest = new RefundRequest();
        refundRequest.setPaymentId(payment.getId());
        refundRequest.setUserId(ownerId);
        refundRequest.setRefundAmount(request.getRefundAmount());
        refundRequest.setReason(request.getReason());
        refundRequest.setRefundType(request.getRefundType());
        refundRequest.setOutRefundNo(request.getOutRefundNo()); // ITER-002 阶段③：出金幂等键透传
        return doRefund(refundRequest);
    }

    @Transactional(transactionManager = "paymentTransactionManager", rollbackFor = Exception.class)
    public R<Void> refund(RefundRequest request) {
        R<String> result = doRefund(request);
        return result.isSuccess() ? R.ok() : R.fail(result.getCode(), result.getMessage());
    }

    /**
     * 退款实现：返回支付域退款单号（供上游精确归属，无需回查）
     */
    private R<String> doRefund(RefundRequest request) {
        Long paymentId = request.getPaymentId();
        Long userId = request.getUserId();
        BigDecimal refundAmount = request.getRefundAmount();
        String refundingKey = REFUNDING_KEY_PREFIX + paymentId;
        Long refundRecordId = null;

        // 1. 幂等校验：Redisson 分布式锁 + DB 乐观锁双重保障
        RLock refundLock = redissonClient.getLock("myxhs:lock:payment:refund:" + paymentId);
        boolean locked = false;
        try {
            locked = refundLock.tryLock(3, 10, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("[退款] 获取分布式锁超时: paymentId={}, userId={}", paymentId, userId);
                throw new BizException(ResultCode.INTERNAL_ERROR, "系统繁忙，请稍后再试");
            }

            // 出金幂等（ITER-002 阶段③）：同 out_refund_no → 返回既有退款单（幂等语义：同键同单；
            // 含"处理中"——重试/补偿重放拿到的始终是同一 refundNo，绝不重复出金）
            String outRefundNo = request.getOutRefundNo();
            if (outRefundNo != null && !outRefundNo.isBlank()) {
                Refund existingRefund = findRefundByOutRefundNo(outRefundNo);
                if (existingRefund != null) {
                    log.info("[退款] 幂等复用既有退款单: outRefundNo={}, refundNo={}", outRefundNo, existingRefund.getRefundNo());
                    return R.ok(existingRefund.getRefundNo());
                }
            }

            // 双重检查：获取锁后再次确认是否已在退款中
            String existingStatus = stringRedisTemplate.opsForValue().get(refundingKey);
            if (existingStatus != null) {
                log.warn("[退款] 重复退款请求（锁后检查）: paymentId={}", paymentId);
                throw new BizException(ResultCode.IDEMPOTENT_REJECT, "请勿重复退款");
            }

            // 设置 Redis 退款状态标记
            stringRedisTemplate.opsForValue().set(refundingKey, String.valueOf(userId), REFUNDING_KEY_TTL);

            // 2. 查询支付单
            Payment payment = findById(paymentId);
            if (payment == null) {
                throw new BizException(ResultCode.PAYMENT_FAIL, "支付单不存在");
            }
            // 归属校验: 防水平越权退款他人支付单
            if (!payment.getUserId().equals(userId)) {
                throw new BizException(ResultCode.PAYMENT_FAIL, "无权操作该支付单");
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
            refund.setOutRefundNo(outRefundNo); // ITER-002 阶段③：业务幂等键（可空）
            refund.setRefundAmount(refundAmount);
            refund.setReason(request.getReason());
            refund.setStatus(REFUND_STATUS_PROCESSING);
            refund.setRefundType(request.getRefundType() != null ? request.getRefundType() : 1);
            refund.setRefundChannel(1); // 默认原路退回
            refund.setDeleted(0);
            refund.setCreatedAt(LocalDateTime.now());
            refund.setUpdatedAt(LocalDateTime.now());
            try {
                paymentJdbcTemplate.update(
                        "INSERT INTO t_refund (id, payment_id, order_id, user_id, refund_no, out_refund_no, refund_amount, reason, status, refund_type, refund_channel, deleted, created_at, updated_at) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        refund.getId(), refund.getPaymentId(), refund.getOrderId(),
                        refund.getUserId(), refund.getRefundNo(), refund.getOutRefundNo(), refund.getRefundAmount(),
                        refund.getReason(), refund.getStatus(), refund.getRefundType(),
                        refund.getRefundChannel(), refund.getDeleted(),
                        refund.getCreatedAt(), refund.getUpdatedAt()
                );
            } catch (org.springframework.dao.DuplicateKeyException dup) {
                // uk_out_refund_no 兜底（并发同键）：重查返回既有退款单（不重复出金）
                if (outRefundNo != null && !outRefundNo.isBlank()) {
                    Refund existingRefund = findRefundByOutRefundNo(outRefundNo);
                    if (existingRefund != null) {
                        log.warn("[退款] 并发同键退款，复用既有退款单: outRefundNo={}, refundNo={}",
                                outRefundNo, existingRefund.getRefundNo());
                        return R.ok(existingRefund.getRefundNo());
                    }
                }
                throw dup;
            }
            refundRecordId = refund.getId();
            log.info("[退款] 退款单已创建: refundNo={}, paymentId={}, refundAmount={}", refundNo, paymentId, refundAmount);

            // 可观测性：退款事件（状态落定后记录）
            recordPaymentEvent(payment.getPaymentNo(), payment.getOrderId(), userId, "REFUND",
                    null, request.getReason());

            // 5. 通过策略模式调用对应支付渠道的退款
            PayChannelStrategy strategy = payChannelStrategyMap.get(payment.getPayType());
            if (strategy == null) {
                throw new BizException(ResultCode.PAYMENT_FAIL, "不支持的支付方式");
            }
            String refundTradeNo = strategy.refund(paymentId, refundNo, refundAmount, request.getReason());
            log.info("[退款] 退款请求已发送: refundNo={}, refundTradeNo={}", refundNo, refundTradeNo);

            // 6. Mock 模式：同步标记退款成功；异步渠道：注册退款回调模拟器（T-075 闭环）
            if (isMockMode(payment.getPayType())) {
                handleRefundSuccessInternal(refundNo);
            } else {
                callbackSimulator.registerRefundCallback(refundNo);
            }

            return R.ok(refundNo);

        } catch (BizException e) {
            // 业务异常：清理 Redis 标记（事务已回滚 DB 的 INSERT）
            stringRedisTemplate.delete(refundingKey);
            throw e;
        } catch (Exception e) {
            // 非预期异常：清理 Redis 标记
            log.error("[退款] 退款异常: paymentId={}, userId={}", paymentId, userId, e);
            stringRedisTemplate.delete(refundingKey);
            throw new BizException(ResultCode.INTERNAL_ERROR, "退款失败: " + e.getMessage());
        } finally {
            // 安全释放 Redisson 分布式锁
            if (locked && refundLock.isHeldByCurrentThread()) {
                refundLock.unlock();
            }
        }
    }

    // ==================== 4. 处理退款回调 ====================

    /**
     * 处理退款回调
     * <p>
     * 乐观锁保证幂等：只有"退款中"状态的退款单才能更新。
     * </p>
     */
    @Transactional(transactionManager = "paymentTransactionManager", rollbackFor = Exception.class)
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

        // 3. 更新支付单状态（T-076：仅"累计退款=支付金额"时置 3 已退款；部分退款保持 1 继续可退）
        //    原实现无条件置 3 → 部分退款后剩余金额不可再退（30009 支付单状态不允许退款）
        java.math.BigDecimal refundedTotal = getRefundedAmount(refund.getPaymentId());
        java.math.BigDecimal paymentAmount = paymentJdbcTemplate.queryForObject(
                "SELECT amount FROM t_payment WHERE id = ? AND deleted = 0",
                java.math.BigDecimal.class, refund.getPaymentId());
        if (paymentAmount != null && refundedTotal.compareTo(paymentAmount) >= 0) {
            paymentJdbcTemplate.update(
                    "UPDATE t_payment SET status = ?, updated_at = ? WHERE id = ? AND deleted = 0",
                    STATUS_REFUNDED, LocalDateTime.now(), refund.getPaymentId()
            );
        } else {
            log.info("[退款成功] 部分退款(累计{}<支付{}), 支付单保持已支付: paymentId={}",
                    refundedTotal, paymentAmount, refund.getPaymentId());
        }

        // 4. 清理 Redis（退款中标记）
        stringRedisTemplate.delete(REFUNDING_KEY_PREFIX + refund.getPaymentId());

        log.info("[退款成功] refundNo={}, paymentId={}, orderId={}", refundNo, refund.getPaymentId(), refund.getOrderId());

        // 5. T-076/T-077：仅全额退款才通知订单（REFUND_SUCCESS → 订单 5 + 释放库存）；
        //    T-077（注释已修正）：退款原仅 MQ（order 侧后已补 RefundResultConsumer 兜底消费）→
        //    全额退款补 Feign 直调 notifyRefundSuccess（与支付成功对称）；部分退款不通知订单
        if (paymentAmount != null && refundedTotal.compareTo(paymentAmount) >= 0) {
            sendRefundResultMq(refund.getOrderId(), refund.getUserId(), true, refundNo);
            try {
                R<Void> nr = orderFeignClient.notifyRefundSuccess(refund.getOrderId(), refundNo);
                if (nr == null || !nr.isSuccess()) {
                    log.error("[退款成功] Feign通知订单失败(REFUND_RESULT_TOPIC 兜底消费，状态可收敛): orderId={}, resp={}",
                            refund.getOrderId(), nr);
                }
            } catch (Exception e) {
                log.error("[退款成功] Feign通知订单异常(订单状态可能滞留): orderId={}", refund.getOrderId(), e);
            }
        } else {
            log.info("[退款成功] 部分退款，不通知订单状态变更: orderId={}", refund.getOrderId());
        }
    }

    /**
     * 内部方法：处理退款失败
     */
    private void handleRefundFailInternal(String refundNo) {
        int updated = paymentJdbcTemplate.update(
                "UPDATE t_refund SET status = ?, updated_at = ? " +
                        "WHERE refund_no = ? AND status = ? AND deleted = 0",
                REFUND_STATUS_FAIL, LocalDateTime.now(),
                refundNo, REFUND_STATUS_PROCESSING
        );
        if (updated > 0) {
            Refund refund = findByRefundNo(refundNo);
            stringRedisTemplate.delete(REFUNDING_KEY_PREFIX + refund.getPaymentId());
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
        // 分布式锁：防止多实例重复执行（防御性——XXL-Job 已保证单实例执行；
        // ITER-002 阶段④：TTL 与任务周期（30s）对齐为 2 分钟，异常退出后更快自愈）
        String lockKey = "myxhs:payment:lock:timeout-check";
        String lockValue = java.util.UUID.randomUUID().toString();
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, Duration.ofMinutes(2));
        if (Boolean.FALSE.equals(locked)) {
            log.debug("[支付超时检查] 未获取到分布式锁，跳过本次检查");
            return;
        }

        try {
            // T-062（2026-08-14）：原实现 SCAN 扫 status key + Lua now>timeoutTs 恒真判定——
            // ① status key TTL=30min 到点即消失，真正超时单扫不到；
            // ② 未超时的 status=0 单（刚创建）会被提前误标 '2'；
            // ③ 只改 Redis，DB t_payment 永远 0（状态不一致）。
            // 改为 DB 扫描（与 checkRefundTimeout/orderCloseJob 同模式）。
            // ITER-002 阶段④（GAP-1/2/3）：置失败前**按渠道真相处置**（查渠道 → 矩阵），不再"到点直接置失败"。
            java.time.LocalDateTime deadline = LocalDateTime.now()
                    .minus(payTimeoutMs, java.time.temporal.ChronoUnit.MILLIS);
            int totalMarked = 0;
            int totalConverged = 0;
            int totalLeft = 0;
            while (true) {
                java.util.List<java.util.Map<String, Object>> rows = paymentJdbcTemplate.queryForList(
                        "SELECT id, order_id, user_id, payment_no, pay_type FROM t_payment " +
                                "WHERE status = ? AND created_at < ? AND deleted = 0 LIMIT 100",
                        STATUS_PENDING, deadline);
                if (rows.isEmpty()) {
                    break;
                }
                for (java.util.Map<String, Object> row : rows) {
                    Long pid = ((Number) row.get("id")).longValue();
                    Long orderId = ((Number) row.get("order_id")).longValue();
                    Long uid = ((Number) row.get("user_id")).longValue();
                    String paymentNo = (String) row.get("payment_no");
                    Integer payType = row.get("pay_type") != null ? ((Number) row.get("pay_type")).intValue() : null;
                    switch (disposeTimeoutPayment(pid, orderId, uid, paymentNo, payType)) {
                        case MARKED_FAIL -> totalMarked++;
                        case CONVERGED_SUCCESS -> totalConverged++;
                        case LEFT -> totalLeft++;
                    }
                }
                if (rows.size() < 100) {
                    break;
                }
            }
            log.info("[支付超时检查] 完成: 置失败 {} 单, 收敛成功 {} 单, 不处置 {} 单",
                    totalMarked, totalConverged, totalLeft);
        } finally {
            // Lua 安全释放锁（只释放自己持有的锁，防止误删其他实例的锁）
            safeUnlock(lockKey, lockValue);
        }
    }

    /** 超时处置结果（ITER-002 阶段④） */
    private enum TimeoutDisposition { MARKED_FAIL, CONVERGED_SUCCESS, LEFT }

    /**
     * 单笔超时处置（ITER-002 阶段④：按渠道真相处置，GAP-1/2/3）
     * <p>
     * 矩阵：渠道=成功 → 本地收敛成功（复用成功收敛体：乐观锁 + 通知订单 + 自动退款语义）；
     * 渠道=明确未支付 → close 渠道交易 + 置失败 + 记录核实证据（verify_method=1）；
     * 渠道不可达/无法判定（null）→ **不处置**（"不知道"≠"没付"，留下一轮重扫）。
     * </p>
     */
    private TimeoutDisposition disposeTimeoutPayment(Long pid, Long orderId, Long uid, String paymentNo, Integer payType) {
        // ① 查渠道事实（未知结果绝不当作"没付"）
        PayChannelStrategy strategy = payType != null ? payChannelStrategyMap.get(payType) : null;
        Integer channelStatus;
        try {
            channelStatus = strategy != null ? strategy.queryPayStatus(paymentNo) : null;
        } catch (Exception e) {
            log.warn("[支付超时检查] 渠道查询异常, 不处置留下一轮: paymentId={}", pid, e);
            channelStatus = null;
        }
        if (channelStatus == null) {
            log.warn("[支付超时检查] 渠道不可达/无法判定, 不处置留下一轮: paymentId={}, paymentNo={}", pid, paymentNo);
            return TimeoutDisposition.LEFT;
        }
        if (channelStatus == 1) {
            // ② 渠道已支付（回调丢失场景）→ 本地收敛为成功（tradeNo 位传 paymentNo，与对账路径同口径）
            log.warn("[支付超时检查] 渠道查得已支付, 本地收敛: paymentId={}, orderId={}", pid, orderId);
            handlePaySuccessInternal(orderId, uid, paymentNo, paymentNo);
            recordPaymentEvent(paymentNo, orderId, uid, "TIMEOUT_CHANNEL_PAID", null, "渠道查得已支付，本地收敛");
            return TimeoutDisposition.CONVERGED_SUCCESS;
        }
        // ③ 渠道明确未支付 → 关闭渠道交易（失败不阻断：本地状态推进 + 对账兜底） + 置失败 + 核实证据
        try {
            if (strategy != null) {
                strategy.close(paymentNo);
            }
        } catch (Exception e) {
            log.error("[支付超时检查] 关闭渠道交易失败(对账兜底): paymentId={}", pid, e);
        }
        int updated = paymentJdbcTemplate.update(
                "UPDATE t_payment SET status = ?, verify_method = ?, updated_at = ? WHERE id = ? AND status = ? AND deleted = 0",
                STATUS_FAIL, VERIFY_METHOD_CHANNEL_UNPAID, LocalDateTime.now(), pid, STATUS_PENDING);
        if (updated > 0) {
            recordPaymentEvent(paymentNo, orderId, uid, "TIMEOUT",
                    "PAY_TIMEOUT", "支付超时未完成（渠道已确认未支付）");
            log.info("[支付超时检查] 标记超时: paymentId={}, orderId={}", pid, orderId);
            return TimeoutDisposition.MARKED_FAIL;
        }
        return TimeoutDisposition.LEFT; // 乐观锁 0 行 = 并发已处置
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
        String lockKey = "myxhs:payment:lock:refund-timeout-check";
        String lockValue = java.util.UUID.randomUUID().toString();
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, Duration.ofSeconds(60));
        if (Boolean.FALSE.equals(locked)) {
            return;
        }

        try {
            // 查询退款中超时（配置天数；ITER-002 阶段④配置化）的退款单
            LocalDateTime timeoutThreshold = LocalDateTime.now().minusDays(refundTimeoutDays);
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
                        "WHERE id = ? AND status = ? AND deleted = 0",
                        REFUND_STATUS_CLOSED, LocalDateTime.now(),
                        record.id(), REFUND_STATUS_PROCESSING
                );
                if (updated > 0) {
                    stringRedisTemplate.delete(REFUNDING_KEY_PREFIX + record.paymentId());
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
            stringRedisTemplate.execute(script, java.util.Collections.singletonList(lockKey), lockValue);
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
        String lockKey = "myxhs:payment:lock:reconcile";
        String lockValue = java.util.UUID.randomUUID().toString();
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, Duration.ofMinutes(5));
        if (Boolean.FALSE.equals(locked)) {
            return;
        }

        try {
            log.info("[对账] 开始执行支付对账...");
            // 游标分页查询（避免全量查询 OOM）
            long lastId = 0;
            int batchSize = 200;
            int inconsistent = 0;
            int totalRecords = 0;

            while (true) {
                List<ReconcileRecord> records = paymentJdbcTemplate.query(
                        "SELECT order_id, payment_no, id FROM t_payment " +
                                "WHERE status = 1 AND deleted = 0 AND id > ? ORDER BY id ASC LIMIT ?",
                        (rs, rowNum) -> new ReconcileRecord(
                                rs.getLong("id"),
                                rs.getLong("order_id"),
                                rs.getString("payment_no")
                        ),
                        lastId, batchSize
                );
                if (records.isEmpty()) break;
                totalRecords += records.size();

                for (ReconcileRecord record : records) {
                    try {
                        R<BigDecimal> payAmountResult = orderFeignClient.getOrderPayAmount(record.orderId());
                        if (payAmountResult != null && payAmountResult.isSuccess()) {
                            BigDecimal payAmount = payAmountResult.getData();
                            if (payAmount != null && payAmount.compareTo(BigDecimal.ZERO) > 0) {
                                log.error("[对账] 不一致: 支付成功但订单仍待支付, orderId={}", record.orderId());
                                inconsistent++;
                                R<Void> notifyResult = orderFeignClient.notifyPaySuccess(record.orderId(), record.paymentNo());
                                if (notifyResult == null || !notifyResult.isSuccess()) {
                                    log.error("[对账] 支付成功补偿通知失败: orderId={}, resp={}", record.orderId(), notifyResult);
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.error("[对账] 处理异常: orderId={}", record.orderId(), e);
                    }
                }
                long finalLastId = lastId;
                lastId = records.stream().mapToLong(ReconcileRecord::id).max().orElse(finalLastId);
                if (records.size() < batchSize) break;
            }

            if (inconsistent > 0) {
                log.warn("[对账] 发现 {} 条不一致记录，已触发补偿通知", inconsistent);
            }
            log.info("[对账] 支付对账完成: totalRecords={}, inconsistent={}", totalRecords, inconsistent);

            // ============ ITER-002 阶段④（GAP-4）：未核实失败单核查 ============
            // 场景："误标失败 + 回调永久丢失" → 永久错位（用户付了钱、本地失败）。
            // 修复：扫 status=2 且 verify_method=2（未核实，含存量数据）→ 重查渠道：
            //   渠道已付 → 本地收敛成功（补通知订单）；渠道确认未付 → 补记"已核实(1)"；不可达 → 留下轮。
            int rechecked = 0;
            int converged = 0;
            long lastFailId = 0;
            while (true) {
                List<java.util.Map<String, Object>> failRows = paymentJdbcTemplate.queryForList(
                        "SELECT id, order_id, user_id, payment_no, pay_type FROM t_payment " +
                                "WHERE status = ? AND verify_method = ? AND deleted = 0 AND id > ? ORDER BY id ASC LIMIT ?",
                        STATUS_FAIL, VERIFY_METHOD_UNVERIFIED, lastFailId, batchSize);
                if (failRows.isEmpty()) break;
                for (java.util.Map<String, Object> row : failRows) {
                    Long pid = ((Number) row.get("id")).longValue();
                    Long orderId = ((Number) row.get("order_id")).longValue();
                    Long uid = ((Number) row.get("user_id")).longValue();
                    String paymentNo = (String) row.get("payment_no");
                    Integer payType = row.get("pay_type") != null ? ((Number) row.get("pay_type")).intValue() : null;
                    try {
                        PayChannelStrategy strategy = payType != null ? payChannelStrategyMap.get(payType) : null;
                        Integer channelStatus = strategy != null ? strategy.queryPayStatus(paymentNo) : null;
                        if (channelStatus == null) {
                            continue; // 不可达 → 留下轮
                        }
                        rechecked++;
                        if (channelStatus == 1) {
                            log.warn("[对账] 未核实失败单渠道查得已支付, 本地收敛: paymentId={}, orderId={}", pid, orderId);
                            handlePaySuccessInternal(orderId, uid, paymentNo, paymentNo);
                            recordPaymentEvent(paymentNo, orderId, uid, "RECONCILE_CHANNEL_PAID", null, "对账核查：渠道已支付，本地收敛");
                            converged++;
                        } else {
                            paymentJdbcTemplate.update(
                                    "UPDATE t_payment SET verify_method = ?, updated_at = ? WHERE id = ? AND status = ? AND deleted = 0",
                                    VERIFY_METHOD_CHANNEL_UNPAID, LocalDateTime.now(), pid, STATUS_FAIL);
                        }
                    } catch (Exception e) {
                        log.error("[对账] 核查异常: paymentId={}", pid, e);
                    }
                }
                lastFailId = failRows.stream()
                        .mapToLong(r -> ((Number) r.get("id")).longValue()).max().orElse(lastFailId);
                if (failRows.size() < batchSize) break;
            }
            if (rechecked > 0 || converged > 0) {
                log.info("[对账] 失败单核查完成: rechecked={}, converged={}", rechecked, converged);
            }
        } finally {
            safeUnlock(lockKey, lockValue);
        }
    }

    /**
     * 对账记录 DTO
     */
    private record ReconcileRecord(Long id, Long orderId, String paymentNo) {
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

    /**
     * 查询订单"活着的"（待支付）支付单（ITER-002 A1：一订单最多一条，uk_order_pending 保障）
     */
    private Payment findPendingByOrderId(Long orderId) {
        return paymentJdbcTemplate.query(
                "SELECT id, order_id, user_id, payment_no, amount, pay_type, status, paid_at, deleted, created_at, updated_at " +
                        "FROM t_payment WHERE order_id = ? AND status = 0 AND deleted = 0 ORDER BY created_at DESC LIMIT 1",
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

    /**
     * 按业务幂等键查退款单（ITER-002 阶段③：出金幂等）
     */
    private Refund findRefundByOutRefundNo(String outRefundNo) {
        return paymentJdbcTemplate.query(
                "SELECT id, payment_id, order_id, user_id, refund_no, out_refund_no, refund_amount, reason, status, refund_type, refund_channel, success_at, deleted, created_at, updated_at " +
                        "FROM t_refund WHERE out_refund_no = ? AND deleted = 0 LIMIT 1",
                rs -> {
                    if (rs.next()) {
                        Refund r = new Refund();
                        r.setId(rs.getLong("id"));
                        r.setPaymentId(rs.getLong("payment_id"));
                        r.setOrderId(rs.getLong("order_id"));
                        r.setUserId(rs.getLong("user_id"));
                        r.setRefundNo(rs.getString("refund_no"));
                        r.setOutRefundNo(rs.getString("out_refund_no"));
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
                outRefundNo
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
            case 4: statusDesc = "已关闭"; break;
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
            Message<String> message = MqTraceHelper.wrapWithTraceId(MessageBuilder.withPayload(body)
                    .setHeader("KEYS", key)
                    .build());
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
            Message<String> message = MqTraceHelper.wrapWithTraceId(MessageBuilder.withPayload(body)
                    .setHeader("KEYS", key)
                    .build());
            rocketMQTemplate.syncSend(destination, message);
            log.info("[退款结果MQ] 发送成功: topic={}, tag={}, orderId={}", topic, tag, orderId);
        } catch (Exception e) {
            log.error("[退款结果MQ] 发送失败: orderId={}", orderId, e);
        }
    }

    /**
     * 记录支付事件流水（append-only 可观测性，异步落库）
     * <p>
     * 仅记录已落定的状态变化；独立线程池 + DiscardPolicy + try-catch——
     * 事件失败绝不影响资金主流程（不阻塞、不回滚、不重试）。
     * </p>
     *
     * @param paymentNo 支付流水号
     * @param orderId   订单ID
     * @param userId    用户ID
     * @param eventType CREATE / PAY_SUCCESS / PAY_FAIL / REFUND
     * @param errorCode 失败原因码（成功事件为 null）
     * @param errorMsg  失败原因/备注（成功事件为 null）
     */
    private void recordPaymentEvent(String paymentNo, Long orderId, Long userId,
                                    String eventType, String errorCode, String errorMsg) {
        try {
            PAYMENT_EVENT_EXECUTOR.submit(() -> {
                try {
                    com.myxhs.payment.entity.PaymentEvent ev = new com.myxhs.payment.entity.PaymentEvent();
                    ev.setId(idGeneratorUtil.nextId());
                    ev.setPaymentNo(paymentNo);
                    ev.setOrderId(orderId);
                    ev.setUserId(userId);
                    ev.setEventType(eventType);
                    ev.setErrorCode(errorCode);
                    ev.setErrorMsg(errorMsg != null && errorMsg.length() > 200 ? errorMsg.substring(0, 200) : errorMsg);
                    ev.setEventTime(LocalDateTime.now());
                    paymentEventMapper.insert(ev);
                    log.info("[支付事件] 落库: paymentNo={}, eventType={}", paymentNo, eventType);
                } catch (Exception e) {
                    log.warn("[支付事件] 事件落库失败(可容忍): paymentNo={}, eventType={}", paymentNo, eventType, e);
                }
            });
        } catch (Exception e) {
            log.warn("[支付事件] 事件提交失败(可容忍): paymentNo={}, eventType={}", paymentNo, eventType);
        }
    }
}
