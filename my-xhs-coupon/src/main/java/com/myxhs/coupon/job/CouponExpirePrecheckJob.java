package com.myxhs.coupon.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.myxhs.coupon.entity.CouponTemplate;
import com.myxhs.coupon.mapper.CouponTemplateMapper;
import com.myxhs.coupon.mq.CouponExpireDelayProducer;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 过期预检任务（C-017 P2 预检层）
 * <p>
 * 每日扫描"未来 24h 内到期"的启用模板，补排/续排过期延时消息。
 * 作用：覆盖"模板创建时消息丢失/模板长期存在"的场景；与 Job 兜底、延时消费形成三层。
 * 幂等：重复排期无害（消费端条件更新 + 未到期续排）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponExpirePrecheckJob {

    private final CouponTemplateMapper templateMapper;
    private final CouponExpireDelayProducer expireDelayProducer;

    @XxlJob("couponExpirePrecheckJob")
    public void precheck() {
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime horizon = now.plusDays(1);
            List<CouponTemplate> templates = templateMapper.selectList(
                    new LambdaQueryWrapper<CouponTemplate>()
                            .eq(CouponTemplate::getDeleted, 0)
                            .eq(CouponTemplate::getStatus, 1)
                            .gt(CouponTemplate::getValidEnd, now)
                            .le(CouponTemplate::getValidEnd, horizon));
            for (CouponTemplate template : templates) {
                expireDelayProducer.schedule(template.getId(), template.getValidEnd());
            }
            log.info("[券过期预检] 完成: 待到期模板={}, 已补排延时消息", templates.size());
            XxlJobHelper.handleSuccess("券过期预检: " + templates.size());
        } catch (Exception e) {
            log.error("[券过期预检] 异常", e);
            XxlJobHelper.handleFail("券过期预检异常: " + e.getMessage());
        }
    }
}
