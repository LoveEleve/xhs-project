package com.myxhs.coupon.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.id.IdGeneratorUtil;
import com.myxhs.common.metrics.BusinessMetrics;
import com.myxhs.coupon.entity.CouponTemplate;
import com.myxhs.coupon.entity.UserCoupon;
import com.myxhs.coupon.mapper.CouponPushRecordMapper;
import com.myxhs.coupon.mapper.CouponTemplateMapper;
import com.myxhs.coupon.mapper.UserCouponMapper;
import com.myxhs.coupon.push.CouponPushSender;
import com.myxhs.coupon.push.CouponPushTarget;
import com.myxhs.coupon.push.SharedThreadPool;
import com.myxhs.coupon.push.UserBucketSplitter;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

/**
 * 优惠券到期推送任务（C-017 P3）
 * <p>
 * T 日扫"明天到期"的未使用券 → 用户分桶 → 批分割 → 共享线程池 → NOTIFICATION_TOPIC。
 * 幂等：t_coupon_push_record uk(template_id,user_id,push_date)；已发送跳过，失败次日/重跑重试。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponExpiryPushJob {

    private final CouponTemplateMapper templateMapper;
    private final UserCouponMapper userCouponMapper;
    private final CouponPushRecordMapper pushRecordMapper;
    private final CouponPushSender sender;
    private final SharedThreadPool sharedThreadPool;
    private final IdGeneratorUtil idGeneratorUtil;
    private final ObjectMapper objectMapper;
    private final BusinessMetrics businessMetrics;

    private static final int SCAN_LIMIT = 5000;
    private static final int BATCH_SIZE = 200;
    private static final int MAX_BYTES = 800 * 1024;

    @XxlJob("couponExpiryPushJob")
    public void push() {
        try {
            Result r = doPush();
            XxlJobHelper.handleSuccess("券到期推送: 目标=" + r.targets + ", 批次=" + r.batches
                    + ", 成功=" + r.sent + ", 失败=" + r.failed + ", 跳过=" + r.skipped);
        } catch (Exception e) {
            log.error("[券推送] 任务异常", e);
            XxlJobHelper.handleFail("券推送异常: " + e.getMessage());
        }
    }

    private Result doPush() throws Exception {
        LocalDate pushDate = LocalDate.now().plusDays(1);
        LocalDateTime from = pushDate.atStartOfDay();
        LocalDateTime to = pushDate.plusDays(1).atStartOfDay();

        List<CouponTemplate> templates = templateMapper.selectList(
                new LambdaQueryWrapper<CouponTemplate>()
                        .eq(CouponTemplate::getDeleted, 0)
                        .ge(CouponTemplate::getValidEnd, from)
                        .lt(CouponTemplate::getValidEnd, to));

        List<CouponPushTarget> targets = new ArrayList<>();
        int skipped = 0;
        for (CouponTemplate template : templates) {
            List<UserCoupon> coupons = userCouponMapper.selectUnusedByCoupon(template.getId(), SCAN_LIMIT);
            for (UserCoupon coupon : coupons) {
                int inserted = pushRecordMapper.insertIgnore(
                        idGeneratorUtil.nextId(), template.getId(), coupon.getUserId(), pushDate);
                if (inserted == 0) {
                    Integer status = pushRecordMapper.selectStatus(
                            template.getId(), coupon.getUserId(), pushDate);
                    if (status != null && status == 1) {
                        skipped++;
                        continue; // 已推送
                    }
                }
                targets.add(new CouponPushTarget(template.getId(), template.getName(),
                        coupon.getUserId(), template.getValidEnd()));
            }
        }
        if (skipped > 0) {
            businessMetrics.recordCouponAction("push_skip", true);
        }

        List<List<CouponPushTarget>> batches = UserBucketSplitter.split(
                targets, BATCH_SIZE, MAX_BYTES, this::serialize);
        AtomicInteger sent = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (List<CouponPushTarget> batch : batches) {
            futures.add(sharedThreadPool.submit(() -> {
                for (CouponPushTarget target : batch) {
                    if (sender.send(target)) {
                        pushRecordMapper.markSent(target.templateId(), target.userId(), pushDate);
                        sent.incrementAndGet();
                    } else {
                        pushRecordMapper.markFailed(target.templateId(), target.userId(), pushDate, "mq send failed");
                        failed.incrementAndGet();
                    }
                }
            }));
        }
        for (Future<?> future : futures) {
            try {
                future.get(60, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.error("[券推送] 批次执行异常", e);
                failed.incrementAndGet();
            }
        }
        log.info("[券推送] 完成: date={}, templates={}, targets={}, batches={}, sent={}, failed={}, skipped={}",
                pushDate, templates.size(), targets.size(), batches.size(), sent.get(), failed.get(), skipped);
        return new Result(targets.size(), batches.size(), sent.get(), failed.get(), skipped);
    }

    private String serialize(CouponPushTarget target) {
        try {
            return objectMapper.writeValueAsString(target);
        } catch (Exception e) {
            return target.toString();
        }
    }

    private record Result(int targets, int batches, int sent, int failed, int skipped) {
    }
}
