package com.myxhs.coupon.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.response.ResultCode;
import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.coupon.dto.request.*;
import com.myxhs.coupon.dto.response.UserCouponVO;
import com.myxhs.coupon.entity.CouponTemplate;
import com.myxhs.coupon.entity.UserCoupon;
import com.myxhs.coupon.mapper.CouponTemplateMapper;
import com.myxhs.coupon.mapper.UserCouponMapper;
import com.myxhs.coupon.validator.CouponValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 优惠券服务
 * <p>
 * 核心设计：
 * 1. Lua 原子领券：扣库存 + 限领校验一步到位，防止超发
 * 2. 责任链用券校验：门槛 → 有效期 → 状态，链式校验
 * 3. MQ 同步写 DB：领券后同步发送 MQ，失败则回滚 Redis 库存
 * </p>
 * <p>
 * Redis Key 设计（使用 {templateId} 作为 hash tag，保证 Lua 脚本跨 Key 原子操作在 Cluster 下同 slot）：
 * - 券库存：coupon:{templateId}:stock（String，值=剩余数量）
 * - 用户领取次数：coupon:{templateId}:claimed:userId（String，值=已领次数）
 * - 模板缓存：coupon:template:{templateId}（Hash，缓存模板信息，避免高并发查 MySQL）
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponService {

    private final StringRedisTemplate stringRedisTemplate;
    private final RocketMQTemplate rocketMQTemplate;
    private final CouponTemplateMapper templateMapper;
    private final UserCouponMapper userCouponMapper;
    private final ObjectMapper objectMapper;
    private final DefaultRedisScript<Long> claimCouponScript;
    private final DefaultRedisScript<Long> returnCouponScript;
    private final List<CouponValidator> validators; // Spring 自动注入所有校验器（按 @Order 排序）

    /** 【修复M15】Key 使用 {templateId} 作为 hash tag，保证 stock 和 claimed 落同 slot */
    private static final String STOCK_KEY_TPL = "coupon:{%d}:stock";
    private static final String CLAIMED_KEY_TPL = "coupon:{%d}:claimed:%d";
    private static final String TEMPLATE_KEY_PREFIX = "coupon:template:";
    private static final String COUPON_CLAIM_TOPIC = "COUPON_CLAIM_TOPIC";
    private static final long TEMPLATE_CACHE_SECONDS = 1800L; // 模板缓存 30 分钟

    private static String stockKey(Long templateId) { return String.format(STOCK_KEY_TPL, templateId); }
    private static String claimedKey(Long templateId, Long userId) { return String.format(CLAIMED_KEY_TPL, templateId, userId); }

    // ==================== 券模板管理 ====================

    /**
     * 创建优惠券模板
     * <p>
     * 创建后自动初始化 Redis 库存。
     * 使用 SETNX 保证幂等（同一模板不会重复初始化库存）。
     * </p>
     */
    public CouponTemplate createTemplate(CreateTemplateRequest request) {
        // 参数校验：有效期结束必须晚于开始
        if (request.getValidEnd().isBefore(request.getValidStart())) {
            throw new BizException(ResultCode.PARAM_INVALID, "有效期结束时间必须晚于开始时间");
        }
        // 折扣券校验：折扣值必须在 0.1~9.9 之间
        if (request.getType() == 2) {
            if (request.getDiscountValue().compareTo(new BigDecimal("0.1")) < 0
                    || request.getDiscountValue().compareTo(new BigDecimal("9.9")) > 0) {
                throw new BizException(ResultCode.PARAM_INVALID, "折扣值必须在0.1~9.9之间");
            }
        }

        CouponTemplate template = new CouponTemplate();
        template.setName(request.getName());
        template.setType(request.getType());
        template.setDiscountValue(request.getDiscountValue());
        template.setMinAmount(request.getMinAmount() != null ? request.getMinAmount() : BigDecimal.ZERO);
        template.setTotalCount(request.getTotalCount());
        template.setRemainCount(request.getTotalCount());
        template.setPerUserLimit(request.getPerUserLimit());
        template.setValidStart(request.getValidStart());
        template.setValidEnd(request.getValidEnd());
        template.setStatus(1); // 默认启用
        templateMapper.insert(template);

        // 初始化 Redis 库存（SETNX 保证幂等）
        String stockKey = stockKey(template.getId());
        stringRedisTemplate.opsForValue().setIfAbsent(stockKey, String.valueOf(template.getTotalCount()));

        // 缓存模板信息到 Redis
        cacheTemplate(template);

        log.info("[优惠券] 创建模板: id={}, name={}, type={}, total={}",
                template.getId(), template.getName(), template.getType(), template.getTotalCount());
        return template;
    }

    /**
     * 修改券模板状态（上线/下线）
     * <p>
     * 状态变更后清除模板缓存，下次查询时重新加载。
     * </p>
     */
    public void updateTemplateStatus(Long templateId, Integer status) {
        CouponTemplate template = templateMapper.selectById(templateId);
        if (template == null) {
            throw new BizException(ResultCode.COUPON_NOT_FOUND);
        }
        template.setStatus(status);
        templateMapper.updateById(template);

        // 清除模板缓存（状态变更后必须失效）
        evictTemplateCache(templateId);

        log.info("[优惠券] 模板状态变更: id={}, status={}", templateId, status);
    }

    /**
     * 查询券模板详情
     */
    public CouponTemplate getTemplate(Long templateId) {
        CouponTemplate template = getTemplateWithCache(templateId);
        if (template == null) {
            throw new BizException(ResultCode.COUPON_NOT_FOUND);
        }
        return template;
    }

    // ==================== 领券 ====================

    /**
     * Lua 原子领券
     * <p>
     * 流程：
     * 1. 从缓存获取模板信息（避免高并发查 MySQL）
     * 2. Lua 脚本原子执行：检查库存 → 检查限领 → 扣库存 → 记录领取次数
     * 3. MQ 同步发送写 MySQL（失败则回滚 Redis 库存）
     * </p>
     * <p>
     * 为什么用同步发送而非异步？
     * 异步发送如果失败，Redis 库存已扣但 MySQL 永远不会写入，导致数据不一致。
     * 同步发送失败时可以立即回滚 Redis 库存，保证一致性。
     * 同步发送的性能开销（~2ms）相比 Lua 脚本（~0.1ms）可以接受。
     * </p>
     */
    public void claimCoupon(Long userId, ClaimCouponRequest request) {
        Long templateId = request.getTemplateId();

        // 1. 从缓存获取模板（避免高并发打 MySQL）
        CouponTemplate template = getTemplateWithCache(templateId);
        if (template == null) {
            throw new BizException(ResultCode.COUPON_NOT_FOUND);
        }
        if (template.getStatus() != 1) {
            throw new BizException(ResultCode.COUPON_NOT_AVAILABLE, "优惠券已下线");
        }
        if (LocalDateTime.now().isAfter(template.getValidEnd())) {
            throw new BizException(ResultCode.COUPON_EXPIRED);
        }

        // 2. Lua 原子领券
        String stockKey = stockKey(templateId);
        String claimedKey = claimedKey(templateId, userId);

        Long result = stringRedisTemplate.execute(
                claimCouponScript,
                List.of(stockKey, claimedKey),
                String.valueOf(template.getPerUserLimit())
        );

        if (result == null) {
            throw new BizException(ResultCode.INTERNAL_ERROR, "领券操作失败");
        }

        switch (result.intValue()) {
            case 1 -> {
                // 3. MQ 同步发送（失败则回滚 Redis）
                boolean mqSuccess = sendClaimEventSync(userId, templateId);
                if (!mqSuccess) {
                    // MQ 发送失败，回滚 Redis 库存
                    rollbackRedisStock(stockKey, claimedKey);
                    throw new BizException(ResultCode.INTERNAL_ERROR, "领券失败，请重试");
                }
                log.info("[优惠券] 领券成功: userId={}, templateId={}", userId, templateId);
            }
            case -1 -> throw new BizException(ResultCode.COUPON_SOLD_OUT);
            case -2 -> throw new BizException(ResultCode.COUPON_ALREADY_RECEIVED, "已达限领上限");
            case -3 -> {
                // 券库存未初始化，尝试从 MySQL 初始化
                initStockFromDb(template);
                // 重试一次
                result = stringRedisTemplate.execute(
                        claimCouponScript,
                        List.of(stockKey, claimedKey),
                        String.valueOf(template.getPerUserLimit())
                );
                if (result != null && result == 1) {
                    boolean mqSuccess = sendClaimEventSync(userId, templateId);
                    if (!mqSuccess) {
                        rollbackRedisStock(stockKey, claimedKey);
                        throw new BizException(ResultCode.INTERNAL_ERROR, "领券失败，请重试");
                    }
                    log.info("[优惠券] 领券成功(重试): userId={}, templateId={}", userId, templateId);
                } else {
                    throw new BizException(ResultCode.COUPON_SOLD_OUT);
                }
            }
            default -> throw new BizException(ResultCode.INTERNAL_ERROR, "领券异常: result=" + result);
        }
    }

    // ==================== 用券 ====================

    /**
     * 使用优惠券（订单服务 Feign 调用）
     * <p>
     * 责任链模式逐个校验：门槛 → 有效期 → 状态
     * 全部通过后标记券为已使用。
     * </p>
     */
    public void useCoupon(Long userId, UseCouponRequest request) {
        // 1. 查询用户券
        UserCoupon userCoupon = userCouponMapper.selectById(request.getUserCouponId());
        if (userCoupon == null || !userCoupon.getUserId().equals(userId)) {
            throw new BizException(ResultCode.COUPON_NOT_FOUND);
        }
        if (userCoupon.getStatus() != 0) {
            throw new BizException(ResultCode.COUPON_NOT_AVAILABLE, "优惠券状态异常");
        }

        // 2. 查询券模板（走缓存）
        CouponTemplate template = getTemplateWithCache(userCoupon.getCouponId());
        if (template == null) {
            throw new BizException(ResultCode.COUPON_NOT_FOUND);
        }

        // 3. 责任链校验（门槛 → 有效期 → 状态）
        for (CouponValidator validator : validators) {
            validator.validate(template, request.getOrderAmount());
        }

        // 4. 标记已使用（乐观锁：WHERE status = 0）
        int affected = userCouponMapper.markUsed(userCoupon.getId(), request.getOrderId());
        if (affected == 0) {
            throw new BizException(ResultCode.COUPON_NOT_AVAILABLE, "优惠券已被使用");
        }

        log.info("[优惠券] 用券成功: userId={}, couponId={}, orderId={}",
                userId, userCoupon.getCouponId(), request.getOrderId());
    }

    // ==================== 退券 ====================

    /**
     * 退回优惠券（取消订单时调用）
     * <p>
     * 1. MySQL：恢复券状态为未使用（乐观锁）
     * 2. MySQL：原子回退模板剩余数量（SQL: remain_count + 1）
     * 3. Redis：回退库存 + 减少用户领取次数（Lua 原子操作）
     * </p>
     * <p>
     * 为什么退券时要回退 Redis 库存？
     * 如果不回退，券的"剩余数量"会越来越少（即使实际上券被退回了）。
     * 回退后其他用户可以重新领取这张券。
     * </p>
     */
    public void returnCoupon(Long userId, ReturnCouponRequest request) {
        // 1. 查询用户券
        UserCoupon userCoupon = userCouponMapper.selectById(request.getUserCouponId());
        if (userCoupon == null || !userCoupon.getUserId().equals(userId)) {
            throw new BizException(ResultCode.COUPON_NOT_FOUND);
        }

        // 2. MySQL 恢复状态（乐观锁：WHERE status = 1 AND used_order_id = orderId）
        int affected = userCouponMapper.returnCoupon(userCoupon.getId(), request.getOrderId());
        if (affected == 0) {
            log.warn("[优惠券] 退券失败(状态不匹配): userCouponId={}, orderId={}",
                    request.getUserCouponId(), request.getOrderId());
            return; // 幂等：已退回或状态不匹配，不报错
        }

        // 3. MySQL 原子回退模板剩余数量（SQL 原子操作，避免并发 ABA 问题）
        templateMapper.incrementRemainCount(userCoupon.getCouponId());

        // 4. Redis 回退库存 + 减少领取次数（Lua 原子操作）
        String stockKey = stockKey(userCoupon.getCouponId());
        String claimedKey = claimedKey(userCoupon.getCouponId(), userId);

        Long result = stringRedisTemplate.execute(
                returnCouponScript,
                List.of(stockKey, claimedKey)
        );

        log.info("[优惠券] 退券成功: userId={}, couponId={}, orderId={}, redisResult={}",
                userId, userCoupon.getCouponId(), request.getOrderId(), result);
    }

    // ==================== 查询 ====================

    /**
     * 查询用户优惠券列表
     */
    public List<UserCouponVO> getUserCoupons(Long userId, Integer status) {
        LambdaQueryWrapper<UserCoupon> wrapper = new LambdaQueryWrapper<UserCoupon>()
                .eq(UserCoupon::getUserId, userId)
                .orderByDesc(UserCoupon::getReceivedAt);
        if (status != null) {
            wrapper.eq(UserCoupon::getStatus, status);
        }

        List<UserCoupon> userCoupons = userCouponMapper.selectList(wrapper);
        return batchToVO(userCoupons);
    }

    /**
     * 查询用户可用优惠券（下单时展示）
     * <p>
     * 只返回状态=0（未使用）且在有效期内的券。
     * </p>
     */
    public List<UserCouponVO> getAvailableCoupons(Long userId) {
        LambdaQueryWrapper<UserCoupon> wrapper = new LambdaQueryWrapper<UserCoupon>()
                .eq(UserCoupon::getUserId, userId)
                .eq(UserCoupon::getStatus, 0)
                .orderByDesc(UserCoupon::getReceivedAt);

        List<UserCoupon> userCoupons = userCouponMapper.selectList(wrapper);

        // 批量转换 + 过滤掉已过期的（实时校验，不依赖定时任务）
        LocalDateTime now = LocalDateTime.now();
        return batchToVO(userCoupons).stream()
                .filter(vo -> vo.getValidEnd() != null && vo.getValidEnd().isAfter(now))
                .collect(Collectors.toList());
    }

    // ==================== 私有方法 ====================

    /**
     * 从缓存获取模板信息（Redis → MySQL 二级查询）
     * <p>
     * 高并发领券时避免每次都查 MySQL。
     * 模板信息变更频率低（管理员操作），适合缓存。
     * </p>
     */
    private CouponTemplate getTemplateWithCache(Long templateId) {
        String cacheKey = TEMPLATE_KEY_PREFIX + templateId;

        // 1. 尝试从 Redis 获取
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            if ("NULL".equals(cached)) {
                return null; // 缓存空值，防止缓存穿透
            }
            try {
                return objectMapper.readValue(cached, CouponTemplate.class);
            } catch (Exception e) {
                log.warn("[优惠券] 模板缓存反序列化失败: templateId={}", templateId, e);
                evictTemplateCache(templateId);
            }
        }

        // 2. 查 MySQL
        CouponTemplate template = templateMapper.selectById(templateId);

        // 3. 写入缓存
        if (template != null) {
            cacheTemplate(template);
        } else {
            // 缓存空值 60 秒，防止缓存穿透
            stringRedisTemplate.opsForValue().set(cacheKey, "NULL",
                    java.time.Duration.ofSeconds(60));
        }

        return template;
    }

    /**
     * 缓存模板信息到 Redis
     */
    private void cacheTemplate(CouponTemplate template) {
        try {
            String cacheKey = TEMPLATE_KEY_PREFIX + template.getId();
            String json = objectMapper.writeValueAsString(template);
            stringRedisTemplate.opsForValue().set(cacheKey, json,
                    java.time.Duration.ofSeconds(TEMPLATE_CACHE_SECONDS));
        } catch (Exception e) {
            log.warn("[优惠券] 模板缓存写入失败: templateId={}", template.getId(), e);
        }
    }

    /**
     * 清除模板缓存
     */
    private void evictTemplateCache(Long templateId) {
        stringRedisTemplate.delete(TEMPLATE_KEY_PREFIX + templateId);
    }

    /**
     * 从 MySQL 初始化 Redis 库存（券库存未初始化时的兜底）
     */
    private void initStockFromDb(CouponTemplate template) {
        String stockKey = stockKey(template.getId());
        stringRedisTemplate.opsForValue().setIfAbsent(stockKey, String.valueOf(template.getRemainCount()));
        log.info("[优惠券] 从DB初始化Redis库存: templateId={}, stock={}",
                template.getId(), template.getRemainCount());
    }

    /**
     * 同步发送领券事件到 MQ
     * <p>
     * 为什么用同步而非异步？
     * 异步发送失败时无法回滚 Redis 库存，导致"Redis 扣了但 MySQL 没写"的不一致。
     * 同步发送失败时可以立即回滚，保证强一致。
     * </p>
     *
     * @return true=发送成功, false=发送失败
     */
    private boolean sendClaimEventSync(Long userId, Long templateId) {
        try {
            String payload = objectMapper.writeValueAsString(
                    new CouponClaimEvent(userId, templateId));
            SendResult sendResult = rocketMQTemplate.syncSend(
                    COUPON_CLAIM_TOPIC,
                    MqTraceHelper.wrapWithTraceId(MessageBuilder.withPayload(payload).build()),
                    3000 // 超时 3 秒
            );
            if (sendResult.getSendStatus() == SendStatus.SEND_OK) {
                log.debug("[优惠券] MQ同步发送成功: userId={}, templateId={}", userId, templateId);
                return true;
            } else {
                log.error("[优惠券] MQ发送状态异常: userId={}, templateId={}, status={}",
                        userId, templateId, sendResult.getSendStatus());
                return false;
            }
        } catch (Exception e) {
            log.error("[优惠券] MQ同步发送异常: userId={}, templateId={}", userId, templateId, e);
            return false;
        }
    }

    /**
     * 回滚 Redis 库存（MQ 发送失败时调用）
     * <p>
     * 使用 return_coupon.lua 原子回退库存 + 减少领取次数。
     * 保证 Redis 状态和"领券未成功"一致。
     * </p>
     */
    private void rollbackRedisStock(String stockKey, String claimedKey) {
        try {
            stringRedisTemplate.execute(returnCouponScript, List.of(stockKey, claimedKey));
            log.info("[优惠券] Redis库存回滚成功: stockKey={}", stockKey);
        } catch (Exception e) {
            // 回滚失败是极端情况，记录告警日志，后续对账任务修复
            log.error("[优惠券] Redis库存回滚失败(需人工介入): stockKey={}", stockKey, e);
        }
    }

    /**
     * 批量转换 UserCoupon → UserCouponVO（解决 N+1 查询问题）
     * <p>
     * 先批量查询所有关联的模板，再用 Map 关联。
     * 避免每个 UserCoupon 都单独查一次模板。
     * </p>
     */
    private List<UserCouponVO> batchToVO(List<UserCoupon> userCoupons) {
        if (userCoupons.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. 收集所有模板 ID（去重）
        Set<Long> templateIds = userCoupons.stream()
                .map(UserCoupon::getCouponId)
                .collect(Collectors.toSet());

        // 2. 批量查询模板
        List<CouponTemplate> templates = templateMapper.selectBatchIds(templateIds);
        Map<Long, CouponTemplate> templateMap = templates.stream()
                .collect(Collectors.toMap(CouponTemplate::getId, Function.identity()));

        // 3. 组装 VO
        return userCoupons.stream().map(uc -> {
            CouponTemplate template = templateMap.get(uc.getCouponId());
            UserCouponVO.UserCouponVOBuilder builder = UserCouponVO.builder()
                    .id(uc.getId())
                    .couponId(uc.getCouponId())
                    .status(uc.getStatus())
                    .receivedAt(uc.getReceivedAt());

            if (template != null) {
                builder.name(template.getName())
                        .type(template.getType())
                        .discountValue(template.getDiscountValue())
                        .minAmount(template.getMinAmount())
                        .validEnd(template.getValidEnd());
            }
            return builder.build();
        }).collect(Collectors.toList());
    }

    /**
     * 领券事件（MQ 消息体）
     */
    public record CouponClaimEvent(Long userId, Long templateId) {}
}
