package com.myxhs.search.consumer;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.myxhs.common.response.ResultCode;
import com.myxhs.common.trace.MqTraceHelper;
import com.myxhs.search.feign.ProductFeignClient;
import com.myxhs.search.service.ProductIndexDocumentBuilder;
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
    private final ProductIndexDocumentBuilder productIndexDocumentBuilder;

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
            Long spuId = currentSpuId.get();
            log.error("[商品索引同步] 商品文档构建失败，触发重试: spuId={}, msgId={}, reconsumeTimes={}",
                    spuId, msg.getMsgId(), msg.getReconsumeTimes(), e);
            if (spuId != null && stringRedisTemplate != null) {
                stringRedisTemplate.opsForSet().add("myxhs:es:sync:failed:product", String.valueOf(spuId));
                stringRedisTemplate.expire("myxhs:es:sync:failed:product", Duration.ofHours(1));
            }
            throw new IllegalStateException("商品索引同步失败", e);
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
        // T-088：统一版本域为毫秒时间戳(ts)（与 note 侧 P1-4 对齐）——
        // 原实现 es（Canal 小整数）优先：补偿任务（updated_at 毫秒 ~1.7e12）写过文档后，
        // 后续 canal es(小整数) 会被 ES ExternalGte 永久拒绝 → product_index 增量冻结。
        long version = canalMsg.getLongValue("ts", System.currentTimeMillis());
        if (version == 0) {
            version = canalMsg.getLongValue("es", System.currentTimeMillis());
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
                    // T-042：逻辑删除（UPDATE deleted=1）→ 标记删除（带版本防乱序覆盖，T-091 同族）
                    if (row.getIntValue("deleted", 0) == 1) {
                        deleteProduct(spuId, version);
                    } else {
                        indexProductFromCanal(spuId, row, version);
                    }
                }
                case "DELETE" -> {
                    currentSpuId.set(spuId);
                    deleteProduct(spuId, version);
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
                deleteProduct(spuId, event.getLongValue("timestamp", System.currentTimeMillis()));
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
        // T-042 修复（2026-08-13）：逻辑删除（deleted=1）→ 同步 ES 删除标记。
        // 删除 SPU 走 @TableLogic（UPDATE 非 DELETE），canal 事件 type=UPDATE，
        // 原实现忽略 deleted 列 → 已删 SPU 在 ES 恒保留（T-040 同款问题）。
        if (row.getIntValue("deleted", 0) == 1) {
            deleteProduct(spuId, version);
            return;
        }
        String name = row.getString("name");
        Long categoryId = row.getLong("category_id");
        Integer status = row.getIntValue("status", 1);
        String createdAt = row.getString("created_at");

        com.myxhs.common.response.R<Map<String, Object>> response = productFeignClient.getSpuDetail(spuId);
        if (response == null || !response.isSuccess() || response.getData() == null) {
            // RV12：商品不存在为确定性失败，跳过不重试（避免无意义死信）；其余（超时/服务不可用）仍重试
            if (response != null && response.getCode() == ResultCode.PRODUCT_NOT_FOUND.getCode()) {
                log.warn("[商品索引同步] 商品不存在，跳过（不可重试）: spuId={}", spuId);
                return;
            }
            throw new IllegalStateException("商品详情获取失败: spuId=" + spuId);
        }
        Map<String, Object> product = new HashMap<>();
        product.put("id", spuId);
        product.put("name", name);
        product.put("category_id", categoryId);
        product.put("brand_id", row.getLong("brand_id"));
        product.put("status", status);
        product.put("created_at", createdAt);
        Map<String, Object> doc = productIndexDocumentBuilder.build(product, response.getData());

        String jsonDoc = JSON.toJSONString(doc);

        try {
            esClient.index(IndexRequest.of(idx -> idx
                    .index(productIndexName)
                    .id(String.valueOf(spuId))
                    .versionType(co.elastic.clients.elasticsearch._types.VersionType.ExternalGte)
                    .version(version)
                    .withJson(new StringReader(jsonDoc))));
        } catch (Exception e) {
            if (isVersionConflict(e)) {
                log.info("[商品索引同步] 陈旧版本消息已忽略(ES 已有更新版本): spuId={}, version={}", spuId, version);
                return;
            }
            throw e;
        }

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
        Map<String, Object> product = new HashMap<>();
        product.put("id", spuId);
        product.put("name", event.get("name"));
        product.put("category_id", event.get("categoryId"));
        product.put("brand_id", event.get("brandId"));
        product.put("status", event.getOrDefault("status", 1));
        product.put("created_at", event.get("createdAt"));
        product.put("images", event.get("images"));
        product.put("category_name", event.get("categoryName"));
        product.put("min_price", event.get("price"));
        Map<String, Object> doc = productIndexDocumentBuilder.build(product, Map.of());

        String jsonDoc = JSON.toJSONString(doc);

        // 使用时间戳作为版本号，防止乱序消息覆盖
        // 优先使用消息自带的时间戳，降级使用当前时间
        long version = event.getLongValue("timestamp", System.currentTimeMillis());

        try {
            esClient.index(IndexRequest.of(idx -> idx
                    .index(productIndexName)
                    .id(String.valueOf(spuId))
                    .versionType(co.elastic.clients.elasticsearch._types.VersionType.ExternalGte)
                    .version(version)
                    .withJson(new StringReader(jsonDoc))));
        } catch (Exception e) {
            if (isVersionConflict(e)) {
                log.info("[商品索引同步] 陈旧版本消息已忽略(ES 已有更新版本): spuId={}, version={}", spuId, version);
                return;
            }
            throw e;
        }

        log.info("[商品索引同步] 扁平格式索引成功: spuId={}, version={}", spuId, version);
    }

    /** 判断是否为 ES 版本冲突（ExternalGte 拒绝陈旧版本，属幂等已应用语义） */
    private boolean isVersionConflict(Throwable e) {
        Throwable t = e;
        while (t != null) {
            String m = String.valueOf(t.getMessage());
            if (m.contains("version_conflict") || m.contains("version conflict")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /**
     * 标记删除商品文档（而非物理删除，防止乱序消息重新索引）
     */
    private void deleteProduct(Long spuId, long version) throws Exception {
        // T-091 同族修复：带 ExternalGte version 防止乱序旧消息覆盖删除标记
        Map<String, Object> doc = new HashMap<>();
        doc.put("spuId", spuId);
        doc.put("status", -1); // -1 表示已删除，搜索时过滤

        String jsonDoc = JSON.toJSONString(doc);

        try {
            esClient.index(IndexRequest.of(idx -> idx
                    .index(productIndexName)
                    .id(String.valueOf(spuId))
                    .versionType(co.elastic.clients.elasticsearch._types.VersionType.ExternalGte)
                    .version(version)
                    .withJson(new StringReader(jsonDoc))));
            log.info("[商品索引同步] 标记删除成功: spuId={}", spuId);
        } catch (Exception e) {
            if (isVersionConflict(e)) {
                log.info("[商品索引同步] 陈旧版本删除标记已忽略(ES 已有更新版本): spuId={}, version={}", spuId, version);
            } else if (e.getMessage() != null && e.getMessage().contains("not_found")) {
                log.debug("[商品索引同步] 文档不存在，跳过删除: spuId={}", spuId);
            } else {
                throw e;
            }
        }
    }
}
