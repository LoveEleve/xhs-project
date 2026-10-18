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
import com.myxhs.coupon.mapper.CouponOutboxMapper;
import com.myxhs.coupon.mapper.UserCouponMapper;
import com.myxhs.common.id.IdGeneratorUtil;
import com.myxhs.common.metrics.BusinessMetrics;
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
import org.springframework.transaction.annotation.Transactional;

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
 * - 券库存：myxhs:coupon:{templateId}:stock（String，值=剩余数量）
 * - 用户领取次数：myxhs:coupon:{templateId}:claimed:userId（String，值=已领次数）
 * - 模板缓存：myxhs:coupon:template:{templateId}（JSON String，缓存模板信息，避免高并发查 MySQL）
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
    private final CouponOutboxMapper outboxMapper;
    private final IdGeneratorUtil idGeneratorUtil;
    private final ObjectMapper objectMapper;
    private final DefaultRedisScript<Long> claimCouponScript;
    private final DefaultRedisScript<Long> returnCouponScript;
    private final List<CouponValidator> validators; // Spring 自动注入所有校验器（按 @Order 排序）
    private final BusinessMetrics businessMetrics; // C-007：业务指标（领/用/退）

    /** 【修复M15】Key 使用 {templateId} 作为 hash tag，保证 stock 和 claimed 落同 slot */
    private static final String STOCK_KEY_TPL = "myxhs:coupon:{%d}:stock";
    private static final String CLAIMED_KEY_TPL = "myxhs:coupon:{%d}:claimed:%d";
    private static final String TEMPLATE_KEY_PREFIX = "myxhs:coupon:template:";
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
        // T-121（2026-08-16）：非法状态校验（对照 product updateSpuStatus 40002）——
        // 修复前 status=2 等非法值直接入库，无业务语义
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException(ResultCode.PARAM_INVALID, "优惠券状态无效，仅支持 0(下线) 或 1(上线)");
        }
        CouponTemplate template = templateMapper.selectById(templateId);
        if (template == null) {
            throw new BizException(ResultCode.COUPON_NOT_FOUND);
        }
        // 定向更新 status（原生 SQL）：updateById 全字段写会覆盖并发的 decrementRemainCount（丢失扣减）
        templateMapper.updateStatusOnly(templateId, status);

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

    /**
     * 可领券模板列表（领券中心公开接口）
     * <p>
     * 返回当前可领取的模板：状态=上架、未删除、剩余>0、且在有效期内。
     * </p>
     */
    public List<CouponTemplate> listClaimableTemplates() {
        LocalDateTime now = LocalDateTime.now();
        return templateMapper.selectList(new LambdaQueryWrapper<CouponTemplate>()
                .eq(CouponTemplate::getStatus, 1)
                .eq(CouponTemplate::getDeleted, 0)
                .gt(CouponTemplate::getRemainCount, 0)
                .le(CouponTemplate::getValidStart, now)
                .ge(CouponTemplate::getValidEnd, now)
                .orderByDesc(CouponTemplate::getCreatedAt));
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
        if (template.getValidStart() != null && LocalDateTime.now().isBefore(template.getValidStart())) {
            throw new BizException(ResultCode.COUPON_NOT_AVAILABLE, "活动尚未开始");
        }
        if (template.getValidEnd() != null && LocalDateTime.now().isAfter(template.getValidEnd())) {
            throw new BizException(ResultCode.COUPON_EXPIRED);
        }

        // 2. Lua 原子领券（-3 = 库存未初始化 → DB 自愈重试一次）
        String stockKey = stockKey(templateId);
        String claimedKey = claimedKey(templateId, userId);

        Long result = executeClaimLua(stockKey, claimedKey, template.getPerUserLimit());
        if (result == null) {
            throw new BizException(ResultCode.INTERNAL_ERROR, "领券操作失败");
        }

        if (result.intValue() == -3) {
            // 券库存未初始化，尝试从 MySQL 实时初始化
            initStockFromDb(templateId);
            // 重试一次（T-121：重试结果按语义分派——-2 限领应返回 30013 而非 30014 售罄）
            result = executeClaimLua(stockKey, claimedKey, template.getPerUserLimit());
            if (result == null) {
                throw new BizException(ResultCode.INTERNAL_ERROR, "领券操作失败");
            }
            dispatchClaimResult(result.intValue(), userId, templateId, stockKey, claimedKey, "(重试)");
        } else {
            dispatchClaimResult(result.intValue(), userId, templateId, stockKey, claimedKey, "");
        }
    }

    /** 执行领券 Lua（主流程与 -3 自愈重试共用） */
    private Long executeClaimLua(String stockKey, String claimedKey, Integer perUserLimit) {
        return stringRedisTemplate.execute(
                claimCouponScript,
                List.of(stockKey, claimedKey),
                String.valueOf(perUserLimit));
    }

    /**
     * 领券结果分派（主流程与 -3 自愈重试共用；C-007：提取原重复 switch）。
     * <p>result=1 → 发送链（Design B：仅 Outbox 双次写入失败才回滚 + 报错，其余恒成功）。</p>
     */
    private void dispatchClaimResult(int result, Long userId, Long templateId,
                                     String stockKey, String claimedKey, String logTag) {
        switch (result) {
            case 1 -> {
                String claimNo = sendClaimEventSync(userId, templateId);
                if (claimNo == null) {
                    // 唯一回滚路径：Outbox 双次写入失败（可证"无消息在途"）
                    rollbackRedisStock(stockKey, claimedKey);
                    businessMetrics.recordCouponAction("claim_fail", false);
                    throw new BizException(ResultCode.INTERNAL_ERROR, "领券失败，请重试");
                }
                log.info("[优惠券] 领券成功{}: userId={}, templateId={}", logTag, userId, templateId);
                businessMetrics.recordCouponAction("claim_ok", true);
            }
            case -1 -> {
                businessMetrics.recordCouponAction("claim_sold_out", false);
                throw new BizException(ResultCode.COUPON_SOLD_OUT);
            }
            case -2 -> {
                businessMetrics.recordCouponAction("claim_limit", false);
                throw new BizException(ResultCode.COUPON_ALREADY_RECEIVED, "已达限领上限");
            }
            default -> {
                businessMetrics.recordCouponAction("claim_fail", false);
                throw new BizException(ResultCode.INTERNAL_ERROR, "领券异常: result=" + result);
            }
        }
    }

    // ==================== 查询折扣（不核销） ====================

    /**
     * 查询优惠券折扣金额（下单前调用，不标记已使用）
     * @param userId 用户ID（校验券归属）
     * @param userCouponId 用户券记录 ID
     * @param orderAmount 订单金额（用于门槛校验和折扣计算）
     * @return 折扣金额
     */
    public java.math.BigDecimal getCouponDiscount(Long userId, Long userCouponId, java.math.BigDecimal orderAmount) {
        UserCoupon userCoupon = userCouponMapper.selectById(userCouponId);
        if (userCoupon == null || !userCoupon.getUserId().equals(userId)) {
            throw new BizException(ResultCode.COUPON_NOT_AVAILABLE, "优惠券不属于当前用户");
        }
        if (userCoupon.getStatus() != 0) {
            throw new BizException(ResultCode.COUPON_NOT_AVAILABLE, "优惠券不可用");
        }
        CouponTemplate template = getTemplateWithCache(userCoupon.getCouponId());
        if (template == null) {
            throw new BizException(ResultCode.COUPON_NOT_FOUND);
        }
        for (CouponValidator validator : validators) {
            validator.validate(template, orderAmount);
        }
        return calculateDiscount(template, orderAmount);
    }

    // ==================== 用券 ====================

    /**
     * 使用优惠券（订单服务 Feign 调用）
     * <p>
     * 责任链模式逐个校验：门槛 → 有效期 → 状态
     * 全部通过后标记券为已使用。
     * </p>
     */
    /**
     * 核销优惠券（创建订单时调用）
     * @return 折扣金额
     */
    public java.math.BigDecimal useCoupon(Long userId, UseCouponRequest request) {
        try {
            // 1. 查询用户券
            UserCoupon userCoupon = userCouponMapper.selectById(request.getUserCouponId());
            if (userCoupon == null || !userCoupon.getUserId().equals(userId)) {
                throw new BizException(ResultCode.COUPON_NOT_FOUND);
            }
            if (userCoupon.getStatus() != 0) {
                // T-121（2026-08-16）：状态文案精确化——1=已使用（含并发乐观锁失败），2=已过期标记
                if (userCoupon.getStatus() == 1) {
                    throw new BizException(ResultCode.COUPON_NOT_AVAILABLE, "优惠券已被使用");
                }
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

            // 4. 计算折扣
            java.math.BigDecimal discount = calculateDiscount(template, request.getOrderAmount());

            // 5. 标记已使用（乐观锁：WHERE status = 0）
            int affected = userCouponMapper.markUsed(userCoupon.getId(), request.getOrderId());
            if (affected == 0) {
                throw new BizException(ResultCode.COUPON_NOT_AVAILABLE, "优惠券已被使用");
            }

            log.info("[优惠券] 用券成功: userId={}, couponId={}, orderId={}, discount={}",
                    userId, userCoupon.getCouponId(), request.getOrderId(), discount);
            businessMetrics.recordCouponAction("use_ok", true);
            return discount;
        } catch (BizException e) {
            // C-007：业务拒绝路径统一观测（未找到/不可用/责任链拦截/并发冲突）
            businessMetrics.recordCouponAction("use_fail", false);
            throw e;
        }
    }

    private java.math.BigDecimal calculateDiscount(CouponTemplate template, java.math.BigDecimal orderAmount) {
        java.math.BigDecimal discount = switch (template.getType()) {
            case 1 -> template.getDiscountValue(); // 满减
            case 2 -> orderAmount.subtract(orderAmount.multiply(template.getDiscountValue())
                    .divide(new java.math.BigDecimal("10"), 2, java.math.RoundingMode.HALF_UP)); // 折扣
            case 3 -> template.getDiscountValue(); // 无门槛
            default -> java.math.BigDecimal.ZERO;
        };
        // 减免不能超过订单金额（防 0 元购）
        return discount.min(orderAmount);
    }

    // ==================== 退券 ====================

    /**
     * 退回优惠券（取消订单时恢复为"未使用"）
     * <p>
     * 语义（RV30 修正）：只恢复用户券状态（乐观锁 WHERE status=1 AND used_order_id=orderId，天然幂等）；
     * **不回补模板 stock/remain、不减 claimed**——核销（useCoupon）不扣 remain，
     * 若退券回补会形成 remain+已发>total 的超发敞口，且用户可"领→用→退→再领"绕过 perUserLimit；
     * 用户仍持有该券，领取次数不应回退。
     * </p>
     * <p>
     * 为什么状态不匹配时静默返回？退券由订单终止事件驱动（可能重复投递），
     * affected=0 表示已退回/非本单，属正常幂等路径，不报错。
     * </p>
     */
    @Transactional(rollbackFor = Exception.class)
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
            businessMetrics.recordCouponAction("return_skip", true);
            return; // 幂等：已退回或状态不匹配，不报错
        }

        // 3. 语义（RV30，见 javadoc）：仅恢复用户券状态；不触碰 stock/remain/claimed（防超发敞口）
        log.info("[优惠券] 退券完成(仅恢复用户券状态): couponId={}, userId={}, userCouponId={}",
                userCoupon.getCouponId(), userId, userCoupon.getId());
        businessMetrics.recordCouponAction("return_ok", true);
        // 【C-007 删除】原 evictTemplateCache 调用：退券不修改模板任何字段 → 无需失效模板缓存
    }

    // ==================== 查询 ====================

    /**
     * 查询用户优惠券列表
     */
    public List<UserCouponVO> getUserCoupons(Long userId, Integer status) {
        // RV32：加硬上限防全量扫描（单用户券量上限 200，超出先取最近 200 张）
        LambdaQueryWrapper<UserCoupon> wrapper = new LambdaQueryWrapper<UserCoupon>()
                .eq(UserCoupon::getUserId, userId)
                .orderByDesc(UserCoupon::getReceivedAt)
                .last("LIMIT 200");
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

        if (userCoupons.isEmpty()) {
            return Collections.emptyList();
        }

        // C-007：单次批量查询模板（原实现按 distinct couponId 逐个 selectById → N+1），
        // 同一份 Map 复用给"下线过滤"与"VO 构建"（batchToVO 重载）。
        // T-121 语义保持（2026-08-16）：补 validStart 过滤——修复前"可用"列表含未来生效券
        // （用户可见但 use 时被责任链拦截，体验不一致；与 useCoupon 语义对齐）
        Set<Long> templateIds = userCoupons.stream()
                .map(UserCoupon::getCouponId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, CouponTemplate> templateMap = templateIds.isEmpty()
                ? Collections.emptyMap()
                : templateMapper.selectBatchIds(templateIds).stream()
                        .collect(Collectors.toMap(CouponTemplate::getId, Function.identity()));

        LocalDateTime now = LocalDateTime.now();
        // 过滤下线模板（原实现不过滤 → 已下线模板的券仍出现在"可用"列表，用券时才被拒）：
        // 模板不存在（逻辑删除）或状态非"上线"都视为下线
        return batchToVO(userCoupons, templateMap).stream()
                .filter(vo -> {
                    CouponTemplate t = templateMap.get(vo.getCouponId());
                    return t != null && t.getStatus() != null && t.getStatus() == 1;
                })
                .filter(vo -> vo.getValidEnd() != null && vo.getValidEnd().isAfter(now)
                        && (vo.getValidStart() == null || !vo.getValidStart().isAfter(now)))
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
     * 从 MySQL 实时初始化 Redis 库存（券库存未初始化时的兜底）
     * <p>
     * 必须读 DB 实时 remain_count，不能复用缓存模板的 remainCount：
     * 模板缓存 TTL 1800s，若期间已领大量券，缓存值会明显落后于实际，导致 Redis 库存按旧值重置 → 瞬时超发敞口。
     * </p>
     */
    private void initStockFromDb(Long templateId) {
        CouponTemplate fresh = templateMapper.selectById(templateId);
        if (fresh == null || fresh.getDeleted() != null && fresh.getDeleted() == 1) {
            log.warn("[优惠券] 从DB初始化Redis库存失败(模板不存在): templateId={}", templateId);
            return;
        }
        String stockKey = stockKey(templateId);
        stringRedisTemplate.opsForValue().setIfAbsent(stockKey, String.valueOf(fresh.getRemainCount()));
        log.info("[优惠券] 从DB初始化Redis库存: templateId={}, stock={}",
                templateId, fresh.getRemainCount());
    }

    /**
     * 领券事件同步发送（C-007 Design B：名额锁定即成功 + 券必达·至少一次）
     * <p>
     * 语义（产品对齐：内部故障不进用户视野——超时≠失败）：
     * ① Outbox 先落（同 claimNo 幂等重试一次：写入"超时≠失败"，DuplicateKey=实际已写入按成功）；
     *    **双次写入仍失败 → 返回 null**（调用方回滚 Redis + 报错——唯一回滚路径：此刻可证"无消息在途"）
     * ② syncSend 任何结果均**保留 Outbox**（补发 Job 必达）：
     *    SEND_OK → markOutboxSent 尽力（失败仅告警，行保留 → 补发重复由消费端 uk_claim_no 幂等兜底）；
     *    异常/非 SEND_OK（消息可能已写入 broker）→ 仅告警，行保留
     * ③ 返回 claimNo（闸门放行后恒成功）
     * </p>
     *
     * @return claimNo=成功（含"发送未确认但已入 Outbox"）；null=仅"Outbox 双次写入失败"
     */
    private String sendClaimEventSync(Long userId, Long templateId) {
        String claimNo = java.util.UUID.randomUUID().toString().replace("-", "");

        // ① Outbox 先落（同 claimNo 幂等重试一次——写入超时≠失败）
        try {
            outboxMapper.insertOutboxEvent(idGeneratorUtil.nextId(), userId, templateId, claimNo);
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            log.warn("[优惠券] Outbox 写入命中已有记录(写入超时≠失败), 按成功继续: claimNo={}", claimNo);
        } catch (Exception first) {
            try {
                outboxMapper.insertOutboxEvent(idGeneratorUtil.nextId(), userId, templateId, claimNo);
            } catch (org.springframework.dao.DuplicateKeyException dup) {
                log.warn("[优惠券] Outbox 重试命中已有记录(写入超时≠失败), 按成功继续: claimNo={}", claimNo);
            } catch (Exception second) {
                log.error("[优惠券] Outbox 双次写入失败(回滚 Redis + 报错——唯一回滚路径): userId={}, templateId={}, claimNo={}",
                        userId, templateId, claimNo, second);
                return null;
            }
        }

        // ② 同步发送（任何结果均保留 Outbox——补发必达；SEND_OK 仅做标记尽力）
        try {
            String payload = objectMapper.writeValueAsString(
                    new CouponClaimEvent(userId, templateId, claimNo));
            SendResult sendResult = rocketMQTemplate.syncSend(
                    COUPON_CLAIM_TOPIC,
                    MqTraceHelper.wrapWithTraceId(MessageBuilder.withPayload(payload).build()),
                    3000);
            if (sendResult != null && sendResult.getSendStatus() == SendStatus.SEND_OK) {
                try {
                    outboxMapper.markOutboxSent(claimNo);
                } catch (Exception e) {
                    // 标记失败仅告警：行保留 → 补发重复由消费端幂等兜底（不再回滚/作废——消息已确认发出）
                    log.warn("[优惠券] markSent 失败(Outbox 将重复补发, 消费端幂等兜底): claimNo={}", claimNo, e);
                }
            } else {
                log.warn("[优惠券] MQ 发送状态非 SEND_OK(保留 Outbox 交补发——消息可能已写入 broker): userId={}, templateId={}, claimNo={}, status={}",
                        userId, templateId, claimNo, sendResult != null ? sendResult.getSendStatus() : "null");
            }
        } catch (Exception e) {
            // 发送异常（超时等）：结果未知——不回滚、不作废；Outbox 补发必达（"发送超时≠失败"）
            log.warn("[优惠券] MQ 发送异常(保留 Outbox 交补发——超时≠失败): userId={}, templateId={}, claimNo={}",
                    userId, templateId, claimNo, e);
        }
        return claimNo;
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

    // 【2026-09-23 深挖删除】原"退券 Redis 补偿链"（sendReturnCouponRedisRepairEvent /
    // repairReturnCouponRedis / RETURN_REPAIR_FALLBACK_KEY / COUPON_RETURN_REDIS_REPAIR_TOPIC
    // 及其消费者与 Job 重放）为 RV30 之前的旧语义残留：
    // 它会 INCR stock + DECR claimed，而现行语义（见 returnCoupon 注释）明确"退券只恢复用户券状态，
    // 不回补 stock/remain、不减 claimed"——一旦被触发（手工投递/陈旧消息）即造成超发敞口，
    // 且与每日对账（以已发券为准）相互打架。生产者零调用已确认，整链删除。
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

        return batchToVO(userCoupons, templateMap);
    }

    /** 批量转换重载（C-007）：调用方已持有模板 Map 时复用（getAvailableCoupons 单查复用给过滤+构建） */
    private List<UserCouponVO> batchToVO(List<UserCoupon> userCoupons, Map<Long, CouponTemplate> templateMap) {
        if (userCoupons.isEmpty()) {
            return Collections.emptyList();
        }
        // T-121：孤儿券防御——模板已删除/不存在的券行过滤，避免列表出现 name=null 脏条目
        return userCoupons.stream().map(uc -> {
            CouponTemplate template = templateMap.get(uc.getCouponId());
            if (template == null) {
                log.warn("[优惠券] 跳过孤儿券(模板不存在): userCouponId={}, couponId={}",
                        uc.getId(), uc.getCouponId());
                return null;
            }
            UserCouponVO.UserCouponVOBuilder builder = UserCouponVO.builder()
                    .id(uc.getId())
                    .couponId(uc.getCouponId())
                    .status(uc.getStatus())
                    .receivedAt(uc.getReceivedAt())
                    .name(template.getName())
                    .type(template.getType())
                    .discountValue(template.getDiscountValue())
                    .minAmount(template.getMinAmount())
                    .validStart(template.getValidStart())
                    .validEnd(template.getValidEnd());
            return builder.build();
        }).filter(java.util.Objects::nonNull).collect(Collectors.toList());
    }

    /**
     * 领券事件（MQ 消息体）
     */
    public record CouponClaimEvent(Long userId, Long templateId, String claimNo) {
    }

}

