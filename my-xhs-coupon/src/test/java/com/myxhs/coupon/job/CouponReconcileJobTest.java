package com.myxhs.coupon.job;

import com.myxhs.common.metrics.BusinessMetrics;
import com.myxhs.coupon.entity.CouponTemplate;
import com.myxhs.coupon.mapper.CouponOutboxMapper;
import com.myxhs.coupon.mapper.CouponTemplateMapper;
import com.myxhs.coupon.mapper.UserCouponMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CouponReconcileJobTest {

    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private CouponTemplateMapper templateMapper;
    @Mock private UserCouponMapper userCouponMapper;
    @Mock private CouponOutboxMapper outboxMapper;
    @Mock private BusinessMetrics businessMetrics;
    @Mock private ValueOperations<String, String> valueOperations;

    private CouponReconcileJob job;

    @BeforeEach
    void setUp() {
        // 2026-09-21：构造新增 3 个依赖（限领计数对账 + 卡住 Outbox 检查 + 指标）
        job = new CouponReconcileJob(stringRedisTemplate, templateMapper, userCouponMapper, outboxMapper, businessMetrics);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void shouldReconcileDisabledTemplate() {
        CouponTemplate template = new CouponTemplate();
        template.setId(1L);
        template.setName("disabled");
        template.setStatus(0);
        template.setRemainCount(10);
        template.setDeleted(0);
        template.setValidEnd(LocalDateTime.now().minusDays(1));
        when(templateMapper.selectList(any())).thenReturn(List.of(template));
        when(valueOperations.get("myxhs:coupon:{1}:stock")).thenReturn("7");

        job.reconcile();

        // 2026-09-21 语义变更：对账改为"定向更新 remain_count"（原生 SQL，不依赖 MP lambda 缓存；
        // updateById 全字段写会覆盖并发扣减）→ 断言修正为 定向更新 + 值来自 Redis(7)
        verify(templateMapper).updateRemainCountOnly(1L, 7);
    }

    @Test
    void shouldBackfillExpiredTemplateRedisStock() {
        CouponTemplate template = new CouponTemplate();
        template.setId(2L);
        template.setName("expired");
        template.setStatus(0);
        template.setRemainCount(6);
        template.setDeleted(0);
        template.setValidEnd(LocalDateTime.now().minusDays(30));
        when(templateMapper.selectList(any())).thenReturn(List.of(template));
        when(valueOperations.get("myxhs:coupon:{2}:stock")).thenReturn(null);

        job.reconcile();

        verify(valueOperations).set("myxhs:coupon:{2}:stock", "6");
    }
}
