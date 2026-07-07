package com.myxhs.counter.provider;

import com.myxhs.counter.dto.CounterBatchRequest;
import com.myxhs.counter.dubbo.CounterDubboService;
import com.myxhs.counter.service.CounterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 计数 Dubbo Provider 实现（Triple 协议）
 * <p>
 * 替代 Feign 调用，提供高性能的计数 RPC 接口。
 * 主要用于首页聚合服务 Feed 流中每个笔记的点赞/收藏/评论数查询。
 * </p>
 */
@Slf4j
@Component
@DubboService
@RequiredArgsConstructor
public class CounterDubboServiceImpl implements CounterDubboService {

    private final CounterService counterService;

    @Override
    public void increment(int targetType, long targetId, int countType) {
        counterService.increment(targetType, targetId, countType);
    }

    @Override
    public boolean decrement(int targetType, long targetId, int countType) {
        return counterService.decrement(targetType, targetId, countType);
    }

    @Override
    public long getCount(int targetType, long targetId, int countType) {
        return counterService.getCount(targetType, targetId, countType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Long>> batchGetCounts(Map<String, Object> request) {
        CounterBatchRequest batchRequest = new CounterBatchRequest();
        List<CounterBatchRequest.QueryItem> items = ((List<Map<String, Object>>) request.get("items")).stream()
                .map(m -> {
                    CounterBatchRequest.QueryItem item = new CounterBatchRequest.QueryItem();
                    item.setTargetType((Integer) m.get("targetType"));
                    item.setTargetId(((Number) m.get("targetId")).longValue());
                    item.setCountTypes((List<Integer>) m.get("countTypes"));
                    return item;
                }).toList();
        batchRequest.setQueries(items);
        return counterService.batchGetCounts(batchRequest);
    }
}
