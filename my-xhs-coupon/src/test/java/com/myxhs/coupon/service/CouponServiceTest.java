package com.myxhs.coupon.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.myxhs.common.exception.BizException;
import com.myxhs.coupon.dto.request.ClaimCouponRequest;
import com.myxhs.coupon.dto.request.CreateTemplateRequest;
import com.myxhs.coupon.dto.request.ReturnCouponRequest;
import com.myxhs.coupon.dto.response.UserCouponVO;
import com.myxhs.coupon.entity.CouponTemplate;
import com.myxhs.coupon.entity.UserCoupon;
import com.myxhs.coupon.mapper.CouponOutboxMapper;
import com.myxhs.coupon.mapper.CouponTemplateMapper;
import com.myxhs.coupon.mapper.UserCouponMapper;
import com.myxhs.coupon.validator.CouponValidator;
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
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.Message;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CouponService 单元测试
 * <p>
 * 测试策略：纯 Mockito Mock，不连接任何外部服务。
 * Mock 对象：CouponTemplateMapper, UserCouponMapper, RocketMQTemplate,
 * StringRedisTemplate, DefaultRedisScript, CouponValidator
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CouponServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private RocketMQTemplate rocketMQTemplate;
    @Mock
    private CouponTemplateMapper templateMapper;
    @Mock
    private UserCouponMapper userCouponMapper;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private SetOperations<String, String> setOperations;
    @Mock
    private DefaultRedisScript<Long> claimCouponScript;
    @Mock
    private DefaultRedisScript<Long> returnCouponScript;
    @Mock
    private CouponValidator couponValidator;

    private ObjectMapper objectMapper;
    private CouponOutboxMapper couponOutboxMapper;
    private CouponService couponService;

    private static final Long USER_ID = 1001L;
    private static final Long TEMPLATE_ID = 1L;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        List<CouponValidator> validators = Collections.singletonList(couponValidator);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        couponOutboxMapper = mock(CouponOutboxMapper.class);
        couponService = new CouponService(
                stringRedisTemplate, rocketMQTemplate,
                templateMapper, userCouponMapper,
                couponOutboxMapper,
                mock(com.myxhs.common.id.IdGeneratorUtil.class),
                objectMapper,
                claimCouponScript, returnCouponScript,
                validators,
                mock(com.myxhs.common.metrics.BusinessMetrics.class)
        );
    }

    // ==================== 创建模板 ====================

    @Test
    @DisplayName("创建优惠券模板 - 成功创建并验证 Mapper.insert 被调用")
    void createTemplateSuccess() {
        // 模拟 MyBatis Plus 插入后设置 ID
        doAnswer(invocation -> {
            CouponTemplate t = invocation.getArgument(0);
            t.setId(TEMPLATE_ID);
            return 1;
        }).when(templateMapper).insert(any(CouponTemplate.class));

        CreateTemplateRequest request = buildCreateTemplateRequest();
        CouponTemplate result = couponService.createTemplate(request);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(TEMPLATE_ID);
        assertThat(result.getName()).isEqualTo("满100减10");
        assertThat(result.getType()).isEqualTo(1);
        assertThat(result.getDiscountValue()).isEqualByComparingTo("10");
        verify(templateMapper).insert(any(CouponTemplate.class));
    }

    @Test
    @DisplayName("创建模板 - 发送过期延时消息（deliverTime=validEnd，C-017）")
    void createTemplateSendsExpireDelay() {
        doAnswer(invocation -> {
            CouponTemplate t = invocation.getArgument(0);
            t.setId(TEMPLATE_ID);
            return 1;
        }).when(templateMapper).insert(any(CouponTemplate.class));

        CreateTemplateRequest request = buildCreateTemplateRequest();
        couponService.createTemplate(request);

        long deliverAt = request.getValidEnd()
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        verify(rocketMQTemplate).syncSendDeliverTimeMills(
                eq("COUPON_EXPIRE_TOPIC"), any(), eq(deliverAt));
    }

    // ==================== 领券 ====================

    @Test
    @DisplayName("领券 - 成功领取优惠券")
    void claimCouponSuccess() {
        // 缓存未命中，走 MySQL
        when(valueOperations.get(startsWith("myxhs:coupon:template:"))).thenReturn(null);
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(buildTemplate());

        // Lua 脚本返回 1（成功）
        when(stringRedisTemplate.execute(
                eq(claimCouponScript), anyList(), anyString()))
                .thenReturn(1L);

        // MQ 同步发送成功
        SendResult sendResult = new SendResult();
        sendResult.setSendStatus(SendStatus.SEND_OK);
        when(rocketMQTemplate.syncSend(
                eq("COUPON_CLAIM_TOPIC"), any(Message.class), eq(3000L)))
                .thenReturn(sendResult);

        ClaimCouponRequest request = new ClaimCouponRequest();
        request.setTemplateId(TEMPLATE_ID);

        assertThatCode(() -> couponService.claimCoupon(USER_ID, request))
                .doesNotThrowAnyException();

        verify(stringRedisTemplate).execute(eq(claimCouponScript), anyList(), anyString());
        verify(rocketMQTemplate).syncSend(eq("COUPON_CLAIM_TOPIC"), any(Message.class), eq(3000L));
    }

    @Test
    @DisplayName("C-007：MQ发送异常（超时≠失败）→ 保留 Outbox + 领券成功（Design B 至少一次）")
    void claimCouponKeepsOutboxWhenMqSendFails() {
        when(valueOperations.get(startsWith("myxhs:coupon:template:"))).thenReturn(null);
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(buildTemplate());
        when(stringRedisTemplate.execute(eq(claimCouponScript), anyList(), anyString())).thenReturn(1L);
        when(rocketMQTemplate.syncSend(eq("COUPON_CLAIM_TOPIC"), any(Message.class), eq(3000L)))
                .thenThrow(new RuntimeException("mq timeout"));

        ClaimCouponRequest request = new ClaimCouponRequest();
        request.setTemplateId(TEMPLATE_ID);

        // Design B：内部故障不进用户视野——不抛错、不回滚、恒成功
        assertThatCode(() -> couponService.claimCoupon(USER_ID, request))
                .doesNotThrowAnyException();

        // Outbox 保留（补发必达）；绝不回滚 Redis
        verify(couponOutboxMapper).insertOutboxEvent(anyLong(), eq(USER_ID), eq(TEMPLATE_ID), anyString());
        verify(stringRedisTemplate, never()).execute(eq(returnCouponScript), anyList(), any());
    }

    @Test
    @DisplayName("C-007：MQ返回非 SEND_OK（消息可能已写入 broker）→ 保留 Outbox + 成功")
    void claimCouponKeepsOutboxOnNonOkStatus() {
        when(valueOperations.get(startsWith("myxhs:coupon:template:"))).thenReturn(null);
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(buildTemplate());
        when(stringRedisTemplate.execute(eq(claimCouponScript), anyList(), anyString())).thenReturn(1L);
        SendResult sendResult = new SendResult();
        sendResult.setSendStatus(SendStatus.FLUSH_DISK_TIMEOUT);
        when(rocketMQTemplate.syncSend(eq("COUPON_CLAIM_TOPIC"), any(Message.class), eq(3000L)))
                .thenReturn(sendResult);

        ClaimCouponRequest request = new ClaimCouponRequest();
        request.setTemplateId(TEMPLATE_ID);

        assertThatCode(() -> couponService.claimCoupon(USER_ID, request))
                .doesNotThrowAnyException();

        verify(couponOutboxMapper).insertOutboxEvent(anyLong(), eq(USER_ID), eq(TEMPLATE_ID), anyString());
        verify(couponOutboxMapper, never()).markOutboxSent(anyString());
        verify(stringRedisTemplate, never()).execute(eq(returnCouponScript), anyList(), any());
    }

    @Test
    @DisplayName("C-007：SEND_OK 但 markSent 失败 → 仅告警 + 成功（行保留补发，幂等兜底）")
    void claimCouponMarkSentFailureStillSuccess() {
        when(valueOperations.get(startsWith("myxhs:coupon:template:"))).thenReturn(null);
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(buildTemplate());
        when(stringRedisTemplate.execute(eq(claimCouponScript), anyList(), anyString())).thenReturn(1L);
        SendResult sendResult = new SendResult();
        sendResult.setSendStatus(SendStatus.SEND_OK);
        when(rocketMQTemplate.syncSend(eq("COUPON_CLAIM_TOPIC"), any(Message.class), eq(3000L)))
                .thenReturn(sendResult);
        when(couponOutboxMapper.markOutboxSent(anyString()))
                .thenThrow(new RuntimeException("db blip"));

        ClaimCouponRequest request = new ClaimCouponRequest();
        request.setTemplateId(TEMPLATE_ID);

        assertThatCode(() -> couponService.claimCoupon(USER_ID, request))
                .doesNotThrowAnyException();
        verify(stringRedisTemplate, never()).execute(eq(returnCouponScript), anyList(), any());
    }

    @Test
    @DisplayName("C-007：Outbox 写入首次失败、重试命中已有记录（写入超时≠失败）→ 成功")
    void claimCouponOutboxInsertRetryHitsDuplicate() {
        when(valueOperations.get(startsWith("myxhs:coupon:template:"))).thenReturn(null);
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(buildTemplate());
        when(stringRedisTemplate.execute(eq(claimCouponScript), anyList(), anyString())).thenReturn(1L);
        when(couponOutboxMapper.insertOutboxEvent(anyLong(), eq(USER_ID), eq(TEMPLATE_ID), anyString()))
                .thenThrow(new RuntimeException("db timeout"))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("dup"));
        SendResult sendResult = new SendResult();
        sendResult.setSendStatus(SendStatus.SEND_OK);
        when(rocketMQTemplate.syncSend(eq("COUPON_CLAIM_TOPIC"), any(Message.class), eq(3000L)))
                .thenReturn(sendResult);

        ClaimCouponRequest request = new ClaimCouponRequest();
        request.setTemplateId(TEMPLATE_ID);

        assertThatCode(() -> couponService.claimCoupon(USER_ID, request))
                .doesNotThrowAnyException();
        verify(stringRedisTemplate, never()).execute(eq(returnCouponScript), anyList(), any());
    }

    @Test
    @DisplayName("C-007：Outbox 首次写入失败、重试直接成功 → 正常发送（超时≠失败的正向路径）")
    void claimCouponOutboxInsertRetrySucceeds() {
        when(valueOperations.get(startsWith("myxhs:coupon:template:"))).thenReturn(null);
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(buildTemplate());
        when(stringRedisTemplate.execute(eq(claimCouponScript), anyList(), anyString())).thenReturn(1L);
        when(couponOutboxMapper.insertOutboxEvent(anyLong(), eq(USER_ID), eq(TEMPLATE_ID), anyString()))
                .thenThrow(new RuntimeException("db timeout"))
                .thenReturn(1);
        SendResult sendResult = new SendResult();
        sendResult.setSendStatus(SendStatus.SEND_OK);
        when(rocketMQTemplate.syncSend(eq("COUPON_CLAIM_TOPIC"), any(Message.class), eq(3000L)))
                .thenReturn(sendResult);

        ClaimCouponRequest request = new ClaimCouponRequest();
        request.setTemplateId(TEMPLATE_ID);

        assertThatCode(() -> couponService.claimCoupon(USER_ID, request))
                .doesNotThrowAnyException();
        verify(couponOutboxMapper, times(2))
                .insertOutboxEvent(anyLong(), eq(USER_ID), eq(TEMPLATE_ID), anyString());
        verify(couponOutboxMapper).markOutboxSent(anyString());
        verify(stringRedisTemplate, never()).execute(eq(returnCouponScript), anyList(), any());
    }

    @Test
    @DisplayName("C-007：Outbox 双次写入失败 → 回滚 Redis + 抛错（唯一回滚路径）")
    void claimCouponOutboxDoubleFailureRollsBack() {
        when(valueOperations.get(startsWith("myxhs:coupon:template:"))).thenReturn(null);
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(buildTemplate());
        when(stringRedisTemplate.execute(eq(claimCouponScript), anyList(), anyString())).thenReturn(1L);
        when(couponOutboxMapper.insertOutboxEvent(anyLong(), eq(USER_ID), eq(TEMPLATE_ID), anyString()))
                .thenThrow(new RuntimeException("db down"))
                .thenThrow(new RuntimeException("db down again"));

        ClaimCouponRequest request = new ClaimCouponRequest();
        request.setTemplateId(TEMPLATE_ID);

        assertThatThrownBy(() -> couponService.claimCoupon(USER_ID, request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("领券失败");

        // 唯一回滚路径：Outbox 双次写入失败（可证"无消息在途"）→ 回滚 Redis
        verify(stringRedisTemplate).execute(eq(returnCouponScript), anyList());
        verify(rocketMQTemplate, never()).syncSend(anyString(), any(Message.class), anyLong());
    }

    @Test
    @DisplayName("领券 - 重复领取抛异常")
    void claimDuplicatePrevention() {
        when(valueOperations.get(startsWith("myxhs:coupon:template:"))).thenReturn(null);
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(buildTemplate());

        // Lua 脚本返回 -2（已达限领上限）
        when(stringRedisTemplate.execute(
                eq(claimCouponScript), anyList(), anyString()))
                .thenReturn(-2L);

        ClaimCouponRequest request = new ClaimCouponRequest();
        request.setTemplateId(TEMPLATE_ID);

        assertThatThrownBy(() -> couponService.claimCoupon(USER_ID, request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已达限领上限");
    }

    @Test
    @DisplayName("退券 - 状态匹配时恢复用户券（RV30 语义：不回补库存/不发补偿消息）")
    void returnCouponSuccess() {
        UserCoupon userCoupon = buildUserCoupon();
        when(userCouponMapper.selectByIdAndUserId(11L, USER_ID)).thenReturn(userCoupon);
        when(userCouponMapper.returnCoupon(1L, 9001L, USER_ID)).thenReturn(1);

        ReturnCouponRequest request = new ReturnCouponRequest();
        request.setUserCouponId(11L);
        request.setOrderId(9001L);
        couponService.returnCoupon(USER_ID, request);

        verify(userCouponMapper).returnCoupon(1L, 9001L, USER_ID);
        verifyNoInteractions(rocketMQTemplate);
    }

    @Test
    @DisplayName("退券 - 状态不匹配幂等返回（不抛异常、不触发补偿）")
    void returnCouponIdempotent() {
        UserCoupon userCoupon = buildUserCoupon();
        when(userCouponMapper.selectByIdAndUserId(11L, USER_ID)).thenReturn(userCoupon);
        when(userCouponMapper.returnCoupon(1L, 9001L, USER_ID)).thenReturn(0);

        ReturnCouponRequest request = new ReturnCouponRequest();
        request.setUserCouponId(11L);
        request.setOrderId(9001L);
        couponService.returnCoupon(USER_ID, request);

        verify(userCouponMapper).returnCoupon(1L, 9001L, USER_ID);
        verifyNoInteractions(rocketMQTemplate);
    }

    // ==================== 查询用户优惠券 ====================

    @Test
    @DisplayName("查询用户优惠券列表 - 返回正确列表")
    void listUserCouponsSuccess() {
        UserCoupon userCoupon = buildUserCoupon();
        when(userCouponMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.singletonList(userCoupon));
        when(templateMapper.selectBatchIds(anySet()))
                .thenReturn(Collections.singletonList(buildTemplate()));

        List<UserCouponVO> result = couponService.getUserCoupons(USER_ID, null);

        assertThat(result).hasSize(1);
        UserCouponVO vo = result.get(0);
        assertThat(vo.getCouponId()).isEqualTo(TEMPLATE_ID);
        assertThat(vo.getName()).isEqualTo("满100减10");
        assertThat(vo.getStatus()).isEqualTo(0);
        verify(userCouponMapper).selectList(any(LambdaQueryWrapper.class));
    }

    @Test
    @DisplayName("C-007：可用券列表语义锁定（T-121：下线/孤儿/未生效/已过期过滤 + 单次批量查询消 N+1）")
    void getAvailableCouponsSemanticsLocked() {
        UserCoupon valid = buildUserCoupon();                    // template 1（上线、窗口内）→ 保留
        UserCoupon offline = buildUserCoupon();                  // template 2（已下线）→ 过滤
        offline.setId(2L);
        offline.setCouponId(2L);
        UserCoupon future = buildUserCoupon();                   // template 3（未生效）→ 过滤
        future.setId(3L);
        future.setCouponId(3L);
        UserCoupon expired = buildUserCoupon();                  // template 4（已过期）→ 过滤
        expired.setId(4L);
        expired.setCouponId(4L);
        UserCoupon orphan = buildUserCoupon();                   // template 5（不存在）→ 过滤（孤儿券）
        orphan.setId(5L);
        orphan.setCouponId(5L);

        when(userCouponMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(java.util.Arrays.asList(valid, offline, future, expired, orphan));

        CouponTemplate t1 = buildTemplate();                     // 上线、窗口内
        CouponTemplate t2 = buildTemplate();
        t2.setId(2L);
        t2.setStatus(0);                                         // 下线
        CouponTemplate t3 = buildTemplate();
        t3.setId(3L);
        t3.setValidStart(LocalDateTime.now().plusDays(1));       // 未生效
        CouponTemplate t4 = buildTemplate();
        t4.setId(4L);
        t4.setValidEnd(LocalDateTime.now().minusDays(1));        // 已过期
        // t5 缺失（孤儿）
        when(templateMapper.selectBatchIds(anySet()))
                .thenReturn(java.util.Arrays.asList(t1, t2, t3, t4));

        List<UserCouponVO> result = couponService.getAvailableCoupons(USER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCouponId()).isEqualTo(TEMPLATE_ID);
        // N+1 消除：单次批量查询（绝不逐个 selectById）
        verify(templateMapper, times(1)).selectBatchIds(anySet());
        verify(templateMapper, never()).selectById(any(Long.class));
    }

    // ==================== 查询模板 ====================

    @Test
    @DisplayName("查询优惠券模板 - 根据ID返回正确数据")
    void getTemplateSuccess() {
        // 缓存未命中
        when(valueOperations.get("myxhs:coupon:template:" + TEMPLATE_ID)).thenReturn(null);
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(buildTemplate());

        CouponTemplate result = couponService.getTemplate(TEMPLATE_ID);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(TEMPLATE_ID);
        assertThat(result.getName()).isEqualTo("满100减10");
        assertThat(result.getType()).isEqualTo(1);
        assertThat(result.getDiscountValue()).isEqualByComparingTo("10");
        assertThat(result.getStatus()).isEqualTo(1);
        verify(templateMapper).selectById(TEMPLATE_ID);
    }

    // ==================== 辅助方法 ====================

    private CreateTemplateRequest buildCreateTemplateRequest() {
        CreateTemplateRequest request = new CreateTemplateRequest();
        request.setName("满100减10");
        request.setType(1);
        request.setDiscountValue(new BigDecimal("10"));
        request.setMinAmount(new BigDecimal("100"));
        request.setTotalCount(100);
        request.setPerUserLimit(1);
        request.setValidStart(LocalDateTime.of(2026, 1, 1, 0, 0));
        request.setValidEnd(LocalDateTime.of(2026, 12, 31, 23, 59));
        return request;
    }

    private CouponTemplate buildTemplate() {
        CouponTemplate template = new CouponTemplate();
        template.setId(TEMPLATE_ID);
        template.setName("满100减10");
        template.setType(1);
        template.setDiscountValue(new BigDecimal("10"));
        template.setMinAmount(new BigDecimal("100"));
        template.setTotalCount(100);
        template.setRemainCount(100);
        template.setPerUserLimit(1);
        template.setStatus(1);
        template.setValidStart(LocalDateTime.of(2026, 1, 1, 0, 0));
        template.setValidEnd(LocalDateTime.of(2026, 12, 31, 23, 59));
        return template;
    }

    private UserCoupon buildUserCoupon() {
        UserCoupon userCoupon = new UserCoupon();
        userCoupon.setId(1L);
        userCoupon.setUserId(USER_ID);
        userCoupon.setCouponId(TEMPLATE_ID);
        userCoupon.setStatus(0);
        userCoupon.setReceivedAt(LocalDateTime.now());
        return userCoupon;
    }
}
