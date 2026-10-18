package com.myxhs.product.consumer;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.myxhs.common.cache.RedisOperator;
import com.myxhs.common.constants.RedisKeyConstants;
import com.myxhs.common.trace.MqTraceHelper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 商品缓存失效消费者（C-005：binlog 驱动失效——替代延迟双删）
 * <p>
 * 数据链路：Canal `product_instance`（`my_xhs_product.t_spu, t_sku` binlog）
 * → {@code PRODUCT_INDEX_TOPIC}（既有 topic，本服务独立消费组）→ 删 SPU 缓存。
 * </p>
 * <p>
 * <b>为什么是它替代"延迟双删"</b>：binlog 是"已提交数据"的因果链——事件必然产生于提交之后、
 * MQ 重试不丢、按主键分区保序（确定性）；而延迟双删是"睡固定时长再删一次"的猜时间魔数（非确定性）。
 * 覆盖场景：并发读重建窗口 / 外部直改 DB / 删缓存失败 / 主从延迟下的脏回填（含空值缓存放大）。
 * </p>
 * <p>
 * 一致性三层（重构后）：① afterCommit 立即删（快路径）② 删失败 MQ 兜底（写入侧）
 * ③ 本消费者（最终保证）。Canal 宕机期间靠逻辑过期/物理 TTL 兜底（基础设施降级，记录）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "PRODUCT_INDEX_TOPIC",
        consumerGroup = "product-cache-canal-evict-group",
        maxReconsumeTimes = 3
)
public class ProductCacheCanalEvictConsumer implements RocketMQListener<MessageExt> {

    private final RedisOperator redisOperator;
    private final MeterRegistry meterRegistry;

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            JSONObject canalMsg = JSON.parseObject(body);
            if (canalMsg == null || !canalMsg.containsKey("table") || !canalMsg.containsKey("data")) {
                log.warn("[商品缓存-Canal] 消息格式无法识别, 跳过: msgId={}", msg.getMsgId());
                return;
            }

            String table = canalMsg.getString("table");
            JSONArray dataArray = canalMsg.getJSONArray("data");
            if (dataArray == null || dataArray.isEmpty()) {
                log.warn("[商品缓存-Canal] data 为空, 跳过: msgId={}", msg.getMsgId());
                return;
            }

            // 收集待失效的 SPU ID（同批去重：一次改多个 SKU 时父 SPU 只删一次）
            Set<Long> spuIds = new LinkedHashSet<>();
            if ("t_spu".equals(table)) {
                for (int i = 0; i < dataArray.size(); i++) {
                    Long id = dataArray.getJSONObject(i).getLong("id");
                    if (id != null) {
                        spuIds.add(id);
                    }
                }
            } else if ("t_sku".equals(table)) {
                for (int i = 0; i < dataArray.size(); i++) {
                    Long spuId = dataArray.getJSONObject(i).getLong("spu_id");
                    if (spuId != null) {
                        spuIds.add(spuId);
                    }
                }
            } else {
                log.debug("[商品缓存-Canal] 忽略非商品表变更: table={}", table);
                return;
            }

            String type = canalMsg.getString("type");
            for (Long spuId : spuIds) {
                // 删除天然幂等；INSERT/UPDATE/DELETE（含 deleted=1 逻辑删）一律失效
                redisOperator.delete(RedisKeyConstants.PRODUCT_SPU + spuId);
                countEvict("ok");
                log.info("[商品缓存-Canal] 缓存失效: spuId={}, table={}, type={}", spuId, table, type);
            }
        } catch (Exception e) {
            countEvict("fail");
            log.error("[商品缓存-Canal] 缓存失效失败(触发MQ重试): msgId={}", msg.getMsgId(), e);
            throw new IllegalStateException("商品缓存失效失败(可重试)", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }

    private void countEvict(String result) {
        Counter.builder("myxhs_product_cache_evict_total")
                .tag("source", "canal")
                .tag("result", result)
                .register(meterRegistry)
                .increment();
    }
}
