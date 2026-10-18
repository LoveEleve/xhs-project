package com.myxhs.product.consumer;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.myxhs.common.cache.RedisOperator;
import com.myxhs.common.constants.RedisKeyConstants;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 商品缓存失效消费者单测（C-005：Canal 失效替代延迟双删）
 * <p>
 * 覆盖：t_spu 失效 / t_sku 失效父 SPU / 批量去重 / DELETE 与 deleted=1 / 非目标表与非法消息跳过 / 失败抛错重试。
 * </p>
 */
class ProductCacheCanalEvictConsumerTest {

    private RedisOperator redisOperator;
    private ProductCacheCanalEvictConsumer consumer;

    @BeforeEach
    void setUp() {
        redisOperator = mock(RedisOperator.class);
        consumer = new ProductCacheCanalEvictConsumer(redisOperator, new SimpleMeterRegistry());
    }

    private MessageExt message(String json) {
        MessageExt msg = new MessageExt();
        msg.setBody(json.getBytes(StandardCharsets.UTF_8));
        return msg;
    }

    @SafeVarargs
    private String canalMessage(String table, String type, Map<String, Object>... rows) {
        JSONObject msg = new JSONObject();
        msg.put("database", "my_xhs_product");
        msg.put("table", table);
        msg.put("type", type);
        JSONArray data = new JSONArray();
        for (Map<String, Object> row : rows) {
            data.add(row);
        }
        msg.put("data", data);
        msg.put("ts", 1715510539000L);
        return msg.toJSONString();
    }

    private Map<String, Object> spuRow(long id) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", String.valueOf(id));
        row.put("status", "1");
        return row;
    }

    private Map<String, Object> skuRow(long id, long spuId) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", String.valueOf(id));
        row.put("spu_id", String.valueOf(spuId));
        return row;
    }

    @Test
    @DisplayName("t_spu 变更 → 删对应 SPU 缓存")
    void spuChangeEvicts() {
        consumer.onMessage(message(canalMessage("t_spu", "UPDATE", spuRow(10001L))));

        verify(redisOperator).delete(RedisKeyConstants.PRODUCT_SPU + 10001L);
    }

    @Test
    @DisplayName("t_sku 变更 → 删父 SPU 缓存（同批多 SKU 去重只删一次）")
    void skuChangeEvictsParentDeduplicated() {
        consumer.onMessage(message(canalMessage("t_sku", "UPDATE",
                skuRow(20001L, 10001L), skuRow(20002L, 10001L), skuRow(20003L, 10002L))));

        verify(redisOperator, times(1)).delete(RedisKeyConstants.PRODUCT_SPU + 10001L);
        verify(redisOperator, times(1)).delete(RedisKeyConstants.PRODUCT_SPU + 10002L);
    }

    @Test
    @DisplayName("DELETE 事件与 deleted=1 → 同样失效（删除天然幂等）")
    void deleteEventsEvict() {
        Map<String, Object> deleted = spuRow(10001L);
        deleted.put("deleted", "1");
        consumer.onMessage(message(canalMessage("t_spu", "UPDATE", deleted)));
        consumer.onMessage(message(canalMessage("t_spu", "DELETE", spuRow(10002L))));

        verify(redisOperator).delete(RedisKeyConstants.PRODUCT_SPU + 10001L);
        verify(redisOperator).delete(RedisKeyConstants.PRODUCT_SPU + 10002L);
    }

    @Test
    @DisplayName("非目标表/非法消息 → 跳过不删")
    void invalidMessagesSkipped() {
        consumer.onMessage(message("{}"));
        consumer.onMessage(message("{\"table\":\"t_spu\"}"));
        consumer.onMessage(message(canalMessage("t_category", "UPDATE", spuRow(1L))));

        verify(redisOperator, never()).delete(anyString());
    }

    @Test
    @DisplayName("Redis 删除失败 → 抛错触发 MQ 重试")
    void redisFailureRetries() {
        doThrow(new RuntimeException("redis down"))
                .when(redisOperator).delete(RedisKeyConstants.PRODUCT_SPU + 10001L);

        assertThatThrownBy(() -> consumer.onMessage(
                message(canalMessage("t_spu", "UPDATE", spuRow(10001L)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缓存失效失败");
    }

    @Test
    @DisplayName("行缺少 spu_id（行镜像异常）→ 告警跳过，不删不炸")
    void missingSpuIdWarnsAndSkips() {
        Map<String, Object> incomplete = new HashMap<>();
        incomplete.put("id", "20001");
        consumer.onMessage(message(canalMessage("t_sku", "DELETE", incomplete)));

        verify(redisOperator, never()).delete(anyString());
    }
}
