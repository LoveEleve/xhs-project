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
        assertThat(vo.getStatus()).isEqualTo(0); // 返回的是刚创建时的状态

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
    @DisplayName("支付防重 - 同一订单重复支付应被幂等机制拦截")
    void paymentDuplicatePrevention() {
        when(paymentJdbcTemplate.update(
                startsWith("INSERT INTO t_payment"),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        )).thenReturn(1);

        when(paymentJdbcTemplate.update(
                startsWith("UPDATE t_payment SET status"),
                any(), any(), any(), any(), any()
        )).thenReturn(1);

        // 第一次 get 返回 null（未支付），第二次返回 "1"（已支付）
        when(valueOperations.get(eq("myxhs:payment:status:" + ORDER_ID)))
                .thenReturn(null, "1");

        // 第一次支付：应该成功
        PayCreateRequest request = buildPayRequest();
        R<PaymentVO> firstResult = paymentService.pay(request);
        assertThat(firstResult.isSuccess()).isTrue();

        // 第二次支付：应该被幂等拦截
        assertThatThrownBy(() -> paymentService.pay(buildPayRequest()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("请勿重复支付");
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

    // ==================== 辅助方法 ====================

    private PayCreateRequest buildPayRequest() {
        PayCreateRequest request = new PayCreateRequest();
        request.setOrderId(ORDER_ID);
        request.setUserId(USER_ID);
        request.setAmount(AMOUNT);
        request.setPayType(99); // Mock 支付
        return request;
    }
}
