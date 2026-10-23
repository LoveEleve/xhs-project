package com.myxhs.coupon.job;

import com.myxhs.coupon.entity.CouponTemplate;
import com.myxhs.coupon.mapper.CouponTemplateMapper;
import com.myxhs.coupon.mq.CouponExpireDelayProducer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CouponExpirePrecheckJobTest {

    @Test
    @DisplayName("预检：未来 24h 到期模板补排延时消息")
    void precheckSchedules() {
        CouponTemplateMapper templateMapper = mock(CouponTemplateMapper.class);
        CouponExpireDelayProducer producer = mock(CouponExpireDelayProducer.class);
        CouponExpirePrecheckJob job = new CouponExpirePrecheckJob(templateMapper, producer);

        CouponTemplate template = new CouponTemplate();
        template.setId(1L);
        template.setValidEnd(LocalDateTime.now().plusHours(12));
        when(templateMapper.selectList(any())).thenReturn(List.of(template));

        job.precheck();

        verify(producer).schedule(1L, template.getValidEnd());
    }
}
