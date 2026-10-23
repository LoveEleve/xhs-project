package com.myxhs.coupon.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.coupon.entity.CouponTemplate;
import com.myxhs.coupon.mapper.CouponTemplateMapper;
import com.myxhs.coupon.mapper.UserCouponMapper;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 券过期延时消息消费者测试（C-017）
 */
class CouponExpireDelayConsumerTest {

    private CouponTemplateMapper templateMapper;
    private UserCouponMapper userCouponMapper;
    private CouponExpireDelayConsumer consumer;

    @BeforeEach
    void setUp() {
        templateMapper = mock(CouponTemplateMapper.class);
        userCouponMapper = mock(UserCouponMapper.class);
        consumer = new CouponExpireDelayConsumer(templateMapper, userCouponMapper, new ObjectMapper());
    }

    private MessageExt message(long templateId) {
        MessageExt msg = new MessageExt();
        msg.setMsgId("msg-" + templateId);
        msg.setBody(("{\"templateId\":" + templateId + "}").getBytes(StandardCharsets.UTF_8));
        return msg;
    }

    private CouponTemplate template(LocalDateTime validEnd) {
        CouponTemplate t = new CouponTemplate();
        t.setId(1L);
        t.setStatus(1);
        t.setDeleted(0);
        t.setValidEnd(validEnd);
        return t;
    }

    @Test
    @DisplayName("到点消费：批量把该模板未使用券置过期")
    void expireAtDue() {
        when(templateMapper.selectById(1L)).thenReturn(template(LocalDateTime.now().minusMinutes(1)));
        when(userCouponMapper.batchExpireByCoupon(eq(1L), anyInt())).thenReturn(2);

        consumer.onMessage(message(1L));

        verify(userCouponMapper, times(1)).batchExpireByCoupon(eq(1L), anyInt());
    }

    @Test
    @DisplayName("消息提前到达：validEnd 未到 → 忽略（Job 兜底）")
    void earlyMessageIgnored() {
        when(templateMapper.selectById(1L)).thenReturn(template(LocalDateTime.now().plusDays(1)));

        consumer.onMessage(message(1L));

        verifyNoInteractions(userCouponMapper);
    }

    @Test
    @DisplayName("模板不存在/已删除：忽略")
    void missingTemplateIgnored() {
        when(templateMapper.selectById(1L)).thenReturn(null);

        consumer.onMessage(message(1L));

        verifyNoInteractions(userCouponMapper);
    }

    @Test
    @DisplayName("消息缺少 templateId：忽略")
    void missingTemplateIdIgnored() {
        MessageExt msg = new MessageExt();
        msg.setMsgId("bad");
        msg.setBody("{}".getBytes(StandardCharsets.UTF_8));

        consumer.onMessage(msg);

        verifyNoInteractions(templateMapper, userCouponMapper);
    }
}
