package com.myxhs.search.controller;

import com.myxhs.common.annotation.RateLimit;
import com.myxhs.common.response.R;
import com.myxhs.common.response.ResultCode;
import com.myxhs.common.web.AccessTokenGuard;
import com.myxhs.search.dto.SellerOrderPageVO;
import com.myxhs.search.service.SellerOrderSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家维度订单检索接口（C-004）
 * <p>
 * 商家视角 = 子单（履约/售后锚点）。数据来自 ES 异构索引（Canal→MQ→ES）——
 * 解决"订单按买家（user_id）分片、商家维度无法路由"的分库分表经典问题。
 * </p>
 * <p>
 * <b>守卫</b>：
 * <ul>
 *   <li>X-Admin-Call：管理角色（现状：商家角色=管理令牌；真实商家身份体系为演进）</li>
 *   <li><b>sellerId 必填</b>：不允许"查所有商家"（防全量泄漏）</li>
 *   <li><b>ES 只做发现</b>：列表延迟不影响写操作/终态（发货等以 DB+CAS 为准）</li>
 * </ul>
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/search/seller-order")
@RequiredArgsConstructor
public class SellerOrderSearchController {

    private final SellerOrderSearchService sellerOrderSearchService;
    private final AccessTokenGuard accessTokenGuard;
    private final com.myxhs.search.job.SellerSubOrderIndexBackfillJob sellerSubOrderIndexBackfillJob;

    /**
     * 商家子单列表（筛选/排序/分页）。
     *
     * @param sellerId  商家 ID（必填；0=平台自营）
     * @param status    子单状态（可选）
     * @param startTime 下单时间起（可选）
     * @param endTime   下单时间止（可选）
     * @param page      页码（默认 1）
     * @param size      每页大小（默认 20，上限 100）
     */
    @GetMapping("/list")
    @RateLimit(windowSeconds = 1, maxRequests = 10, perUser = true, prefix = "myxhs:search:seller-order:list")
    public R<SellerOrderPageVO> list(
            @RequestParam(value = "sellerId", required = false) Long sellerId,
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "startTime", required = false) String startTime,
            @RequestParam(value = "endTime", required = false) String endTime,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestHeader(value = "X-Admin-Call", required = false) String adminCall) {
        if (!accessTokenGuard.isAdminCall(adminCall)) {
            return R.fail(403, "无权访问管理接口");
        }
        // 守卫：sellerId 必填（防全量泄漏——不允许"查所有商家"）
        if (sellerId == null || sellerId < 0) {
            return R.fail(ResultCode.PARAM_INVALID, "sellerId 必填");
        }
        return R.ok(sellerOrderSearchService.search(sellerId, status, startTime, endTime, page, size));
    }

    /**
     * 手动触发存量回填（管理员；canal 只捕增量——存量必须回填，部署顺序：先回填后切流量）。
     */
    @PostMapping("/backfill")
    @RateLimit(windowSeconds = 60, maxRequests = 1, perUser = true, prefix = "myxhs:search:seller-order:backfill")
    public R<String> triggerBackfill(@RequestHeader(value = "X-Admin-Call", required = false) String adminCall) {
        if (!accessTokenGuard.isAdminCall(adminCall)) {
            return R.fail(403, "无权访问管理接口");
        }
        sellerSubOrderIndexBackfillJob.manualBackfill();
        return R.ok("存量回填任务已触发（异步执行，可重入）");
    }
}
