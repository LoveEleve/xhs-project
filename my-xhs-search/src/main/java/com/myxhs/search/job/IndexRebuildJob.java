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
import com.myxhs.search.service.ProductIndexDocumentBuilder;
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
    private final ProductIndexDocumentBuilder productIndexDocumentBuilder;

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
"SELECT id, user_id, title, content, cover_url, status, created_at, updated_at FROM t_note " +
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
                throw new IllegalStateException("笔记索引批次部分失败: 成功=" + indexed + "/" + notes.size());
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
            throw new IllegalStateException("建议索引重建失败", e);
        }

        // ===== 重建商品索引 =====
        try {
        String lastSpuIdStr = (String) stringRedisTemplate.opsForHash().get(REBUILD_STATUS_KEY, "lastSpuId");
        long lastSpuId = lastSpuIdStr != null ? Long.parseLong(lastSpuIdStr) : 0;

        while (true) {
            List<Map<String, Object>> products = jdbcTemplate.queryForList(
                    // t_spu 在 my_xhs_product 库，search 默认数据源是 my_xhs_content，必须跨库限定
                    "SELECT s.id, s.name, s.category_id, s.brand_id, s.description, s.images, s.status, s.created_at, s.updated_at, " +
                            "c.name AS category_name, MIN(k.price) AS min_price " +
                            "FROM my_xhs_product.t_spu s " +
                            "LEFT JOIN my_xhs_product.t_category c ON c.id = s.category_id AND c.deleted = 0 " +
                            "LEFT JOIN my_xhs_product.t_sku k ON k.spu_id = s.id AND k.deleted = 0 AND k.status = 1 " +
                            "WHERE s.id > ? AND s.deleted = 0 GROUP BY s.id ORDER BY s.id ASC LIMIT ?",
                    lastSpuId, batchSize);

            if (products.isEmpty()) {
                break;
            }

            int indexed = bulkIndexProducts(products);
            totalIndexed += indexed;

            // 断点续传安全策略：部分失败时不推进断点
            if (indexed < products.size()) {
                throw new IllegalStateException("商品索引批次部分失败: 成功=" + indexed + "/" + products.size());
            }

            lastSpuId = ((Number) products.get(products.size() - 1).get("id")).longValue();
            stringRedisTemplate.opsForHash().put(REBUILD_STATUS_KEY, "lastSpuId", String.valueOf(lastSpuId));
            stringRedisTemplate.expire(REBUILD_STATUS_KEY, Duration.ofDays(1));

            if (products.size() < batchSize) {
                break;
            }
        }
        } catch (Exception e) {
            throw new IllegalStateException("商品索引重建失败", e);
        }

        // 死文档清理（best-effort，不阻塞完成标记）：重建只 upsert，
        // 源表逻辑删除的行若错过 Canal 删除事件会永久残留在 ES（搜索可命中已删内容）
        try {
            pruneDeletedDocs();
        } catch (Exception e) {
            log.warn("[索引重建] 死文档清理失败(不阻塞完成标记，下轮重试)", e);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[索引重建] 完成: 共索引{}条, 耗时{}ms", totalIndexed, elapsed);

        // 记录完成状态，并清零断点：
        // 原实现保留 lastNoteId/lastSpuId → 次日定时"全量重建"从上次末尾 id 继续（退化为增量），
        // 且 key 的 1 天 TTL 与次日 4 点调度存在竞态（有时读到陈旧断点、有时已过期，行为不稳定）
        stringRedisTemplate.opsForHash().delete(REBUILD_STATUS_KEY, "lastNoteId", "lastSpuId");
        stringRedisTemplate.opsForHash().put(REBUILD_STATUS_KEY, "status", "COMPLETED");
        stringRedisTemplate.opsForHash().put(REBUILD_STATUS_KEY, "completedAt",
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
    }

    /**
     * 清理源表已逻辑删除的 ES 死文档（笔记/商品/建议索引）
     */
    private void pruneDeletedDocs() {
        int pruned = 0;
        pruned += pruneByDeletedFlag("t_note", noteIndexName);
        pruned += pruneByDeletedFlag("my_xhs_product.t_spu", productIndexName);
        pruned += pruneSuggestForUnavailableNotes();
        if (pruned > 0) {
            log.info("[索引重建] 死文档清理完成: 共处理 {} 条", pruned);
        }
    }

    /**
     * 按"deleted = 1"扫描源表并批量删除对应 ES 文档（不存在的文档 delete 是幂等 no-op）
     */
    private int pruneByDeletedFlag(String qualifiedTable, String indexName) {
        long lastId = 0;
        int pruned = 0;
        while (true) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id FROM " + qualifiedTable + " WHERE deleted = 1 AND id > ? ORDER BY id ASC LIMIT ?",
                    lastId, batchSize);
            if (rows.isEmpty()) {
                break;
            }
            BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
            for (Map<String, Object> row : rows) {
                long id = ((Number) row.get("id")).longValue();
                bulkBuilder.operations(op -> op.delete(d -> d.index(indexName).id(String.valueOf(id))));
            }
            try {
                esClient.bulk(bulkBuilder.build());
                pruned += rows.size();
            } catch (Exception e) {
                log.warn("[索引重建] 死文档清理批次失败(跳过本轮): table={}", qualifiedTable, e);
                break;
            }
            lastId = ((Number) rows.get(rows.size() - 1).get("id")).longValue();
            if (rows.size() < batchSize) {
                break;
            }
        }
        return pruned;
    }

    /**
     * 建议索引只保留"已发布"笔记：删除已删除/非已发布笔记的建议词条
     */
    private int pruneSuggestForUnavailableNotes() {
        long lastId = 0;
        int pruned = 0;
        while (true) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id FROM t_note WHERE (deleted = 1 OR status <> 2) AND id > ? ORDER BY id ASC LIMIT ?",
                    lastId, batchSize);
            if (rows.isEmpty()) {
                break;
            }
            BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
            for (Map<String, Object> row : rows) {
                long id = ((Number) row.get("id")).longValue();
                bulkBuilder.operations(op -> op.delete(d -> d.index(suggestIndexName).id("note_" + id)));
            }
            try {
                esClient.bulk(bulkBuilder.build());
                pruned += rows.size();
            } catch (Exception e) {
                log.warn("[索引重建] 建议索引死词条清理批次失败(跳过本轮)", e);
                break;
            }
            lastId = ((Number) rows.get(rows.size() - 1).get("id")).longValue();
            if (rows.size() < batchSize) {
                break;
            }
        }
        return pruned;
    }

    /**
     * DB DATETIME → 毫秒时间戳（与增量链路 Canal ts 同域，用于 ES external version）
     */
    private long toEpochMillis(Object dbTime) {
        if (dbTime == null) {
            return System.currentTimeMillis();
        }
        if (dbTime instanceof java.sql.Timestamp ts) {
            return ts.getTime();
        }
        if (dbTime instanceof java.time.LocalDateTime ldt) {
            return ldt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        try {
            return Long.parseLong(dbTime.toString());
        } catch (NumberFormatException e) {
            return System.currentTimeMillis();
        }
    }

    /**
     * 批量索引笔记（BulkRequest）
     */
    private int bulkIndexNotes(List<Map<String, Object>> notes) {
        try {
            BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
            Map<Long, Map<Integer, Long>> countsByNote = loadNoteCounts(notes);

            for (Map<String, Object> note : notes) {
                Long noteId = ((Number) note.get("id")).longValue();
                Map<String, Object> doc = buildNoteDocument(note, countsByNote.getOrDefault(noteId, Map.of()));
                // external version = 行 updated_at 毫秒：与增量链路（Canal ts）同域，
                // 防"重建期间的陈旧读"覆盖并发增量写入的新文档（原实现无版本，ES 无条件覆盖）
                long version = toEpochMillis(note.get("updated_at"));

                bulkBuilder.operations(op -> op
                        .index(idx -> idx
                                .index(noteIndexName)
                                .id(String.valueOf(noteId))
                                .version(version)
                                .versionType(co.elastic.clients.elasticsearch._types.VersionType.ExternalGte)
                                .document(doc)));
            }

            BulkResponse response = esClient.bulk(bulkBuilder.build());
            if (response.errors()) {
                int errorCount = 0;
                for (BulkResponseItem item : response.items()) {
                    if (item.error() != null) {
                        // external version 冲突 = 文档已是更新版本（并发增量写入或重建重跑）→ 跳过视为成功，
                        // 否则会把"已是最新"误判为批次失败 → 整个重建抛异常（本次引入版本机制后的关键配套）
                        if ("version_conflict_engine_exception".equals(item.error().type())) {
                            log.debug("[索引重建] 版本更新已存在, 跳过热数据: id={}", item.id());
                            continue;
                        }
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
                Map<String, Object> doc;
                try {
                    doc = productIndexDocumentBuilder.build(product, Map.of());
                } catch (Exception e) {
                    log.warn("[ES重建] 商品文档构建失败, 跳过该SPU: spuId={}, reason={}",
                            spuId, e.getMessage());
                    continue;
                }

                long version = toEpochMillis(product.get("updated_at"));
                bulkBuilder.operations(op -> op
                        .index(idx -> idx
                                .index(productIndexName)
                                .id(String.valueOf(spuId))
                                .version(version)
                                .versionType(co.elastic.clients.elasticsearch._types.VersionType.ExternalGte)
                                .document(doc)));
            }

            BulkResponse response = esClient.bulk(bulkBuilder.build());
            if (response.errors()) {
                int errorCount = 0;
                for (BulkResponseItem item : response.items()) {
                    if (item.error() != null) {
                        if ("version_conflict_engine_exception".equals(item.error().type())) {
                            log.debug("[索引重建] 版本更新已存在, 跳过热商品: id={}", item.id());
                            continue;
                        }
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

    private Map<String, Object> buildNoteDocument(Map<String, Object> note, Map<Integer, Long> counts) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("noteId", note.getOrDefault("id", 0L));
        doc.put("userId", note.getOrDefault("user_id", 0L));
        doc.put("title", note.getOrDefault("title", ""));
        doc.put("content", note.getOrDefault("content", ""));
        doc.put("coverImage", note.getOrDefault("cover_url", ""));
        doc.put("likeCount", counts.getOrDefault(1, 0L));
        doc.put("collectCount", counts.getOrDefault(2, 0L));
        doc.put("commentCount", counts.getOrDefault(3, 0L));
        doc.put("status", note.getOrDefault("status", 1));
        doc.put("createdAt", note.get("created_at") != null ? note.get("created_at").toString() : null);
        return doc;
    }

    private Map<Long, Map<Integer, Long>> loadNoteCounts(List<Map<String, Object>> notes) {
        if (notes.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", notes.stream().map(note -> "?").toList());
        Object[] ids = notes.stream().map(note -> note.get("id")).toArray();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT target_id, count_type, count_value FROM my_xhs_counter.t_counter " +
                        "WHERE target_type = 1 AND target_id IN (" + placeholders + ") AND deleted = 0", ids);
        Map<Long, Map<Integer, Long>> counts = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Long noteId = ((Number) row.get("target_id")).longValue();
            counts.computeIfAbsent(noteId, ignored -> new HashMap<>()).put(
                    ((Number) row.get("count_type")).intValue(),
                    ((Number) row.get("count_value")).longValue());
        }
        return counts;
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
            // 带互动数（点赞）做建议权重：原实现 weight 恒 1 → completion 排序无意义
            List<Map<String, Object>> notes = jdbcTemplate.queryForList(
                    "SELECT n.id, n.title, COALESCE(c.count_value, 0) AS like_count " +
                            "FROM t_note n " +
                            "LEFT JOIN my_xhs_counter.t_counter c ON c.target_type = 1 AND c.target_id = n.id " +
                            "  AND c.count_type = 1 AND c.deleted = 0 " +
                            "WHERE n.id > ? AND n.deleted = 0 AND n.status = 2 " +
                            "ORDER BY n.id ASC LIMIT ?",
                    lastId, batchSize);
            if (notes.isEmpty()) break;

            int indexed = bulkIndexSuggest(notes);
            total += indexed;

            if (indexed < notes.size()) {
                // 与笔记/商品路径一致：部分失败即整体失败（原实现只 warn 后 break → 断点不推进也不重试，
                // 调用方却按"重建成功"收尾 → 建议索引静默残缺）
                throw new IllegalStateException("建议索引批次部分失败: 成功=" + indexed + "/" + notes.size());
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
                long likeCount = note.get("like_count") != null ? ((Number) note.get("like_count")).longValue() : 0L;
                Map<String, Object> doc = buildSuggestDoc(title, likeCount);
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
    private Map<String, Object> buildSuggestDoc(String title, long likeCount) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("keyword", title);
        // 权重 1~101：log10 缩放，避免头部笔记权重碾压（100 赞≈21，1 万赞≈41）
        int weight = 1 + (int) Math.min(100, Math.round(Math.log10(1 + Math.max(0, likeCount)) * 10));
        doc.put("weight", weight);
        return doc;
    }
}
