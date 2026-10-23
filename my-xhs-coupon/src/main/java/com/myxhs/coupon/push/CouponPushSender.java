package com.myxhs.coupon.push;

import com.myxhs.common.metrics.BusinessMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 优惠券提醒发送器（C-017 P3）
 * <p>
 * 不直接依赖 notification 模块：发 NOTIFICATION_TOPIC，由 notification 消费落库 + SSE。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponPushSender {

    public static final String NOTIFICATION_TOPIC = "NOTIFICATION_TOPIC";
    private static final int TYPE_SYSTEM = 4;

    private final RocketMQTemplate rocketMQTemplate;
    private final BusinessMetrics businessMetrics;

    /** @return true=已投递到 MQ */
    public boolean send(CouponPushTarget target) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", TYPE_SYSTEM);
            payload.put("targetUserId", target.userId());
            payload.put("senderName", "系统");
            payload.put("targetName", "优惠券到期提醒");
            payload.put("content", "您有一张「" + target.templateName() + "」将于明天过期，记得使用");
            payload.put("extraData", "{\"templateId\":" + target.templateId() + "}");
            rocketMQTemplate.syncSend(NOTIFICATION_TOPIC, payload);
            businessMetrics.recordCouponAction("push_ok", true);
            return true;
        } catch (Exception e) {
            log.error("[券推送] 发送失败: templateId={}, userId={}", target.templateId(), target.userId(), e);
            businessMetrics.recordCouponAction("push_fail", false);
            return false;
        }
    }
}
