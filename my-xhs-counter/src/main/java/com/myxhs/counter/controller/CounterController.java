package com.myxhs.counter.controller;

import com.myxhs.common.response.R;
import com.myxhs.counter.dto.CounterBatchRequest;
import com.myxhs.counter.dto.CounterRequest;
import com.myxhs.counter.service.CounterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 计数控制器
 * <p>
 * 提供计数增减和查询接口。
 * 增减接口为内部调用（其他服务通过 MQ 或 Feign 调用），查询接口公开。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/counter")
@RequiredArgsConstructor
public class CounterController {

    private final CounterService counterService;

    /**
     * 计数 +1（内部调用）
     */
    @PostMapping("/increment")
    public R<Void> increment(@RequestBody CounterRequest request) {
        counterService.increment(request.getTargetType(), request.getTargetId(), request.getCountType());
        return R.ok();
    }

    /**
     * 计数 -1（内部调用）
     */
    @PostMapping("/decrement")
    public R<Void> decrement(@RequestBody CounterRequest request) {
        boolean success = counterService.decrement(request.getTargetType(), request.getTargetId(), request.getCountType());
        if (!success) {
            return R.fail("计数已为 0，无法继续减少");
        }
        return R.ok();
    }

    /**
     * 查询单个计数（公开）
     */
    @GetMapping("/get")
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
    public R<Map<String, Map<String, Long>>> batchGetCounts(@RequestBody CounterBatchRequest request) {
        Map<String, Map<String, Long>> result = counterService.batchGetCounts(request);
        return R.ok(result);
    }

    /**
     * 手动触发对账修复（管理接口）
     */
    @PostMapping("/reconcile")
    public R<Integer> reconcile() {
        int fixedCount = counterService.reconcile();
        return R.ok(fixedCount);
    }
}
