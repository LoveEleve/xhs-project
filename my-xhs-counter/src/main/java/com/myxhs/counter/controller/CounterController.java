package com.myxhs.counter.controller;

import com.myxhs.common.annotation.RateLimit;
import com.myxhs.common.response.R;
import com.myxhs.common.web.AccessTokenGuard;
import com.myxhs.counter.dto.CounterBatchRequest;
import com.myxhs.counter.service.CounterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.Map;

/**
 * 计数控制器
 * <p>
 * 计数写入统一通过 MQ 事件驱动（CounterEventConsumer），无需 HTTP 写入端点。
 * 提供查询和手动对账接口。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/counter")
@RequiredArgsConstructor
public class CounterController {

    private final CounterService counterService;
    private final AccessTokenGuard accessTokenGuard;

    /**
     * 查询单个计数（公开）
     */
    @GetMapping("/get")
    @RateLimit(windowSeconds = 1, maxRequests = 50, prefix = "myxhs:counter:get",
            message = "查询过于频繁")
    public R<Long> getCount(
            @RequestParam("targetType") Integer targetType,
            @RequestParam("targetId") Long targetId,
            @RequestParam("countType") Integer countType) {
        long count = counterService.getCount(targetType, targetId, countType);
        return R.ok(count);
    }

    /**
     * 批量查询计数（公开）
     * <p>
     * 返回格式：{"1:20001": {"like": 42, "collect": 18, "comment": 128}}
     * </p>
     */
    @PostMapping("/batch-get")
    public R<Map<String, Map<String, Long>>> batchGetCounts(@RequestBody @Valid CounterBatchRequest request) {
        Map<String, Map<String, Long>> result = counterService.batchGetCounts(request);
        return R.ok(result);
    }

    /**
     * 手动触发对账修复（管理接口，需 X-Admin-Call 校验）
     * <p>
     * T-095：限流改 perUser=true——原全局限流（所有用户共享 2 次/分钟）
     * 且 AOP 限流先于方法内 isAdminCall（未鉴权请求可消耗额度，轻微 DoS 面）。
     * perUser 后各用户独立窗口；鉴权顺序问题由 gateway 管理端点模式兜底。
     * </p>
     */
    @PostMapping("/reconcile")
    @RateLimit(windowSeconds = 60, maxRequests = 2, perUser = true, prefix = "myxhs:counter:reconcile",
            message = "对账修复请求过于频繁，每分钟最多 2 次")
    public R<Integer> reconcile(
            @RequestHeader(value = "X-Admin-Call", required = false) String adminCall) {
        if (!accessTokenGuard.isAdminCall(adminCall)) {
            return R.fail(403, "无权访问管理接口");
        }
        int fixedCount = counterService.reconcile();
        return R.ok(fixedCount);
    }
}
