package com.myxhs.coupon.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.exception.BizException;
import com.myxhs.coupon.dto.request.ClaimCouponRequest;
import com.myxhs.coupon.dto.request.CreateTemplateRequest;
import com.myxhs.coupon.dto.response.UserCouponVO;
import com.myxhs.coupon.entity.CouponTemplate;
import com.myxhs.coupon.entity.UserCoupon;
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
    private DefaultRedisScript<Long> claimCouponScript;
    @Mock
    private DefaultRedisScript<Long> returnCouponScript;
    @Mock
    private CouponValidator couponValidator;

    private ObjectMapper objectMapper;
    private CouponService couponService;

    private static final Long USER_ID = 1001L;
    private static final Long TEMPLATE_ID = 1L;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        List<CouponValidator> validators = Collections.singletonList(couponValidator);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        couponService = new CouponService(
                stringRedisTemplate, rocketMQTemplate,
                templateMapper, userCouponMapper,
                mock(com.myxhs.coupon.mapper.CouponOutboxMapper.class),
                mock(com.myxhs.common.id.IdGeneratorUtil.class),
                objectMapper,
                claimCouponScript, returnCouponScript,
                validators
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
