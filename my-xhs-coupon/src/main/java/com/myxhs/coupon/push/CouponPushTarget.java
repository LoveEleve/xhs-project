package com.myxhs.coupon.push;

import java.time.LocalDateTime;

/**
 * 推送目标（一个用户 × 一张到期券）
 */
public record CouponPushTarget(Long templateId, String templateName,
                               Long userId, LocalDateTime validEnd) {
}
