package com.myxhs.search.job;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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

    @Value("${search.rebuild.batch-size:500}")
    private int batchSize;

    private static final String LOCK_KEY = "lock:job:search:index:rebuild";
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
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(5, 7200, TimeUnit.SECONDS);
            if (!acquired) {
                throw new RuntimeException("索引重建任务正在执行中，请稍后重试");
            }
            // 手动重建时清除断点，从头开始
            stringRedisTemplate.delete(REBUILD_STATUS_KEY);
            // 异步执行，释放 HTTP 线程
            final RLock asyncLock = lock;
            CompletableFuture.runAsync(() -> {
                try {
                    doRebuild();
                } catch (Exception e) {
                    log.error("[索引重建] 异步执行异常", e);
                } finally {
                    if (asyncLock.isHeldByCurrentThread()) {
                        asyncLock.unlock();
                    }
                }
            });
            // 标记已提交，不在 finally 中释放锁（由异步线程释放）
            acquired = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("索引重建被中断");
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
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
        long startTime = System.currentTimeMillis();

        // 读取断点
        String lastIdStr = (String) stringRedisTemplate.opsForHash().get(REBUILD_STATUS_KEY, "lastNoteId");
        long lastNoteId = lastIdStr != null ? Long.parseLong(lastIdStr) : 0;

        int totalIndexed = 0;

        // ===== 重建笔记索引 =====
        while (true) {
            List<Map<String, Object>> notes = jdbcTemplate.queryForList(
"SELECT id, user_id, title, content, cover_url, like_count, collect_count, " +
                            "comment_count, status, created_at FROM t_note " +
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

        // ===== 重建商品索引 =====
        String lastSpuIdStr = (String) stringRedisTemplate.opsForHash().get(REBUILD_STATUS_KEY, "lastSpuId");
        long lastSpuId = lastSpuIdStr != null ? Long.parseLong(lastSpuIdStr) : 0;

        while (true) {
            List<Map<String, Object>> products = jdbcTemplate.queryForList(
                    "SELECT id, name, category_id, category_name, brand_name, price, " +
                            "main_image, sales, status, created_at FROM t_spu " +
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
                String doc = buildNoteDocument(note);

                bulkBuilder.operations(op -> op
                        .index(idx -> idx
                                .index(noteIndexName)
                                .id(String.valueOf(noteId))
                                .withJson(new StringReader(doc))));
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
                String doc = buildProductDocument(product);

                bulkBuilder.operations(op -> op
                        .index(idx -> idx
                                .index(productIndexName)
                                .id(String.valueOf(spuId))
                                .withJson(new StringReader(doc))));
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

    private String buildNoteDocument(Map<String, Object> note) {
        Map<String, Object> doc = Map.of(
                "noteId", note.get("id"),
                "userId", note.get("user_id"),
                "title", note.getOrDefault("title", ""),
                "content", note.getOrDefault("content", ""),
"coverImage", note.getOrDefault("cover_url", ""),
                "likeCount", note.getOrDefault("like_count", 0),
                "collectCount", note.getOrDefault("collect_count", 0),
                "commentCount", note.getOrDefault("comment_count", 0),
                "status", note.getOrDefault("status", 1),
                "createdAt", note.get("created_at") != null ? note.get("created_at").toString() : null
        );
        return com.alibaba.fastjson2.JSON.toJSONString(doc);
    }

    private String buildProductDocument(Map<String, Object> product) {
        Map<String, Object> doc = Map.of(
                "spuId", product.get("id"),
                "name", product.getOrDefault("name", ""),
                "categoryId", product.getOrDefault("category_id", 0),
                "categoryName", product.getOrDefault("category_name", ""),
                "brandName", product.getOrDefault("brand_name", ""),
                "price", product.getOrDefault("price", 0),
                "image", product.getOrDefault("main_image", ""),
                "sales", product.getOrDefault("sales", 0),
                "status", product.getOrDefault("status", 1),
                "createdAt", product.get("created_at") != null ? product.get("created_at").toString() : null
        );
        return com.alibaba.fastjson2.JSON.toJSONString(doc);
    }
}
