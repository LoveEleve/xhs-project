package com.myxhs.search.consumer;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.myxhs.common.trace.MqTraceHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 商家维度子单索引同步消费者（C-004）
 * <p>
 * 数据链路：Canal order_instance（my_xhs_order_0..3 的 t_sub_order_0..3 binlog）
 * → {@code SUB_ORDER_INDEX_TOPIC} → 本消费者 → ES {@code seller_sub_order_index}。
 * </p>
 * <p>
 * <b>正确性守卫（prd 决策 5）</b>：ES 只做发现（商家列表检索）；
 * 一切写操作/终态判断以 DB + CAS 为准——本索引的最终一致延迟不得导致错误操作。
 * </p>
 * <p>
 * 可靠性：doc id = subOrderNo + ExternalGte 版本（同 id 重放幂等、乱序旧事件不覆盖）；
 * ES 异常 → 失败单号落 Redis（供补偿） + 上抛触发 RocketMQ 重试（maxReconsumeTimes=3）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "SUB_ORDER_INDEX_TOPIC",
        consumerGroup = "seller-sub-order-index-sync-group",
        maxReconsumeTimes = 3
)
public class SellerSubOrderIndexSyncConsumer implements RocketMQListener<MessageExt> {

    /** 失败补偿键（供回填/补偿任务重放；与既有 myxhs:es:sync:failed:* 同族） */
    static final String FAILED_KEY = "myxhs:es:sync:failed:seller-sub-order";

    private final SellerSubOrderIndexWriter indexWriter;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        String currentSubOrderNo = null;
        JSONArray dataArray = null;
        int currentIndex = -1;
        try {
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            JSONObject canalMsg = JSON.parseObject(body);
            if (canalMsg == null || !canalMsg.containsKey("table") || !canalMsg.containsKey("data")) {
                log.warn("[卖家子单索引] 消息格式无法识别，跳过: msgId={}", msg.getMsgId());
                return;
            }

            String table = canalMsg.getString("table");
            if (table == null || !table.startsWith("t_sub_order")) {
                log.debug("[卖家子单索引] 忽略非子单表变更: table={}", table);
                return;
            }

            dataArray = canalMsg.getJSONArray("data");
            if (dataArray == null || dataArray.isEmpty()) {
                log.warn("[卖家子单索引] data 为空，跳过: msgId={}", msg.getMsgId());
                return;
            }

            String type = canalMsg.getString("type");
            long version = canalMsg.getLongValue("ts", System.currentTimeMillis());
            if (version == 0) {
                version = canalMsg.getLongValue("es", System.currentTimeMillis());
            }

            for (int i = 0; i < dataArray.size(); i++) {
                currentIndex = i;
                JSONObject row = dataArray.getJSONObject(i);
                String subOrderNo = row.getString("sub_order_no");
                if (subOrderNo == null || subOrderNo.isBlank()) {
                    log.warn("[卖家子单索引] 行数据缺少 sub_order_no，跳过: msgId={}", msg.getMsgId());
                    continue;
                }
                currentSubOrderNo = subOrderNo;

                if ("DELETE".equals(type) || row.getIntValue("deleted", 0) == 1) {
                    // 逻辑删除（UPDATE deleted=1）与物理 DELETE 统一走软删除标记
                    indexWriter.softDelete(subOrderNo, version);
                } else if ("INSERT".equals(type) || "UPDATE".equals(type)) {
                    indexWriter.upsert(subOrderNo, buildDoc(row), version);
                } else {
                    log.debug("[卖家子单索引] 未知事件类型，跳过: type={}, subOrderNo={}", type, subOrderNo);
                }
            }
        } catch (Exception e) {
            // 记录失败单号 → Redis 补偿键（与商品索引同模式）；上抛触发重试
            recordFailedBatch(dataArray, currentIndex, currentSubOrderNo);
            log.error("[卖家子单索引] 同步失败（触发重试）: subOrderNo={}, msgId={}, reconsumeTimes={}",
                    currentSubOrderNo, msg.getMsgId(), msg.getReconsumeTimes(), e);
            throw new IllegalStateException("卖家子单索引同步失败（可重试）", e);
        }
    }

    /**
     * 失败补偿：记录"当前失败行 + 批次内未处理行"的单号——
     * MQ 重试会整批重放（正常路径）；此处覆盖"重试仍失败进 DLQ"时后续行不丢失（兜底）。
     */
    private void recordFailedBatch(JSONArray dataArray, int currentIndex, String fallbackSubOrderNo) {
        java.util.Set<String> failed = new java.util.LinkedHashSet<>();
        if (dataArray != null && currentIndex >= 0) {
            for (int i = currentIndex; i < dataArray.size(); i++) {
                try {
                    JSONObject row = dataArray.getJSONObject(i);
                    String subOrderNo = row == null ? null : row.getString("sub_order_no");
                    if (subOrderNo != null && !subOrderNo.isBlank()) {
                        failed.add(subOrderNo);
                    }
                } catch (Exception ignored) {
                    // 行解析失败：跳过（无法提取单号）
                }
            }
        }
        if (failed.isEmpty() && fallbackSubOrderNo != null) {
            failed.add(fallbackSubOrderNo);
        }
        if (failed.isEmpty()) {
            return;
        }
        try {
            for (String subOrderNo : failed) {
                stringRedisTemplate.opsForSet().add(FAILED_KEY, subOrderNo);
            }
            stringRedisTemplate.expire(FAILED_KEY, Duration.ofHours(1));
        } catch (Exception redisEx) {
            log.warn("[卖家子单索引] 记录失败单号到 Redis 失败: subOrderNos={}", failed, redisEx);
        }
    }

    /** canal 行数据（列名）→ ES 文档（字段名） */
    private Map<String, Object> buildDoc(JSONObject row) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("subOrderNo", row.getString("sub_order_no"));
        doc.put("orderNo", row.getString("order_no"));
        doc.put("orderId", row.getLong("order_id"));
        doc.put("userId", row.getLong("user_id"));
        doc.put("sellerId", row.getLong("seller_id"));
        doc.put("status", row.getIntValue("status"));
        doc.put("totalAmount", row.getBigDecimal("total_amount"));
        doc.put("discountAmount", row.getBigDecimal("discount_amount"));
        doc.put("payAmount", row.getBigDecimal("pay_amount"));
        doc.put("createdAt", row.getString("created_at"));
        doc.put("updatedAt", row.getString("updated_at"));
        doc.put("deleted", false);
        return doc;
    }
}
