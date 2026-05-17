package com.myxhs.search.consumer;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.myxhs.common.trace.MqTraceHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 商品索引同步消费者
 * <p>
 * 消费 Canal 发送的商品变更消息，同步到 ES product_index。
 * 消息来源：Canal 监听 MySQL my_xhs_product.t_spu/t_sku 表的 Binlog → RocketMQ PRODUCT_INDEX_TOPIC
 * </p>
 * <p>
 * Canal 原始消息格式：
 * {
 *   "database": "my_xhs_product",
 *   "table": "t_spu",
 *   "type": "INSERT|UPDATE|DELETE",
 *   "data": [{"id": "1", "name": "商品名", ...}],
 *   "ts": 1715510539000
 * }
 * </p>
 * <p>
 * 注意：t_sku 表变更也会触发消息，但当前只索引 SPU 维度。
 * SKU 变更时需要关联查询 SPU 信息（TODO：后续优化为宽表同步）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "PRODUCT_INDEX_TOPIC",
        consumerGroup = "product-index-sync-consumer-group",
        maxReconsumeTimes = 3
)
public class ProductIndexSyncConsumer implements RocketMQListener<MessageExt> {

    private final ElasticsearchClient esClient;

    @Value("${search.product.index-name:product_index}")
    private String productIndexName;

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            JSONObject canalMsg = JSON.parseObject(body);

            if (canalMsg == null) {
                log.error("[商品索引同步] 消息体为空，跳过: msgId={}", msg.getMsgId());
                return;
            }

            // 兼容两种消息格式
            if (canalMsg.containsKey("database") && canalMsg.containsKey("data")) {
                handleCanalMessage(canalMsg, msg.getMsgId());
            } else if (canalMsg.containsKey("spuId") && canalMsg.containsKey("type")) {
                handleFlatMessage(canalMsg, msg.getMsgId());
            } else {
                log.error("[商品索引同步] 消息格式无法识别，跳过: msgId={}, body={}", msg.getMsgId(), body);
            }

        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException
                 | java.io.IOException e) {
            log.error("[商品索引同步] ES 通信异常，触发重试: msgId={}, reconsumeTimes={}",
                    msg.getMsgId(), msg.getReconsumeTimes(), e);
            throw new RuntimeException("商品索引同步失败（可重试）", e);
        } catch (Exception e) {
            log.error("[商品索引同步] 不可重试异常，跳过: msgId={}", msg.getMsgId(), e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }

    /**
     * 处理 Canal 原始格式消息
     * <p>
     * 只处理 t_spu 表的变更（SPU 维度索引）。
     * t_sku 表变更暂时忽略（SKU 信息在全量重建时关联写入）。
     * </p>
     */
    private void handleCanalMessage(JSONObject canalMsg, String msgId) throws Exception {
        String table = canalMsg.getString("table");
        String type = canalMsg.getString("type");
        JSONArray dataArray = canalMsg.getJSONArray("data");
        // 优先使用 es（event sequence，严格递增），降级使用 ts
        long version = canalMsg.getLongValue("es", 0);
        if (version == 0) {
            version = canalMsg.getLongValue("ts", System.currentTimeMillis());
        }

        // 只处理 t_spu 表（SPU 维度索引）
        if (!"t_spu".equals(table)) {
            log.debug("[商品索引同步] 忽略非 SPU 表变更: table={}", table);
            return;
        }

        if (dataArray == null || dataArray.isEmpty()) {
            log.warn("[商品索引同步] Canal 消息 data 为空: msgId={}", msgId);
            return;
        }

        for (int i = 0; i < dataArray.size(); i++) {
            JSONObject row = dataArray.getJSONObject(i);
            Long spuId = row.getLong("id");
            if (spuId == null) {
                log.warn("[商品索引同步] 行数据缺少 id 字段，跳过: msgId={}", msgId);
                continue;
            }

            switch (type) {
                case "INSERT", "UPDATE" -> indexProductFromCanal(spuId, row, version);
                case "DELETE" -> deleteProduct(spuId);
                default -> log.debug("[商品索引同步] 忽略事件类型: type={}", type);
            }
        }
    }

    /**
     * 处理应用层扁平格式消息
     */
    private void handleFlatMessage(JSONObject event, String msgId) throws Exception {
        String type = event.getString("type");
        Long spuId = event.getLong("spuId");
        if (spuId == null) {
            log.error("[商品索引同步] spuId 为空，跳过: msgId={}", msgId);
            return;
        }

        switch (type) {
            case "INSERT", "UPDATE" -> indexProductFromFlat(spuId, event);
            case "DELETE" -> deleteProduct(spuId);
            default -> log.warn("[商品索引同步] 未知事件类型: type={}, spuId={}", type, spuId);
        }
    }

    /**
     * 从 Canal 行数据构建 ES 文档并索引
     * <p>
     * 使用 ExternalGte 版本类型：允许相同版本号写入（同一毫秒内多次变更），
     * 但拒绝更低版本号写入（防止乱序旧消息覆盖新数据）。
     * </p>
     */
    private void indexProductFromCanal(Long spuId, JSONObject row, long version) throws Exception {
        Map<String, Object> doc = new HashMap<>();
        doc.put("spuId", spuId);
        doc.put("name", row.getString("name"));
        doc.put("categoryId", row.getLong("category_id"));
        doc.put("categoryName", row.getString("category_name"));
        doc.put("brandName", row.getString("brand_name"));
        doc.put("price", row.getBigDecimal("price"));
        doc.put("image", row.getString("main_image"));
        doc.put("sales", row.getIntValue("sales", 0));
        doc.put("status", row.getIntValue("status", 1));
        doc.put("createdAt", row.getString("created_at"));

        String jsonDoc = JSON.toJSONString(doc);

        esClient.index(IndexRequest.of(idx -> idx
                .index(productIndexName)
                .id(String.valueOf(spuId))
                .versionType(co.elastic.clients.elasticsearch._types.VersionType.ExternalGte)
                .version(version)
                .withJson(new StringReader(jsonDoc))));

        log.info("[商品索引同步] Canal 索引成功: spuId={}, version={}", spuId, version);
    }

    /**
     * 从扁平格式消息构建 ES 文档并索引
     * <p>
     * 添加 ExternalGte 版本控制，与 Canal 格式消息一致。
     * 扁平消息使用当前时间戳作为版本号（毫秒级，同毫秒内多次更新可能丢失，
     * 但比完全没有版本控制好——至少可以防止秒级乱序消息覆盖新数据）。
     * </p>
     */
    private void indexProductFromFlat(Long spuId, JSONObject event) throws Exception {
        Map<String, Object> doc = new HashMap<>();
        doc.put("spuId", spuId);
        doc.put("name", event.get("name"));
        doc.put("categoryId", event.get("categoryId"));
        doc.put("categoryName", event.get("categoryName"));
        doc.put("brandName", event.get("brandName"));
        doc.put("price", event.get("price"));
        doc.put("image", event.get("image"));
        doc.put("sales", event.getOrDefault("sales", 0));
        doc.put("status", event.getOrDefault("status", 1));
        doc.put("createdAt", event.get("createdAt"));

        String jsonDoc = JSON.toJSONString(doc);

        // 使用时间戳作为版本号，防止乱序消息覆盖
        // 优先使用消息自带的时间戳，降级使用当前时间
        long version = event.getLongValue("timestamp", System.currentTimeMillis());

        esClient.index(IndexRequest.of(idx -> idx
                .index(productIndexName)
                .id(String.valueOf(spuId))
                .versionType(co.elastic.clients.elasticsearch._types.VersionType.ExternalGte)
                .version(version)
                .withJson(new StringReader(jsonDoc))));

        log.info("[商品索引同步] 扁平格式索引成功: spuId={}, version={}", spuId, version);
    }

    /**
     * 标记删除商品文档（而非物理删除，防止乱序消息重新索引）
     */
    private void deleteProduct(Long spuId) throws Exception {
        Map<String, Object> doc = new HashMap<>();
        doc.put("spuId", spuId);
        doc.put("status", -1); // -1 表示已删除，搜索时过滤

        String jsonDoc = JSON.toJSONString(doc);

        try {
            esClient.index(IndexRequest.of(idx -> idx
                    .index(productIndexName)
                    .id(String.valueOf(spuId))
                    .withJson(new StringReader(jsonDoc))));
            log.info("[商品索引同步] 标记删除成功: spuId={}", spuId);
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException e) {
            if (e.getMessage() != null && e.getMessage().contains("not_found")) {
                log.debug("[商品索引同步] 文档不存在，跳过删除: spuId={}", spuId);
            } else {
                throw e;
            }
        }
    }
}
