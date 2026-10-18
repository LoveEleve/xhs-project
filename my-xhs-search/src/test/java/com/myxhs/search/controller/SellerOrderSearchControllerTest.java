package com.myxhs.search.controller;

import com.myxhs.common.web.AccessTokenGuard;
import com.myxhs.search.dto.SellerOrderPageVO;
import com.myxhs.search.service.SellerOrderSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商家订单检索接口单测（C-004，standalone MockMvc）
 * <p>
 * 守卫验证：非 Admin-Call → 403；sellerId 必填 → 40002；成功 → 参数透传 service。
 * </p>
 */
class SellerOrderSearchControllerTest {

    private SellerOrderSearchService sellerOrderSearchService;
    private AccessTokenGuard accessTokenGuard;
    private com.myxhs.search.job.SellerSubOrderIndexBackfillJob sellerSubOrderIndexBackfillJob;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        sellerOrderSearchService = mock(SellerOrderSearchService.class);
        accessTokenGuard = mock(AccessTokenGuard.class);
        sellerSubOrderIndexBackfillJob = mock(com.myxhs.search.job.SellerSubOrderIndexBackfillJob.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SellerOrderSearchController(sellerOrderSearchService, accessTokenGuard,
                        sellerSubOrderIndexBackfillJob))
                .build();
    }

    @Test
    @DisplayName("非 X-Admin-Call → 403（不触达 service）")
    void nonAdminCallRejected() throws Exception {
        when(accessTokenGuard.isAdminCall(any())).thenReturn(false);

        mockMvc.perform(get("/api/search/seller-order/list").param("sellerId", "3001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));

        verify(sellerOrderSearchService, never()).search(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("sellerId 缺失 → 40002（防全量泄漏）")
    void missingSellerIdRejected() throws Exception {
        when(accessTokenGuard.isAdminCall(any())).thenReturn(true);

        mockMvc.perform(get("/api/search/seller-order/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40002));

        verify(sellerOrderSearchService, never()).search(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("sellerId 为负 → 40002")
    void negativeSellerIdRejected() throws Exception {
        when(accessTokenGuard.isAdminCall(any())).thenReturn(true);

        mockMvc.perform(get("/api/search/seller-order/list").param("sellerId", "-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40002));

        verify(sellerOrderSearchService, never()).search(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("成功：参数透传（sellerId/status/时间/分页）+ 返回分页结果")
    void listPassesParamsAndReturnsPage() throws Exception {
        when(accessTokenGuard.isAdminCall(any())).thenReturn(true);
        SellerOrderPageVO pageVO = new SellerOrderPageVO();
        pageVO.setTotal(1);
        pageVO.setPage(2);
        pageVO.setSize(10);
        pageVO.setRecords(List.of());
        when(sellerOrderSearchService.search(eq(3001L), eq(2), eq("2026-10-01 00:00:00"),
                eq("2026-10-17 23:59:59"), eq(2), eq(10))).thenReturn(pageVO);

        mockMvc.perform(get("/api/search/seller-order/list")
                        .param("sellerId", "3001")
                        .param("status", "2")
                        .param("startTime", "2026-10-01 00:00:00")
                        .param("endTime", "2026-10-17 23:59:59")
                        .param("page", "2")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(2));

        verify(sellerOrderSearchService).search(eq(3001L), eq(2), eq("2026-10-01 00:00:00"),
                eq("2026-10-17 23:59:59"), eq(2), eq(10));
    }

    @Test
    @DisplayName("sellerId=0（平台自营）→ 合法放行")
    void sellerIdZeroAllowed() throws Exception {
        when(accessTokenGuard.isAdminCall(any())).thenReturn(true);
        when(sellerOrderSearchService.search(eq(0L), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(new SellerOrderPageVO());

        mockMvc.perform(get("/api/search/seller-order/list").param("sellerId", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(sellerOrderSearchService).search(eq(0L), isNull(), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("回填触发：非 Admin-Call → 403（不触达 Job）")
    void backfillRejectedWithoutAdmin() throws Exception {
        when(accessTokenGuard.isAdminCall(any())).thenReturn(false);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/search/seller-order/backfill"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));

        verify(sellerSubOrderIndexBackfillJob, never()).manualBackfill();
    }

    @Test
    @DisplayName("回填触发：Admin-Call → 触发异步回填")
    void backfillTriggeredByAdmin() throws Exception {
        when(accessTokenGuard.isAdminCall(any())).thenReturn(true);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/search/seller-order/backfill"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(sellerSubOrderIndexBackfillJob).manualBackfill();
    }
}
