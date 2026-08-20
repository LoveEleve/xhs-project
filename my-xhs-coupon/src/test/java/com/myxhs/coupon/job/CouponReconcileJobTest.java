package com.myxhs.coupon.job;

import com.myxhs.coupon.entity.CouponTemplate;
import com.myxhs.coupon.mapper.CouponTemplateMapper;
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
    @Mock private ValueOperations<String, String> valueOperations;

    private CouponReconcileJob job;

    @BeforeEach
    void setUp() {
        job = new CouponReconcileJob(stringRedisTemplate, templateMapper);
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

        verify(templateMapper).updateById(template);
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
