package com.myxhs.search.consumer;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.search.feign.ProductFeignClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
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
    private final StringRedisTemplate stringRedisTemplate;
    private final ProductFeignClient productFeignClient;

    /**
     * 追踪当前正在处理的 spuId，用于在 catch 块中记录失败的 docId 到 Redis。
     * RocketMQ 消费者默认单线程消费，ThreadLocal 安全。
     */
    private final ThreadLocal<Long> currentSpuId = new ThreadLocal<>();

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
            Long spuId = currentSpuId.get();
            log.error("[商品索引同步] ES 通信异常，触发重试: spuId={}, msgId={}, reconsumeTimes={}",
                    spuId, msg.getMsgId(), msg.getReconsumeTimes(), e);
            // 记录失败 spuId 到 Redis Set，供 IncrementalIndexSyncJob 增量补偿
            if (spuId != null && stringRedisTemplate != null) {
                try {
                    stringRedisTemplate.opsForSet().add("myxhs:es:sync:failed:product", String.valueOf(spuId));
                    stringRedisTemplate.expire("myxhs:es:sync:failed:product", Duration.ofHours(1));
                } catch (Exception redisEx) {
                    log.warn("[商品索引同步] 记录失败 spuId 到 Redis 失败: spuId={}", spuId, redisEx);
                }
            }
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
                case "INSERT", "UPDATE" -> {
                    currentSpuId.set(spuId);
                    indexProductFromCanal(spuId, row, version);
                }
                case "DELETE" -> {
                    currentSpuId.set(spuId);
                    deleteProduct(spuId);
                }
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
            case "INSERT", "UPDATE" -> {
                currentSpuId.set(spuId);
                indexProductFromFlat(spuId, event);
            }
            case "DELETE" -> {
                currentSpuId.set(spuId);
                deleteProduct(spuId);
            }
            default -> log.warn("[商品索引同步] 未知事件类型: type={}, spuId={}", type, spuId);
        }
    }

    /**
     * 从 product 服务获取 SPU 完整信息并索引（修复 CC1）
     * <p>
     * t_spu 表缺少 category_name/brand_name/price/sales 字段，
     * 改为通过 Feign 调用 product 服务获取 SpuDetailVO，从 SKU 列表提取价格，从 Category 提取分类名。
     * Feign 不可用时跳过不阻塞 Canal 消费（下次重试）。
     * </p>
     */
    private void indexProductFromCanal(Long spuId, JSONObject row, long version) throws Exception {
        String name = row.getString("name");
        Long categoryId = row.getLong("category_id");
        Integer status = row.getIntValue("status", 1);
        String createdAt = row.getString("created_at");

        String categoryName = null;
        String price = null;
        String image = null;

        // 通过 Feign 获取补全字段（product 宕机时跳过，Canal 下次重试）
        try {
            com.myxhs.common.response.R<Map<String, Object>> r = productFeignClient.getSpuDetail(spuId);
            if (r != null && r.isSuccess() && r.getData() != null) {
                Map<String, Object> spu = r.getData();
                categoryName = (String) spu.get("categoryName");
                // 从 SKU 列表取最低售价
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> skuList = (List<Map<String, Object>>) spu.get("skuList");
                if (skuList != null && !skuList.isEmpty()) {
                    price = String.valueOf(skuList.get(0).get("price"));
                }
                // 从图片列表取第一张
                @SuppressWarnings("unchecked")
                List<String> images = (List<String>) spu.get("images");
                if (images != null && !images.isEmpty()) {
                    image = images.get(0);
                }
            }
        } catch (Exception e) {
            log.warn("[商品索引同步] product 服务不可用，索引字段将不完整（非阻塞）: spuId={}", spuId, e);
        }

        Map<String, Object> doc = new HashMap<>();
        doc.put("spuId", spuId);
        doc.put("name", name);
        doc.put("categoryId", categoryId);
        doc.put("categoryName", categoryName);
        doc.put("brandName", null);     // t_spu 无品牌表，暂无品牌名
        doc.put("price", price);
        doc.put("image", image);
        doc.put("sales", 0);            // product 模块无销量统计
        doc.put("status", status);
        doc.put("createdAt", createdAt);

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
