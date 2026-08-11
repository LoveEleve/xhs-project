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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 笔记索引同步消费者
 * <p>
 * 消费 Canal 发送的笔记变更消息（通过 RocketMQ），同步到 ES。
 * 消息来源：Canal 监听 MySQL my_xhs_content.t_note 表的 Binlog → RocketMQ NOTE_INDEX_TOPIC
 * </p>
 * <p>
 * Canal 原始消息格式：
 * {
 *   "database": "my_xhs_content",
 *   "table": "t_note",
 *   "type": "INSERT|UPDATE|DELETE",
 *   "data": [{"id": "123", "title": "标题", ...}],
 *   "old": [{"title": "旧标题"}],  // UPDATE 时才有
 *   "ts": 1715510539000
 * }
 * </p>
 * <p>
 * 幂等性：ES IndexRequest 使用 noteId 作为文档 ID，
 * 重复写入只会覆盖（upsert 语义），天然幂等。
 * </p>
 * <p>
 * 防乱序：使用 Canal 消息中的 Binlog 时间戳作为 ES external version，
 * 旧版本写入会被 ES 拒绝，保证最终一致性。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "NOTE_INDEX_TOPIC",
        consumerGroup = "note-index-sync-consumer-group",
        maxReconsumeTimes = 3
)
public class NoteIndexSyncConsumer implements RocketMQListener<MessageExt> {

    private final ElasticsearchClient esClient;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 追踪当前正在处理的 noteId，用于在 catch 块中记录失败的 docId 到 Redis。
     * RocketMQ 消费者默认单线程消费，ThreadLocal 安全。
     */
    private final ThreadLocal<Long> currentNoteId = new ThreadLocal<>();

    @Value("${search.note.index-name:note_index}")
    private String noteIndexName;

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            JSONObject canalMsg = JSON.parseObject(body);

            if (canalMsg == null) {
                log.error("[笔记索引同步] 消息体为空，跳过: msgId={}", msg.getMsgId());
                return;
            }

            // 兼容两种消息格式：Canal 原始格式 和 应用层直接发送的扁平格式
            if (canalMsg.containsKey("database") && canalMsg.containsKey("data")) {
                // Canal 原始格式
                handleCanalMessage(canalMsg, msg.getMsgId());
            } else if (canalMsg.containsKey("noteId") && canalMsg.containsKey("type")) {
                // 应用层扁平格式（兼容旧消息 / 手动触发）
                handleFlatMessage(canalMsg, msg.getMsgId());
            } else {
                log.error("[笔记索引同步] 消息格式无法识别，跳过: msgId={}, body={}", msg.getMsgId(), body);
            }

        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException
                 | java.io.IOException e) {
            Long noteId = currentNoteId.get();
            log.error("[笔记索引同步] ES 通信异常，触发重试: noteId={}, msgId={}, reconsumeTimes={}",
                    noteId, msg.getMsgId(), msg.getReconsumeTimes(), e);
            // 记录失败 noteId 到 Redis Set，供 IncrementalIndexSyncJob 增量补偿
            if (noteId != null && stringRedisTemplate != null) {
                try {
                    stringRedisTemplate.opsForSet().add("myxhs:es:sync:failed:note", String.valueOf(noteId));
                    stringRedisTemplate.expire("myxhs:es:sync:failed:note", Duration.ofHours(1));
                } catch (Exception redisEx) {
                    log.warn("[笔记索引同步] 记录失败 noteId 到 Redis 失败: noteId={}", noteId, redisEx);
                }
            }
            throw new RuntimeException("笔记索引同步失败（可重试）", e);
        } catch (Exception e) {
            log.error("[笔记索引同步] 不可重试异常，跳过: msgId={}", msg.getMsgId(), e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }

    /**
     * 处理 Canal 原始格式消息
     * <p>
     * Canal 消息的 data 是数组（一次 Binlog 事件可能包含多行变更），
     * 需要遍历每一行分别处理。
     * </p>
     */
    private void handleCanalMessage(JSONObject canalMsg, String msgId) throws Exception {
        String type = canalMsg.getString("type");
        JSONArray dataArray = canalMsg.getJSONArray("data");
        // P1-4：统一版本域为毫秒时间戳(ts)，与增量补偿(IncrementalIndexSyncJob 用 updated_at 毫秒)一致。
        // 原实现优先用 es(Canal 全局事件序列,小整数)——一旦补偿任务用 currentTimeMillis(~1.7e12) 写过该文档，
        // 后续 Canal 的 es(小整数) 会被 ES ExternalGte 永久拒绝，索引冻结在补偿快照。
        long version = canalMsg.getLongValue("ts", System.currentTimeMillis());
        if (version == 0) {
            version = canalMsg.getLongValue("es", System.currentTimeMillis());
        }

        if (dataArray == null || dataArray.isEmpty()) {
            log.warn("[笔记索引同步] Canal 消息 data 为空: msgId={}", msgId);
            return;
        }

        for (int i = 0; i < dataArray.size(); i++) {
            JSONObject row = dataArray.getJSONObject(i);
            Long noteId = row.getLong("id");
            if (noteId == null) {
                log.warn("[笔记索引同步] 行数据缺少 id 字段，跳过: msgId={}", msgId);
                continue;
            }

            switch (type) {
                case "INSERT", "UPDATE" -> {
                    currentNoteId.set(noteId);
                    indexNoteFromCanal(noteId, row, version);
                }
                case "DELETE" -> {
                    currentNoteId.set(noteId);
                    deleteNote(noteId);
                }
                default -> log.debug("[笔记索引同步] 忽略事件类型: type={}", type);
            }
        }
    }

    /**
     * 处理应用层扁平格式消息（兼容旧格式 / 手动触发同步）
     */
    private void handleFlatMessage(JSONObject event, String msgId) throws Exception {
        String type = event.getString("type");
        Long noteId = event.getLong("noteId");
        if (noteId == null) {
            log.error("[笔记索引同步] noteId 为空，跳过: msgId={}", msgId);
            return;
        }

        switch (type) {
            case "INSERT", "UPDATE" -> {
                currentNoteId.set(noteId);
                indexNoteFromFlat(noteId, event);
            }
            case "DELETE" -> {
                currentNoteId.set(noteId);
                deleteNote(noteId);
            }
            default -> log.warn("[笔记索引同步] 未知事件类型: type={}, noteId={}", type, noteId);
        }
    }

    /**
     * 从 Canal 行数据构建 ES 文档并索引
     * <p>
     * Canal 的字段名是 MySQL 列名（下划线风格），需要转换为 ES 文档字段名（驼峰风格）。
     * 使用 Canal 消息的 es（event sequence）字段作为 external version 防乱序。
     * </p>
     * <p>
     * 为什么用 es 而不是 ts？
     * ts 是毫秒级时间戳，同一毫秒内多次变更会导致 version 相同被 ES 拒绝。
     * es 是 Canal 内部的事件序列号，严格递增，不会重复。
     * 如果 es 不可用，降级使用 ts（兼容旧版本 Canal）。
     * </p>
     */
    private void indexNoteFromCanal(Long noteId, JSONObject row, long version) throws Exception {
        Map<String, Object> doc = new HashMap<>();
        doc.put("noteId", noteId);
        doc.put("userId", row.getLong("user_id"));
        doc.put("title", row.getString("title"));
        doc.put("content", row.getString("content"));
        doc.put("coverImage", row.getString("cover_url"));
        // 注意：t_note 表中没有 like_count/collect_count/comment_count 字段
        // 这些计数由计数器服务维护，全量重建时从计数器服务获取
        // Canal 增量同步时不覆盖这些字段（使用 ES partial update 或忽略）
        doc.put("status", row.getIntValue("status", 1));
        doc.put("createdAt", row.getString("created_at"));

        String jsonDoc = JSON.toJSONString(doc);

        esClient.index(IndexRequest.of(idx -> idx
                .index(noteIndexName)
                .id(String.valueOf(noteId))
                .versionType(co.elastic.clients.elasticsearch._types.VersionType.ExternalGte)
                .version(version)
                .withJson(new StringReader(jsonDoc))));

        log.info("[笔记索引同步] Canal 索引成功: noteId={}, version={}", noteId, version);
    }

    /**
     * 从扁平格式消息构建 ES 文档并索引
     */
    private void indexNoteFromFlat(Long noteId, JSONObject event) throws Exception {
        Map<String, Object> doc = new HashMap<>();
        doc.put("noteId", noteId);
        doc.put("userId", event.get("userId"));
        doc.put("title", event.get("title"));
        doc.put("content", event.get("content"));
        doc.put("coverImage", event.get("coverImage"));
        doc.put("likeCount", event.getOrDefault("likeCount", 0));
        doc.put("collectCount", event.getOrDefault("collectCount", 0));
        doc.put("commentCount", event.getOrDefault("commentCount", 0));
        doc.put("status", event.getOrDefault("status", 1));
        doc.put("createdAt", event.get("createdAt"));

        String jsonDoc = JSON.toJSONString(doc);
        long version = extractVersion(event);

        esClient.index(IndexRequest.of(idx -> idx
                .index(noteIndexName)
                .id(String.valueOf(noteId))
                .versionType(co.elastic.clients.elasticsearch._types.VersionType.External)
                .version(version)
                .withJson(new StringReader(jsonDoc))));

        log.info("[笔记索引同步] 扁平格式索引成功: noteId={}, version={}", noteId, version);
    }

    private long extractVersion(JSONObject event) {
        Long es = event.getLong("es"); // Canal 全局序列号（小整数，严格递增—防版本冲突）
        if (es != null && es > 0) return es;
        Long ts = event.getLong("binlogTimestamp"); // 兼容旧格式
        if (ts != null) return ts;
        ts = event.getLong("eventTimestamp");
        if (ts != null) return ts;
        return System.currentTimeMillis();
    }

    /**
     * 删除笔记文档
     * <p>
     * 注意：直接删除文档后，如果有乱序的旧 INSERT/UPDATE 消息到达，
     * 由于文档已不存在，ES 不会拒绝旧版本写入（version check 只对已存在的文档生效）。
     * </p>
     * <p>
     * 解决方案：删除时不真正删除，而是将 status 设为 -1（标记删除），
     * 搜索时过滤 status=-1 的文档。这样 external version 机制仍然有效。
     * 真正的物理删除由全量重建任务执行（重建时不会索引 deleted=1 的记录）。
     * </p>
     */
    private void deleteNote(Long noteId) throws Exception {
        // 标记删除（而非物理删除），保留 version 信息防止乱序消息重新索引
        Map<String, Object> doc = new HashMap<>();
        doc.put("noteId", noteId);
        doc.put("status", -1); // -1 表示已删除，搜索时过滤

        String jsonDoc = JSON.toJSONString(doc);

        try {
            esClient.index(IndexRequest.of(idx -> idx
                    .index(noteIndexName)
                    .id(String.valueOf(noteId))
                    .withJson(new StringReader(jsonDoc))));
            log.info("[笔记索引同步] 标记删除成功: noteId={}", noteId);
        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException e) {
            // 文档不存在时忽略（可能从未被索引过）
            if (e.getMessage() != null && e.getMessage().contains("not_found")) {
                log.debug("[笔记索引同步] 文档不存在，跳过删除: noteId={}", noteId);
            } else {
                throw e;
            }
        }
    }
}
