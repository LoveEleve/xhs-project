package com.myxhs.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.id.IdGeneratorUtil;
import com.myxhs.common.metrics.BusinessMetrics;
import com.myxhs.common.response.R;
import com.myxhs.payment.dto.request.PayCreateRequest;
import com.myxhs.payment.dto.response.PaymentVO;
import com.myxhs.payment.feign.OrderFeignClient;
import com.myxhs.payment.strategy.PayChannelStrategy;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.messaging.Message;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PaymentService 单元测试
 * <p>
 * 测试策略：纯 Mockito Mock，不连接任何外部服务。
 * Mock 对象：JdbcTemplate, RedissonClient, StringRedisTemplate, RocketMQTemplate,
 * OrderFeignClient, IdGeneratorUtil, BusinessMetrics, ObjectMapper
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceTest {

    @Mock
    private JdbcTemplate paymentJdbcTemplate;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private RocketMQTemplate rocketMQTemplate;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private Map<Integer, PayChannelStrategy> payChannelStrategyMap;
    @Mock
    private OrderFeignClient orderFeignClient;
    @Mock
    private IdGeneratorUtil idGeneratorUtil;
    @Mock
    private BusinessMetrics businessMetrics;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private RLock payLock;
    @Mock
    private PayChannelStrategy mockStrategy;
    @Mock
    private com.myxhs.payment.simulator.PayCallbackSimulator callbackSimulator;
    @Mock
    private com.myxhs.payment.mapper.PaymentEventMapper paymentEventMapper;

    private ObjectMapper objectMapper;
    private PaymentService paymentService;

    private static final Long ORDER_ID = 1001L;
    private static final Long USER_ID = 2001L;
    private static final BigDecimal AMOUNT = new BigDecimal("99.00");
    private static final String PAYMENT_NO = "PAY20260712000001";
    private static final Long PAYMENT_ID = 100001L;
    private static final String TRADE_NO = "MOCK_TRADE_001";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        // @RequiredArgsConstructor 构造函数注入
        paymentService = new PaymentService(
                paymentJdbcTemplate,
                stringRedisTemplate,
                rocketMQTemplate,
                redissonClient,
                payChannelStrategyMap,
                callbackSimulator,
                orderFeignClient,
                idGeneratorUtil,
                businessMetrics,
                objectMapper,
                paymentEventMapper
        );

        // 设置 stringRedisTemplate 的 ValueOperations
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        // 设置 Redisson 分布式锁
        when(redissonClient.getLock(anyString())).thenReturn(payLock);
        try {
            when(payLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        } catch (InterruptedException e) {
            // won't happen
        }
        when(payLock.isHeldByCurrentThread()).thenReturn(true);
        doNothing().when(payLock).unlock();

        // 设置 ID 生成
        when(idGeneratorUtil.nextSerialNo("PAY")).thenReturn(PAYMENT_NO);
        when(idGeneratorUtil.nextId()).thenReturn(PAYMENT_ID);

        // 设置支付策略
        when(payChannelStrategyMap.get(99)).thenReturn(mockStrategy);
        when(mockStrategy.pay(eq(ORDER_ID), any(BigDecimal.class), eq(PAYMENT_NO)))
                .thenReturn(TRADE_NO);

        // 设置 Redis delete
        when(stringRedisTemplate.delete(anyString())).thenReturn(true);

        // 设置 MQ 发送
        SendResult sendResult = new SendResult();
        sendResult.setSendStatus(SendStatus.SEND_OK);
        when(rocketMQTemplate.syncSend(anyString(), any(Message.class)))
                .thenReturn(sendResult);

        // 设置 Feign 调用
        when(orderFeignClient.notifyPaySuccess(anyLong(), anyString()))
                .thenReturn(R.ok());
        // P1-1：支付前回查订单状态，默认订单待付款(0)允许支付
        // 2026-09-23：pay() 新增金额校验（/pay-amount 比对）→ 需 stub 订单实付金额与请求一致
        when(orderFeignClient.getOrderPayAmount(anyLong())).thenReturn(R.ok(AMOUNT));
        when(orderFeignClient.getOrderStatus(anyLong()))
                .thenReturn(R.ok(0));
    }

    // ==================== 支付相关测试 ====================

    @Test
    @DisplayName("Mock支付成功 - payType=99创建支付单并标记为SUCCESS")
    void mockPaySuccess() {
        // Mock: INSERT 返回 1（成功插入 1 行），SQL + 10 个占位符参数
        when(paymentJdbcTemplate.update(
                startsWith("INSERT INTO t_payment"),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(1);

        // Mock: 乐观锁更新返回 1（更新成功），SQL + 5 个占位符参数
        when(paymentJdbcTemplate.update(
                startsWith("UPDATE t_payment SET status"),
                any(), any(), any(), any(), any()
        )).thenReturn(1);

        // Mock: get(statusKey) 返回 null（尚未支付）
        when(valueOperations.get(eq("myxhs:payment:status:" + ORDER_ID))).thenReturn(null);

        PayCreateRequest request = buildPayRequest();
        R<PaymentVO> result = paymentService.pay(request);

        assertThat(result.isSuccess()).isTrue();
        PaymentVO vo = result.getData();
        assertThat(vo).isNotNull();
        assertThat(vo.getOrderId()).isEqualTo(ORDER_ID);
        assertThat(vo.getPaymentNo()).isEqualTo(PAYMENT_NO);
        assertThat(vo.getStatus()).isEqualTo(1); // mock 同步成功后返回成功状态（原实现返回创建时状态=0，与本轮修复同步）

        // 验证支付记录已插入
        verify(paymentJdbcTemplate).update(
                startsWith("INSERT INTO t_payment"),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        );

        // 验证 Mock 模式触发了支付成功处理
        verify(paymentJdbcTemplate).update(
                startsWith("UPDATE t_payment SET status"),
                any(), any(), any(), any(), any()
        );

        // 验证策略被调用
        verify(mockStrategy).pay(eq(ORDER_ID), any(BigDecimal.class), eq(PAYMENT_NO));
    }

    @Test
    @DisplayName("Mock支付返回正确金额 - 验证支付金额与请求一致")
    void mockPayReturnCorrectAmount() {
        when(paymentJdbcTemplate.update(
                startsWith("INSERT INTO t_payment"),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(1);

        when(paymentJdbcTemplate.update(
                startsWith("UPDATE t_payment SET status"),
                any(), any(), any(), any(), any()
        )).thenReturn(1);

        when(valueOperations.get(eq("myxhs:payment:status:" + ORDER_ID))).thenReturn(null);

        PayCreateRequest request = buildPayRequest();
        R<PaymentVO> result = paymentService.pay(request);

        PaymentVO vo = result.getData();
        assertThat(vo).isNotNull();
        // 验证返回的支付金额与请求金额一致
        assertThat(vo.getAmount()).isEqualByComparingTo(AMOUNT);
    }

    @Test
    @DisplayName("支付复用 - 同一订单同渠道重复发起复用同一支付单（ITER-002 A1）")
    void paymentReuseSameChannel() {
        // 已存在 pending 支付单（同渠道）
        com.myxhs.payment.entity.Payment existing = new com.myxhs.payment.entity.Payment();
        existing.setId(PAYMENT_ID);
        existing.setOrderId(ORDER_ID);
        existing.setUserId(USER_ID);
        existing.setPaymentNo(PAYMENT_NO);
        existing.setAmount(AMOUNT);
        existing.setPayType(99);
        existing.setStatus(0);
        existing.setCreatedAt(java.time.LocalDateTime.now());
        existing.setUpdatedAt(java.time.LocalDateTime.now());

        when(paymentJdbcTemplate.query(
                org.mockito.ArgumentMatchers.contains("AND status = 0"),
                any(org.springframework.jdbc.core.ResultSetExtractor.class),
                any()
        )).thenReturn(existing);

        R<PaymentVO> result = paymentService.pay(buildPayRequest());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().getPaymentNo()).isEqualTo(PAYMENT_NO);
        // 复用不新建支付单
        verify(paymentJdbcTemplate, never()).update(
                startsWith("INSERT INTO t_payment"),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("换渠道 - 关旧渠道交易 + 旧单置已关闭(4) + 新建新单（ITER-002 A1 阶段2）")
    void paymentSwitchChannel() {
        // 旧 pending：支付宝渠道(1)
        com.myxhs.payment.entity.Payment pending = new com.myxhs.payment.entity.Payment();
        pending.setId(PAYMENT_ID);
        pending.setOrderId(ORDER_ID);
        pending.setUserId(USER_ID);
        pending.setPaymentNo("PAY-ALIPAY-OLD");
        pending.setAmount(AMOUNT);
        pending.setPayType(1);
        pending.setStatus(0);
        pending.setCreatedAt(java.time.LocalDateTime.now());
        pending.setUpdatedAt(java.time.LocalDateTime.now());
        when(paymentJdbcTemplate.query(
                org.mockito.ArgumentMatchers.contains("AND status = 0"),
                any(org.springframework.jdbc.core.ResultSetExtractor.class),
                any()
        )).thenReturn(pending);

        // 旧渠道策略：close 应被调用
        PayChannelStrategy alipayStrategy = mock(PayChannelStrategy.class);
        when(payChannelStrategyMap.get(1)).thenReturn(alipayStrategy);

        // 旧单关闭（乐观锁 UPDATE，4 参）
        when(paymentJdbcTemplate.update(
                startsWith("UPDATE t_payment SET status = ?, updated_at = ?"),
                any(), any(), any(), any())).thenReturn(1);

        // 新单插入 + 新渠道（微信 mock：异步 → 注册回调模拟器）
        when(paymentJdbcTemplate.update(
                startsWith("INSERT INTO t_payment"),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        PayChannelStrategy wechatStrategy = mock(PayChannelStrategy.class);
        when(payChannelStrategyMap.get(2)).thenReturn(wechatStrategy);
        when(wechatStrategy.pay(eq(ORDER_ID), any(java.math.BigDecimal.class), anyString())).thenReturn("WX-TRADE");

        PayCreateRequest request = buildPayRequest();
        request.setPayType(2); // 换到微信

        R<PaymentVO> result = paymentService.pay(request);

        assertThat(result.isSuccess()).isTrue();
        // 旧渠道交易被关闭
        verify(alipayStrategy).close("PAY-ALIPAY-OLD");
        // 旧单置已关闭
        verify(paymentJdbcTemplate).update(
                startsWith("UPDATE t_payment SET status = ?, updated_at = ?"),
                any(), any(), any(), any());
        // 新单已创建
        verify(paymentJdbcTemplate).update(
                startsWith("INSERT INTO t_payment"),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("查询支付状态 - 支付后查询应返回正确的支付状态")
    void queryPaymentStatus() {
        when(paymentJdbcTemplate.update(
                startsWith("INSERT INTO t_payment"),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(1);

        when(paymentJdbcTemplate.update(
                startsWith("UPDATE t_payment SET status"),
                any(), any(), any(), any(), any()
        )).thenReturn(1);

        when(valueOperations.get(eq("myxhs:payment:status:" + ORDER_ID))).thenReturn(null);

        // 第一次调用 pay（会 mock handlePaySuccessInternal 将状态设为 SUCCESS）
        paymentService.pay(buildPayRequest());

        // 然后查询支付状态 — 需要 mock DB 查询返回已支付的 Payment
        // 注意：通过 JdbcTemplate.query() 模拟返回已支付记录
        com.myxhs.payment.entity.Payment payment = new com.myxhs.payment.entity.Payment();
        payment.setId(PAYMENT_ID);
        payment.setOrderId(ORDER_ID);
        payment.setUserId(USER_ID);
        payment.setPaymentNo(PAYMENT_NO);
        payment.setAmount(AMOUNT);
        payment.setPayType(99);
        payment.setStatus(1); // SUCCESS
        payment.setPaidAt(java.time.LocalDateTime.now());
        payment.setCreatedAt(java.time.LocalDateTime.now());
        payment.setUpdatedAt(java.time.LocalDateTime.now());

        when(paymentJdbcTemplate.query(
                startsWith("SELECT id, order_id, user_id, payment_no"),
                any(ResultSetExtractor.class),
                eq(ORDER_ID)
        )).thenReturn(payment);

        R<PaymentVO> result = paymentService.getPaymentStatus(ORDER_ID);
        assertThat(result.isSuccess()).isTrue();
        PaymentVO vo = result.getData();
        assertThat(vo).isNotNull();
        assertThat(vo.getStatus()).isEqualTo(1);
        assertThat(vo.getStatusDesc()).isEqualTo("支付成功");
        assertThat(vo.getAmount()).isEqualByComparingTo(AMOUNT);
    }

    @Test
    @DisplayName("Feign调用通知 - Mock支付成功后应通知订单服务")
    void hasOrderFeignClientCall() {
        when(paymentJdbcTemplate.update(
                startsWith("INSERT INTO t_payment"),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(1);

        when(paymentJdbcTemplate.update(
                startsWith("UPDATE t_payment SET status"),
                any(), any(), any(), any(), any()
        )).thenReturn(1);

        when(valueOperations.get(eq("myxhs:payment:status:" + ORDER_ID))).thenReturn(null);

        PayCreateRequest request = buildPayRequest();
        paymentService.pay(request);

        // 验证 orderFeignClient.notifyPaySuccess 被调用了一次
        verify(orderFeignClient, times(1)).notifyPaySuccess(eq(ORDER_ID), eq(TRADE_NO));
    }

    // ==================== 超时处置矩阵（ITER-002 阶段④） ====================

    @Test
    @DisplayName("超时处置矩阵 - 渠道已付→收敛 / 未付→置失败+关渠道+证据 / 不可达→不处置")
    void checkPaymentTimeout_channelTruthMatrix() {
        // payType=1（支付宝 mock）策略
        when(payChannelStrategyMap.get(1)).thenReturn(mockStrategy);
        // 分布式锁：拿到（未打桩时 Boolean 默认 false → 会走"未获取到锁"提前返回）
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(true);
        when(paymentJdbcTemplate.queryForList(
                startsWith("SELECT id, order_id, user_id, payment_no, pay_type FROM t_payment WHERE status = ?"),
                (Object) any(), (Object) any())).thenReturn(java.util.List.of(
                timeoutRow(1L, 101L, 1001L, "PAY-PAID", 1),
                timeoutRow(2L, 102L, 1001L, "PAY-UNPAID", 1),
                timeoutRow(3L, 103L, 1001L, "PAY-UNKNOWN", 1)
        ));
        when(mockStrategy.queryPayStatus("PAY-PAID")).thenReturn(1);
        when(mockStrategy.queryPayStatus("PAY-UNPAID")).thenReturn(0);
        when(mockStrategy.queryPayStatus("PAY-UNKNOWN")).thenReturn(null);
        // 收敛成功（乐观锁 0→1）
        when(paymentJdbcTemplate.update(
                startsWith("UPDATE t_payment SET status = ?, paid_at = ?"),
                any(), any(), any(), any(), any())).thenReturn(1);
        // 置失败（verify_method）
        when(paymentJdbcTemplate.update(
                startsWith("UPDATE t_payment SET status = ?, verify_method = ?"),
                any(), any(), any(), any(), any())).thenReturn(1);

        paymentService.checkPaymentTimeout();

        // 渠道已付 → 本地收敛 + 通知订单；不关渠道、不置失败
        verify(orderFeignClient).notifyPaySuccess(eq(101L), eq("PAY-PAID"));
        verify(mockStrategy, never()).close("PAY-PAID");
        // 渠道未付 → 关渠道 + 置失败 + 证据
        verify(mockStrategy).close("PAY-UNPAID");
        verify(paymentJdbcTemplate).update(
                startsWith("UPDATE t_payment SET status = ?, verify_method = ?"),
                any(), any(), any(), any(), any());
        // 不可达 → 不处置（不关渠道、不置失败）
        verify(mockStrategy, never()).close("PAY-UNKNOWN");
        verify(orderFeignClient, never()).notifyPaySuccess(eq(103L), anyString());
    }

    @Test
    @DisplayName("对账核查分支 - 未核实失败单渠道已付→收敛（GAP-4）")
    void reconcile_unverifiedFailedRecheck() {
        when(payChannelStrategyMap.get(1)).thenReturn(mockStrategy);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(true);
        when(paymentJdbcTemplate.queryForList(
                startsWith("SELECT id, order_id, user_id, payment_no, pay_type FROM t_payment WHERE status = ? AND verify_method = ?"),
                (Object) any(), (Object) any(), (Object) any(), (Object) any())).thenReturn(java.util.List.of(
                timeoutRow(9L, 109L, 1001L, "PAY-FAILED", 1)
        ));
        when(mockStrategy.queryPayStatus("PAY-FAILED")).thenReturn(1);
        when(paymentJdbcTemplate.update(
                startsWith("UPDATE t_payment SET status = ?, paid_at = ?"),
                any(), any(), any(), any(), any())).thenReturn(1);

        paymentService.reconcile();

        verify(orderFeignClient).notifyPaySuccess(eq(109L), eq("PAY-FAILED"));
    }

    @Test
    @DisplayName("订单终结 - 关闭 pending 支付单（status=4 + 渠道 close；ITER-002 阶段⑤）")
    void closePendingOnTermination_closesPending() {
        com.myxhs.payment.entity.Payment pending = new com.myxhs.payment.entity.Payment();
        pending.setId(PAYMENT_ID);
        pending.setOrderId(ORDER_ID);
        pending.setUserId(USER_ID);
        pending.setPaymentNo(PAYMENT_NO);
        pending.setPayType(99);
        pending.setStatus(0);
        when(paymentJdbcTemplate.query(
                org.mockito.ArgumentMatchers.contains("AND status = 0"),
                any(org.springframework.jdbc.core.ResultSetExtractor.class),
                any()
        )).thenReturn(pending);
        when(paymentJdbcTemplate.update(
                startsWith("UPDATE t_payment SET status = ?, updated_at = ?"),
                any(), any(), any(), any())).thenReturn(1);

        paymentService.closePendingOnTermination(ORDER_ID);

        verify(mockStrategy).close(PAYMENT_NO);
        verify(paymentJdbcTemplate).update(
                startsWith("UPDATE t_payment SET status = ?, updated_at = ?"),
                any(), any(), any(), any());
    }

    // ==================== 辅助方法 ====================

    private java.util.Map<String, Object> timeoutRow(long id, long orderId, long uid, String paymentNo, int payType) {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("id", id);
        m.put("order_id", orderId);
        m.put("user_id", uid);
        m.put("payment_no", paymentNo);
        m.put("pay_type", payType);
        return m;
    }

    private PayCreateRequest buildPayRequest() {
        PayCreateRequest request = new PayCreateRequest();
        request.setOrderId(ORDER_ID);
        request.setUserId(USER_ID);
        request.setAmount(AMOUNT);
        request.setPayType(99); // Mock 支付
        return request;
    }
}
