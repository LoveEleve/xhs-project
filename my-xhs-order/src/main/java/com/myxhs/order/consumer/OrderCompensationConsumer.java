package com.myxhs.order.consumer;

import com.myxhs.common.entity.CompensationMessage;
import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.order.entity.OrderNoMapping;
import com.myxhs.order.repository.OrderNoMappingRepository;
import com.myxhs.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import com.alibaba.fastjson2.JSON;

/**
 * 订单补偿消费者
 * <p>
 * 消费 ORDER_COMPENSATION_TOPIC 中的补偿消息（Feign 调用失败时发送）。
 * 重试执行关单逻辑（释放库存、退还优惠券等），保证最终一致性。
 * </p>
 * <p>
 * 幂等保护：
 * 1. Redis SETNX 快速去重（10min 过期）
 * 2. closeTimeoutOrder 内部乐观锁（WHERE status=0）天然幂等
 * </p>
 * <p>
 * 重试策略：
 * maxReconsumeTimes=3，3 次失败后进入 DLQ，
 * 由运维人工处理或定时任务继续兜底。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "ORDER_COMPENSATION_TOPIC",
        consumerGroup = "order-compensation-consumer-group",
        maxReconsumeTimes = 3
)
public class OrderCompensationConsumer implements RocketMQListener<MessageExt> {

    private final OrderService orderService;
    private final StringRedisTemplate stringRedisTemplate;
    private final OrderNoMappingRepository orderNoMappingRepository;

    private static final String COMPENSATION_CONSUMED_PREFIX = "order:compensation:consumed:";

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        CompensationMessage cm = null;
        Long userId = null;
        try {
            String msgId = msg.getMsgId();
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);

            // 解析补偿消息
            try {
                cm = JSON.parseObject(body, CompensationMessage.class);
            } catch (Exception e) {
                log.warn("[补偿] 消息体解析失败, 尝试解析原始格式: msgId={}", msgId);
                // 兼容旧格式 Map {action, orderId, timestamp}
                cm = new CompensationMessage();
                var rawMap = JSON.parseObject(body, java.util.Map.class);
                if (rawMap != null && rawMap.containsKey("orderId")) {
                    // JacksonConfig 全局 Long→String，orderId 可能是字符串，兼容解析
                    Long orderId = toLong(rawMap.get("orderId"));
                    if (orderId != null) {
                        cm.setOrderId(orderId);
                        cm.setAction((String) rawMap.get("action"));
                        Long ts = toLong(rawMap.get("timestamp"));
                        cm.setTimestamp(ts != null ? ts : System.currentTimeMillis());
                    }
                }
            }

            if (cm == null || cm.getOrderId() == null) {
                log.warn("[补偿] 消息体解析失败或缺少orderId: msgId={}", msgId);
                return;
            }

            Long orderId = cm.getOrderId();

            // 一级幂等：Redis SETNX 用 msgId 去重（防 reconsumeTimes 变化导致重复）
            String consumedKey = COMPENSATION_CONSUMED_PREFIX + msgId;
            Boolean alreadyConsumed = stringRedisTemplate.hasKey(consumedKey);
            if (Boolean.TRUE.equals(alreadyConsumed)) {
                log.info("[补偿] Redis去重跳过: orderId={}, msgId={}", orderId, msgId);
                return;
            }

            // 获取 userId（通过映射表反查，分库分表路由需要）
            String userIdStr = msg.getUserProperty("userId");
            if (userIdStr != null) {
                try {
                    userId = Long.parseLong(userIdStr);
                } catch (NumberFormatException nfe) {
                    log.warn("[补偿] 无效userId: userIdStr={}, orderId={}", userIdStr, orderId);
                }
            }
            if (userId == null) {
                OrderNoMapping mapping = orderNoMappingRepository.selectByOrderId(orderId);
                if (mapping == null || mapping.getUserId() == null) {
                    throw new IllegalStateException("补偿消息缺少路由信息: orderId=" + orderId);
                }
                userId = mapping.getUserId();
            }

            log.info("[补偿] 开始处理: orderId={}, userId={}, action={}, reconsumeTimes={}",
                    orderId, userId, cm.getAction(), msg.getReconsumeTimes());

            // 2. 执行补偿——按 action 分发（P1-2 修复）：
            //   RELEASE_STOCK → compensateReleaseStock（不依赖订单状态，已付款/已取消都释放）
            //   RETURN_COUPON → compensateReturnCoupon（不依赖订单状态）
            //   CLOSE_ORDER → closeTimeoutOrder（关单）
            //   统一走专门补偿方法，避免旧实现一律调 closeTimeoutOrder 对 status!=0 订单跳过导致库存/券泄漏。
            switch (cm.getAction() == null ? "CLOSE_ORDER" : cm.getAction()) {
                case "RELEASE_STOCK" -> orderService.compensateReleaseStock(orderId, userId);
                case "RETURN_COUPON" -> orderService.compensateReturnCoupon(orderId, userId);
                default -> orderService.closeTimeoutOrder(orderId, userId);
            }
            log.info("[补偿] 处理成功: orderId={}, action={}", orderId, cm.getAction());

            // 3. 消费成功后才写入去重 Key
            stringRedisTemplate.opsForValue().set(consumedKey, "1", Duration.ofMinutes(10));

        } catch (Exception e) {
            log.error("[补偿] 关单补偿失败: msgId={}, reconsumeTimes={}",
                    msg.getMsgId(), msg.getReconsumeTimes(), e);
            // 接近重试上限时写入 Redis 兜底集合，由 OrderCloseJob 每分钟重放，
            // 避免进 DLQ 后无消费者导致库存/优惠券永久泄漏。
            if (msg.getReconsumeTimes() >= 2) {
                writeCompensationFallback(cm, userId);
            }
            throw new RuntimeException("关单补偿失败", e); // 触发 RocketMQ 重试
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }

    private static final String ORDER_COMPENSATION_FALLBACK_KEY = "myxhs:order:compensation:pending";

    /** 兼容数字/字符串形式的 Long 解析（JacksonConfig 全局 Long→String 序列化） */
    private Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void writeCompensationFallback(CompensationMessage cm, Long userId) {
        try {
            if (cm == null || cm.getOrderId() == null) {
                return;
            }
            String action = cm.getAction() == null ? "CLOSE_ORDER" : cm.getAction();
            if (userId == null) {
                return;
            }
            String member = action + ":" + cm.getOrderId() + ":" + userId;
            stringRedisTemplate.opsForSet().add(ORDER_COMPENSATION_FALLBACK_KEY, member);
            log.warn("[补偿] 补偿失败已达重试上限，写入Redis兜底集合: {}", member);
        } catch (Exception e) {
            log.error("[补偿] 写入Redis兜底集合失败: orderId={}, userId={}", cm != null ? cm.getOrderId() : null, userId, e);
        }
    }
}
