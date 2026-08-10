package com.myxhs.order.feign;

import com.myxhs.common.response.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 库存服务降级工厂
 * <p>
 * 库存释放失败时记录日志，由定时任务补偿重试。
 * </p>
 */
@Slf4j
@Component
public class InventoryFeignFallbackFactory implements FallbackFactory<InventoryFeignClient> {
    @Override
    public InventoryFeignClient create(Throwable cause) {
        log.error("[降级] InventoryFeignClient 不可用，库存操作失败需补偿: {}", cause.getMessage());
        return new InventoryFeignClient() {
            @Override
            public R<Map<String, Object>> queryStock(Long skuId) {
                return R.fail(503, "库存服务不可用");
            }

            @Override
            public R<Void> releaseStock(Map<String, Object> request) {
                return R.fail(503, "库存服务不可用");
            }

            @Override
            public R<Void> confirmDeduct(Map<String, Object> request) {
                return R.fail(503, "库存服务不可用");
            }
        };
    }
}
