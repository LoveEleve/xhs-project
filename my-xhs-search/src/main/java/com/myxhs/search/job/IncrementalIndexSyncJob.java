package com.myxhs.search.job;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;

/**
 * ES 索引增量补偿任务
 * <p>
 * 职责：
 * 从 Redis Set 中读取 ES 索引同步失败的 docId（由 NoteIndexSyncConsumer /
 * ProductIndexSyncConsumer 在 catch 块中记录），从 MySQL 查询最新数据重新索引到 ES。
 * </p>
 * <p>
 * 设计考量：
 * </p>
 * <ul>
 *   <li><b>触发方式</b>：每 5 分钟定时执行，无需手动触发。</li>
 *   <li><b>数据源</b>：Redis Set（SPOP 原子弹出），避免多实例重复处理。</li>
 *   <li><b>补偿成功率</b>：失败时 SADD 回 Redis Set，等待下次重试。</li>
 *   <li><b>与全量重建互补</b>：全量重建（IndexRebuildJob）每天凌晨 4 点执行，
 *       本任务填补了 24 小时的补偿间隙，做到分钟级补偿。</li>
 *   <li><b>性能</b>：使用 ES Bulk API 批量索引，减少网络往返。</li>
 *   <li><b>版本控制</b>：使用 ExternalGte version type，防止乱序覆盖。</li>
 * </ul>
 *
 * @see com.myxhs.search.consumer.NoteIndexSyncConsumer
 * @see com.myxhs.search.consumer.ProductIndexSyncConsumer
 * @see IndexRebuildJob
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IncrementalIndexSyncJob {

    private final StringRedisTemplate stringRedisTemplate;
    private final ElasticsearchClient esClient;
    private final JdbcTemplate jdbcTemplate;

    @Value("${search.note.index-name:note_index}")
    private String noteIndexName;

    @Value("${search.product.index-name:product_index}")
    private String productIndexName;

    private static final String FAILED_NOTE_KEY = "myxhs:es:sync:failed:note";
    private static final String FAILED_PRODUCT_KEY = "myxhs:es:sync:failed:product";
    private static final int MAX_BATCH = 50;
    // P1-4：移除固定 currentTimeMillis() 版本。改为用 DB 行 updated_at(毫秒) 作为 ExternalGte 版本，
    // 与 NoteIndexSyncConsumer 的 ts(毫秒) 统一版本域，避免补偿巨值版本永久拒绝后续 Canal 增量更新。

    /**
     * 定时增量补偿（每 5 分钟）
     */
    @Scheduled(fixedRate = 60_000) // 每 1 分钟（测试环境快速验证）
    public void incrementalCompensate() {
        long startTime = System.currentTimeMillis();
        int noteCompensated = compensateFailedNotes();
        int productCompensated = compensateFailedProducts();

        long elapsed = System.currentTimeMillis() - startTime;
        if (noteCompensated > 0 || productCompensated > 0) {
            log.info("[ES增量补偿] 完成: 笔记={}, 商品={}, 耗时={}ms",
                    noteCompensated, productCompensated, elapsed);
        }
    }

    /**
     * 补偿失败的笔记索引
     * <p>
     * 使用 SPOP 原子弹出 noteId（最多 MAX_BATCH 个），
     * 从 MySQL 查询最新数据，通过 ES Bulk API 批量索引。
     * 索引失败时 SADD 回 Redis Set 等待下次重试。
     * </p>
     */
    private int compensateFailedNotes() {
        Set<String> noteIds = stringRedisTemplate.opsForSet()
                .distinctRandomMembers(FAILED_NOTE_KEY, MAX_BATCH);

        if (noteIds == null || noteIds.isEmpty()) {
            return 0;
        }

        // 从 Redis Set 中移除（SPOP 等效）
        noteIds.forEach(id -> stringRedisTemplate.opsForSet().remove(FAILED_NOTE_KEY, id));

        List<Long> ids = new ArrayList<>();
        for (String s : noteIds) {
            try {
                ids.add(Long.parseLong(s));
            } catch (NumberFormatException e) {
                log.warn("[ES增量补偿] 非法 noteId 格式: {}", s);
            }
        }

        if (ids.isEmpty()) {
            return 0;
        }

        // 从 MySQL 批量查询笔记数据
        List<Map<String, Object>> notes = queryNotesByIds(ids);
        if (notes.isEmpty()) {
            log.info("[ES增量补偿] 笔记数据不存在，可能已被删除: ids={}", ids);
            return 0;
        }

        // 批量索引到 ES
        int indexed = bulkIndexNotes(notes);

        // 未成功索引的 ID 放回 Redis Set
        if (indexed < notes.size()) {
            for (Map<String, Object> note : notes) {
                Long noteId = ((Number) note.get("id")).longValue();
                // 检查是否在原始 id 列表中且未成功索引
                if (ids.contains(noteId)) {
                    stringRedisTemplate.opsForSet().add(FAILED_NOTE_KEY, String.valueOf(noteId));
                    stringRedisTemplate.expire(FAILED_NOTE_KEY, Duration.ofHours(1));
                }
            }
            log.warn("[ES增量补偿] 笔记部分补偿失败: 成功={}/{}", indexed, notes.size());
        }

        return indexed;
    }

    /**
     * 补偿失败的商品索引
     */
    private int compensateFailedProducts() {
        Set<String> spuIds = stringRedisTemplate.opsForSet()
                .distinctRandomMembers(FAILED_PRODUCT_KEY, MAX_BATCH);

        if (spuIds == null || spuIds.isEmpty()) {
            return 0;
        }

        spuIds.forEach(id -> stringRedisTemplate.opsForSet().remove(FAILED_PRODUCT_KEY, id));

        List<Long> ids = new ArrayList<>();
        for (String s : spuIds) {
            try {
                ids.add(Long.parseLong(s));
            } catch (NumberFormatException e) {
                log.warn("[ES增量补偿] 非法 spuId 格式: {}", s);
            }
        }

        if (ids.isEmpty()) {
            return 0;
        }

        List<Map<String, Object>> products = queryProductsByIds(ids);
        if (products.isEmpty()) {
            log.info("[ES增量补偿] 商品数据不存在，可能已被删除: ids={}", ids);
            return 0;
        }

        int indexed = bulkIndexProducts(products);

        if (indexed < products.size()) {
            for (Map<String, Object> product : products) {
                Long spuId = ((Number) product.get("id")).longValue();
                if (ids.contains(spuId)) {
                    stringRedisTemplate.opsForSet().add(FAILED_PRODUCT_KEY, String.valueOf(spuId));
                    stringRedisTemplate.expire(FAILED_PRODUCT_KEY, Duration.ofHours(1));
                }
            }
            log.warn("[ES增量补偿] 商品部分补偿失败: 成功={}/{}", indexed, products.size());
        }

        return indexed;
    }

    /**
     * 从 MySQL 批量查询笔记数据
     * <p>
     * 查询字段参考 NoteIndexSyncConsumer#indexNoteFromCanal 的文档结构，
     * 包含所有 ES 文档所需字段。
     * </p>
     */
    private List<Map<String, Object>> queryNotesByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }

        String placeholders = String.join(",", ids.stream().map(id -> "?").toArray(String[]::new));
        String sql = "SELECT id, user_id, title, content, cover_url, status, created_at, updated_at " +
                "FROM t_note WHERE id IN (" + placeholders + ") AND deleted = 0";

        Object[] params = ids.toArray();
        return jdbcTemplate.queryForList(sql, params);
    }

    /**
     * 从 MySQL 批量查询商品数据
     * <p>
     * 查询字段参考 IndexRebuildJob#buildProductDocument 的文档结构。
     * </p>
     */
    private List<Map<String, Object>> queryProductsByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }

        String placeholders = String.join(",", ids.stream().map(id -> "?").toArray(String[]::new));
        // t_spu 表实际字段：id, name, category_id, brand_id, description, images, status, deleted, created_at, updated_at
        // category_name/price/image 等需通过 product Feign 或 buildProductDocument 默认值补全
        // P1-5：search 数据源默认 schema 是 my_xhs_content（t_note 所在库），t_spu 在 my_xhs_product，
        // 必须显式加库前缀，否则商品补偿必失败（Table 'my_xhs_content.t_spu' doesn't exist）。
        String sql = "SELECT id, name, category_id, brand_id, description, images, status, created_at, updated_at " +
                "FROM my_xhs_product.t_spu WHERE id IN (" + placeholders + ") AND deleted = 0";

        Object[] params = ids.toArray();
        return jdbcTemplate.queryForList(sql, params);
    }

    /**
     * 批量索引笔记到 ES（BulkRequest + ExternalGte 版本控制）
     * <p>
     * 参考 IndexRebuildJob#bulkIndexNotes 的实现。
     * </p>
     */
    private int bulkIndexNotes(List<Map<String, Object>> notes) {
        try {
            BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();

            for (Map<String, Object> note : notes) {
                Long noteId = ((Number) note.get("id")).longValue();
                Map<String, Object> doc = buildNoteDocument(note);

                bulkBuilder.operations(op -> op
                        .index(idx -> idx
                                .index(noteIndexName)
                                .id(String.valueOf(noteId))
                                .versionType(co.elastic.clients.elasticsearch._types.VersionType.ExternalGte)
                                .version(toEpochMillis(note.get("updated_at")))
                                .document(co.elastic.clients.json.JsonData.of(doc))));
            }

            BulkResponse response = esClient.bulk(bulkBuilder.build());
            if (response.errors()) {
                int errorCount = 0;
                for (BulkResponseItem item : response.items()) {
                    if (item.error() != null) {
                        errorCount++;
                        log.warn("[ES增量补偿] 笔记索引失败: id={}, error={}",
                                item.id(), item.error().reason());
                    }
                }
                return notes.size() - errorCount;
            }
            return notes.size();

        } catch (Exception e) {
            log.error("[ES增量补偿] 笔记批量索引异常", e);
            return 0;
        }
    }

    /**
     * 批量索引商品到 ES
     */
    private int bulkIndexProducts(List<Map<String, Object>> products) {
        try {
            BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();

            for (Map<String, Object> product : products) {
                Long spuId = ((Number) product.get("id")).longValue();
                Map<String, Object> doc = buildProductDocument(product);

                bulkBuilder.operations(op -> op
                        .index(idx -> idx
                                .index(productIndexName)
                                .id(String.valueOf(spuId))
                                .versionType(co.elastic.clients.elasticsearch._types.VersionType.ExternalGte)
                                .version(toEpochMillis(product.get("updated_at")))
                                .document(co.elastic.clients.json.JsonData.of(doc))));
            }

            BulkResponse response = esClient.bulk(bulkBuilder.build());
            if (response.errors()) {
                int errorCount = 0;
                for (BulkResponseItem item : response.items()) {
                    if (item.error() != null) {
                        errorCount++;
                        log.warn("[ES增量补偿] 商品索引失败: id={}, error={}",
                                item.id(), item.error().reason());
                    }
                }
                return products.size() - errorCount;
            }
            return products.size();

        } catch (Exception e) {
            log.error("[ES增量补偿] 商品批量索引异常", e);
            return 0;
        }
    }

    /**
     * 将 DB 行 updated_at 转成毫秒时间戳（P1-4：与 Canal ts 版本域一致，避免补偿版本永久拦截后续增量）
     */
    private long toEpochMillis(Object v) {
        if (v instanceof java.sql.Timestamp) return ((java.sql.Timestamp) v).getTime();
        if (v instanceof java.util.Date) return ((java.util.Date) v).getTime();
        if (v instanceof java.time.LocalDateTime) {
            return ((java.time.LocalDateTime) v).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try {
                return java.time.LocalDateTime.parse((String) v,
                                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (Exception ignore) {
                // fallthrough
            }
        }
        return System.currentTimeMillis();
    }

    /**
     * 构建笔记 ES 文档 JSON
     * <p>
     * 参考 NoteIndexSyncConsumer#indexNoteFromCanal 的文档结构。
     * 注意：增量补偿时不覆盖 likeCount/collectCount/commentCount，
     * 这些由计数器服务维护。
     * </p>
     */
    private Map<String, Object> buildNoteDocument(Map<String, Object> note) {
        // 用 HashMap 而非 Map.of：Map.of 不允许 null 值，而 DB 行可能含 NULL 列（如 cover_url/user_id），
        // 会直接 NPE 导致补偿必失败。
        Map<String, Object> doc = new HashMap<>();
        doc.put("noteId", note.get("id"));
        doc.put("userId", note.get("user_id"));
        doc.put("title", note.getOrDefault("title", ""));
        doc.put("content", note.getOrDefault("content", ""));
        doc.put("coverImage", note.get("cover_url"));
        doc.put("status", note.getOrDefault("status", 1));
        doc.put("createdAt", note.get("created_at") != null ? note.get("created_at").toString() : null);
        return doc;
    }

    /**
     * 构建商品 ES 文档 JSON
     * <p>
     * 参考 IndexRebuildJob#buildProductDocument 的文档结构。
     * </p>
     */
    private Map<String, Object> buildProductDocument(Map<String, Object> product) {
        // 从 images JSON 数组提取第一张图片
        String firstImage = "";
        Object imagesObj = product.get("images");
        if (imagesObj instanceof String imagesStr && !imagesStr.isEmpty()) {
            try {
                JSONArray arr = JSON.parseArray(imagesStr);
                if (arr != null && !arr.isEmpty()) {
                    firstImage = arr.getString(0);
                }
            } catch (Exception e) {
                log.debug("[增量补偿] images 解析失败，使用空图片: {}", imagesStr);
            }
        }

        Map<String, Object> doc = new HashMap<>();
        doc.put("spuId", product.get("id"));
        doc.put("name", product.getOrDefault("name", ""));
        doc.put("categoryId", product.getOrDefault("category_id", 0));
        doc.put("categoryName", "");    // t_spu 无此字段，需 product 服务补全
        doc.put("brandId", product.getOrDefault("brand_id", 0));
        doc.put("brandName", "");       // t_spu 无此字段
        doc.put("price", 0);            // 价格在 t_sku 表，补偿时不补
        doc.put("image", firstImage);
        doc.put("sales", 0);            // 无销量统计
        doc.put("status", product.getOrDefault("status", 1));
        doc.put("createdAt", product.get("created_at") != null ? product.get("created_at").toString() : null);
        return doc;
    }
}
