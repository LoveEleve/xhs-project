package com.myxhs.coupon.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.common.id.IdGeneratorUtil;
import com.myxhs.common.metrics.BusinessMetrics;
import com.myxhs.coupon.entity.CouponTemplate;
import com.myxhs.coupon.entity.UserCoupon;
import com.myxhs.coupon.mapper.CouponPushRecordMapper;
import com.myxhs.coupon.mapper.CouponTemplateMapper;
import com.myxhs.coupon.mapper.UserCouponMapper;
import com.myxhs.coupon.push.CouponPushSender;
import com.myxhs.coupon.push.SharedThreadPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class CouponExpiryPushJobTest {

    @Mock private CouponTemplateMapper templateMapper;
    @Mock private UserCouponMapper userCouponMapper;
    @Mock private CouponPushRecordMapper pushRecordMapper;
    @Mock private CouponPushSender sender;
    @Mock private SharedThreadPool sharedThreadPool;
    @Mock private IdGeneratorUtil idGeneratorUtil;
    @Mock private BusinessMetrics businessMetrics;

    private CouponExpiryPushJob job;

    @BeforeEach
    void setUp() {
        job = new CouponExpiryPushJob(templateMapper, userCouponMapper, pushRecordMapper,
                sender, sharedThreadPool, idGeneratorUtil, new ObjectMapper(), businessMetrics);
        // 批任务内联执行，避免测试异步等待
        lenient().when(sharedThreadPool.submit(any(Runnable.class))).thenAnswer(inv -> {
            inv.getArgument(0, Runnable.class).run();
            return CompletableFuture.completedFuture(null);
        });
    }

    private CouponTemplate template() {
        CouponTemplate t = new CouponTemplate();
        t.setId(1L);
        t.setName("满100减10");
        t.setDeleted(0);
        t.setValidEnd(LocalDate.now().plusDays(1).atTime(12, 0));
        return t;
    }

    private UserCoupon coupon(long userId) {
        UserCoupon c = new UserCoupon();
        c.setUserId(userId);
        c.setCouponId(1L);
        c.setStatus(0);
        return c;
    }

    @Test
    @DisplayName("推送：两个用户 → 两次发送 + 标记已发送")
    void pushTwoUsers() {
        when(templateMapper.selectList(any())).thenReturn(List.of(template()));
        when(userCouponMapper.selectUnusedByCoupon(1L, 5000)).thenReturn(List.of(coupon(1001L), coupon(1002L)));
        when(pushRecordMapper.insertIgnore(anyLong(), eq(1L), anyLong(), any())).thenReturn(1);
        when(idGeneratorUtil.nextId()).thenReturn(1L, 2L);
        when(sender.send(any())).thenReturn(true);

        job.push();

        verify(sender, times(2)).send(any());
        verify(pushRecordMapper, times(2)).markSent(eq(1L), anyLong(), any());
    }

    @Test
    @DisplayName("幂等：已发送（status=1）→ 跳过")
    void skipAlreadySent() {
        when(templateMapper.selectList(any())).thenReturn(List.of(template()));
        when(userCouponMapper.selectUnusedByCoupon(1L, 5000)).thenReturn(List.of(coupon(1001L)));
        when(pushRecordMapper.insertIgnore(anyLong(), eq(1L), anyLong(), any())).thenReturn(0);
        when(pushRecordMapper.selectStatus(eq(1L), eq(1001L), any())).thenReturn(1);

        job.push();

        verify(sender, never()).send(any());
        verify(pushRecordMapper, never()).markSent(anyLong(), anyLong(), any());
    }
}
