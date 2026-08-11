package com.myxhs.search.job;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;

/**
 * 全量索引重建任务
 * <p>
 * 职责：
 * 1. 分页扫描 MySQL 数据 → 批量写入 ES（BulkRequest）
 * 2. 断点续传：记录 lastId 到 Redis，中断后从上次位置继续
 * 3. 分布式安全：Redisson 分布式锁保证多实例只有一个执行
 * </p>
 * <p>
 * 触发方式：
 * - 定时：每天凌晨 4 点自动执行（增量补偿，修复 Canal 漏同步的数据）
 * - 手动：POST /api/search/index/rebuild（管理员触发全量重建）
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexRebuildJob {

    private final ElasticsearchClient esClient;
    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;

    @Value("${search.note.index-name:note_index}")
    private String noteIndexName;

    @Value("${search.product.index-name:product_index}")
    private String productIndexName;

    @Value("${search.suggest.index-name:suggest_index}")
    private String suggestIndexName;

    @Value("${search.rebuild.batch-size:500}")
    private int batchSize;

    private static final String LOCK_KEY = "myxhs:lock:job:search:index:rebuild";
    private static final String REBUILD_STATUS_KEY = "myxhs:search:index:rebuild:status";

    /**
     * 定时全量重建（每天凌晨 4 点）
     * <p>
     * 分布式锁保证多实例只有一个执行。
     * 锁超时 2 小时（大数据量重建可能耗时较长）。
     * </p>
     */
    @Scheduled(cron = "${search.rebuild.cron:0 0 4 * * ?}")
    public void scheduledRebuild() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 7200, TimeUnit.SECONDS);
            if (!acquired) {
                return;
            }
            doRebuild();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("[索引重建] 执行异常", e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 手动触发全量重建（由 Controller 调用，异步执行）
     * <p>
     * 异步提交到线程池执行，接口立即返回，避免 HTTP 超时。
     * 重建进度可通过 Redis REBUILD_STATUS_KEY 查询。
     * </p>
     */
    public void manualRebuild() {
        // 【修复M18】锁的获取和释放必须在同一线程中完成。
        // 将整个加锁→重建→解锁流程移入异步线程，避免 isHeldByCurrentThread() 跨线程失效。
        CompletableFuture.runAsync(() -> {
            RLock lock = redissonClient.getLock(LOCK_KEY);
            boolean acquired = false;
            try {
                acquired = lock.tryLock(5, 7200, TimeUnit.SECONDS);
                if (!acquired) {
                    log.warn("[索引重建] 任务正在执行中，跳过本次手动触发");
                    return;
                }
                stringRedisTemplate.delete(REBUILD_STATUS_KEY);
                doRebuild();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("[索引重建] 被中断", e);
            } catch (Exception e) {
                log.error("[索引重建] 异步执行异常", e);
            } finally {
                if (acquired && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        });
    }

    /**
     * 执行全量重建
     * <p>
     * 断点续传：
     * - 每批写入成功后，记录当前 lastId 到 Redis
     * - 下次执行时从 lastId 继续（定时任务场景）
     * - 手动触发时清除断点，从头开始
     * </p>
     */
    private void doRebuild() {
        log.info("[索引重建] 开始执行...");

        // 前置检查：源表是否存在（Canal 未启动时可能不存在）
        try {
            jdbcTemplate.queryForList("SELECT 1 FROM t_note LIMIT 1");
        } catch (Exception e) {
            log.info("[索引重建] 源表 t_note 不可用，跳过重建(data sync not ready)");
            return;
        }

        long startTime = System.currentTimeMillis();

        // 读取断点
        String lastIdStr = (String) stringRedisTemplate.opsForHash().get(REBUILD_STATUS_KEY, "lastNoteId");
        long lastNoteId = lastIdStr != null ? Long.parseLong(lastIdStr) : 0;

        int totalIndexed = 0;

        // ===== 重建笔记索引 =====
        while (true) {
            List<Map<String, Object>> notes = jdbcTemplate.queryForList(
"SELECT id, user_id, title, content, cover_url, status, created_at FROM t_note " +
                            "WHERE id > ? AND deleted = 0 ORDER BY id ASC LIMIT ?",
                    lastNoteId, batchSize);

            if (notes.isEmpty()) {
                break;
            }

            // 批量写入 ES
            int indexed = bulkIndexNotes(notes);
            totalIndexed += indexed;

            // 断点续传安全策略：只有全部成功才推进断点
            // 部分失败时不推进，下次重试会重新索引这批数据（ES upsert 幂等）
            if (indexed < notes.size()) {
                log.warn("[索引重建] 笔记批次部分失败: 成功={}/{}, 断点不推进", indexed, notes.size());
                break; // 中断本次重建，下次从当前位置重试
            }

            // 全部成功，推进断点
            lastNoteId = ((Number) notes.get(notes.size() - 1).get("id")).longValue();
            stringRedisTemplate.opsForHash().put(REBUILD_STATUS_KEY, "lastNoteId", String.valueOf(lastNoteId));
            stringRedisTemplate.opsForHash().put(REBUILD_STATUS_KEY, "totalIndexed", String.valueOf(totalIndexed));
            stringRedisTemplate.expire(REBUILD_STATUS_KEY, Duration.ofDays(1));

            if (notes.size() < batchSize) {
                break; // 最后一批
            }
        }

        // ===== 重建搜索建议索引（在商品索引前执行——t_note可用但t_spu不可用时仍能填充） =====
        try {
            int suggestIndexed = rebuildSuggestIndex();
            if (suggestIndexed > 0) {
                totalIndexed += suggestIndexed;
                log.info("[索引重建] 建议关键词已索引{}条", suggestIndexed);
            }
        } catch (Exception e) {
            log.error("[索引重建] 建议索引重建失败（不影响其他索引）", e);
        }

        // ===== 重建商品索引 =====
        try {
        String lastSpuIdStr = (String) stringRedisTemplate.opsForHash().get(REBUILD_STATUS_KEY, "lastSpuId");
        long lastSpuId = lastSpuIdStr != null ? Long.parseLong(lastSpuIdStr) : 0;

        while (true) {
            List<Map<String, Object>> products = jdbcTemplate.queryForList(
                    // t_spu 表实际字段 — category_name/price/image 由 buildProductDocument 默认值补全
                    // 【修复】t_spu 在 my_xhs_product 库，search 默认数据源是 my_xhs_content，必须跨库限定
                    "SELECT id, name, category_id, brand_id, description, images, status, created_at FROM my_xhs_product.t_spu " +
                            "WHERE id > ? AND deleted = 0 ORDER BY id ASC LIMIT ?",
                    lastSpuId, batchSize);

            if (products.isEmpty()) {
                break;
            }

            int indexed = bulkIndexProducts(products);
            totalIndexed += indexed;

            // 断点续传安全策略：部分失败时不推进断点
            if (indexed < products.size()) {
                log.warn("[索引重建] 商品批次部分失败: 成功={}/{}, 断点不推进", indexed, products.size());
                break;
            }

            lastSpuId = ((Number) products.get(products.size() - 1).get("id")).longValue();
            stringRedisTemplate.opsForHash().put(REBUILD_STATUS_KEY, "lastSpuId", String.valueOf(lastSpuId));
            stringRedisTemplate.expire(REBUILD_STATUS_KEY, Duration.ofDays(1));

            if (products.size() < batchSize) {
                break;
            }
        }
        } catch (Exception e) {
            log.error("[索引重建] 商品索引重建失败（不影响笔记和建议索引）", e);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[索引重建] 完成: 共索引{}条, 耗时{}ms", totalIndexed, elapsed);

        // 记录完成状态
        stringRedisTemplate.opsForHash().put(REBUILD_STATUS_KEY, "status", "COMPLETED");
        stringRedisTemplate.opsForHash().put(REBUILD_STATUS_KEY, "completedAt",
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
    }

    /**
     * 批量索引笔记（BulkRequest）
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
                                .document(doc)));
            }

            BulkResponse response = esClient.bulk(bulkBuilder.build());
            if (response.errors()) {
                int errorCount = 0;
                for (BulkResponseItem item : response.items()) {
                    if (item.error() != null) {
                        errorCount++;
                        log.warn("[索引重建] 笔记索引失败: id={}, error={}",
                                item.id(), item.error().reason());
                    }
                }
                return notes.size() - errorCount;
            }
            return notes.size();

        } catch (Exception e) {
            log.error("[索引重建] 笔记批量索引异常", e);
            return 0;
        }
    }

    /**
     * 批量索引商品
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
                                .document(doc)));
            }

            BulkResponse response = esClient.bulk(bulkBuilder.build());
            if (response.errors()) {
                int errorCount = 0;
                for (BulkResponseItem item : response.items()) {
                    if (item.error() != null) {
                        errorCount++;
                    }
                }
                return products.size() - errorCount;
            }
            return products.size();

        } catch (Exception e) {
            log.error("[索引重建] 商品批量索引异常", e);
            return 0;
        }
    }

    private Map<String, Object> buildNoteDocument(Map<String, Object> note) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("noteId", note.getOrDefault("id", 0L));
        doc.put("userId", note.getOrDefault("user_id", 0L));
        doc.put("title", note.getOrDefault("title", ""));
        doc.put("content", note.getOrDefault("content", ""));
        doc.put("coverImage", note.getOrDefault("cover_url", ""));
        doc.put("likeCount", 0);
        doc.put("collectCount", 0);
        doc.put("commentCount", 0);
        doc.put("status", note.getOrDefault("status", 1));
        doc.put("createdAt", note.get("created_at") != null ? note.get("created_at").toString() : null);
        return doc;
    }

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
                log.debug("[索引重建] images 解析失败，使用空图片: {}", imagesStr);
            }
        }

        Map<String, Object> doc = new HashMap<>();
        doc.put("spuId", product.get("id"));
        doc.put("name", product.getOrDefault("name", ""));
        doc.put("categoryId", product.getOrDefault("category_id", 0));
        doc.put("categoryName", "");    // t_spu 无此字段，需 product 服务补全
        doc.put("brandId", product.getOrDefault("brand_id", 0));
        doc.put("brandName", "");       // t_spu 无此字段
        doc.put("price", 0);            // 价格在 t_sku 表，重建时不补
        doc.put("image", firstImage);
        doc.put("sales", 0);            // 无销量统计
        doc.put("status", product.getOrDefault("status", 1));
        doc.put("createdAt", product.get("created_at") != null ? product.get("created_at").toString() : null);
        return doc;
    }

    /**
     * 重建搜索建议索引（suggest_index）
     * <p>
     * 从 t_note 读取已发布笔记的 title，写入 ES Completion Suggester。
     * title 经 IK 分词后生成候选建议词。
     * </p>
     */
    private int rebuildSuggestIndex() {
        int total = 0;
        long lastId = 0;
        while (true) {
            List<Map<String, Object>> notes = jdbcTemplate.queryForList(
                    "SELECT id, title FROM t_note WHERE id > ? AND deleted = 0 AND status = 2 " +
                            "ORDER BY id ASC LIMIT ?",
                    lastId, batchSize);
            if (notes.isEmpty()) break;

            int indexed = bulkIndexSuggest(notes);
            total += indexed;

            if (indexed < notes.size()) {
                log.warn("[索引重建] 建议批次部分失败: 成功={}/{}", indexed, notes.size());
                break;
            }
            lastId = ((Number) notes.get(notes.size() - 1).get("id")).longValue();
            if (notes.size() < batchSize) break;
        }
        return total;
    }

    /**
     * 批量写入 note title 到 suggest_index
     */
    private int bulkIndexSuggest(List<Map<String, Object>> notes) {
        try {
            BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
            for (Map<String, Object> note : notes) {
                Long noteId = ((Number) note.get("id")).longValue();
                String title = (String) note.getOrDefault("title", "");
                if (title.isBlank()) continue;
                Map<String, Object> doc = buildSuggestDoc(title);
                bulkBuilder.operations(op -> op
                        .index(idx -> idx
                                .index(suggestIndexName)
                                .id("note_" + noteId)
                                .document(doc)));
            }
            BulkResponse response = esClient.bulk(bulkBuilder.build());
            if (response.errors()) {
                int errorCount = 0;
                for (BulkResponseItem item : response.items()) {
                    if (item.error() != null) errorCount++;
                }
                return notes.size() - errorCount;
            }
            return notes.size();
        } catch (Exception e) {
            log.error("[索引重建] 建议批量索引异常", e);
            return 0;
        }
    }

    /**
     * 构建 suggest_index 文档（Completion Suggester 格式）
     * <p>
     * keyword 字段经 IK 分词生成建议词列表；
     * weight 用于排序，取基本值 1（后续可扩展为搜索热度权重）。
     * </p>
     */
    private Map<String, Object> buildSuggestDoc(String title) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("keyword", title);
        doc.put("weight", 1);
        return doc;
    }
}
