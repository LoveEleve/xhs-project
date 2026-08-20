package com.myxhs.search.consumer;

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
import java.util.Map;

/**
 * 点赞/取消点赞事件消费者 — 同步 ES note_index 的 likeCount
 * <p>
 * T-092（2026-08-16）：原实现 ES likeCount 仅在 canal 触发笔记变更时快照，
 * 点赞/取消本身不触发 canal → hot 排序用陈旧/恒 0 的 likeCount → 退化为 noteId 降序。
 * 修复：监听 SOCIAL_TOPIC:LIKE/UNLIKE，读 counter 权威 key（Set-based SCARD 已写）后
 * partial update ES 文档 likeCount。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "SOCIAL_TOPIC",
        selectorExpression = "LIKE||UNLIKE",
        consumerGroup = "counter-es-sync-consumer-group",
        maxReconsumeTimes = 3
)
public class LikeCountSyncConsumer implements RocketMQListener<MessageExt> {

    private final StringRedisTemplate stringRedisTemplate;
    private final co.elastic.clients.elasticsearch.ElasticsearchClient esClient;

    @org.springframework.beans.factory.annotation.Value("${search.note.index-name:note_index}")
    private String noteIndexName;

    @Override
    public void onMessage(MessageExt msg) {
        MqTraceHelper.restoreTraceId(msg);
        try {
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            JSONObject event = JSONObject.parseObject(body);
            Integer bizType = event.getInteger("bizType");
            Long bizId = event.getLong("bizId");
            if (bizType == null || bizId == null || bizType != 1) {
                // 仅笔记点赞（bizType=1）影响 note_index；评论点赞（bizType=2）与笔记文档无关
                return;
            }
            // 读 analytics 权威 Set SCARD（myxhs:like:note:{noteId}）——
            // T-092 修正（2026-08-16）：原读 counter key（myxhs:counter:1:{id}:1），但 search 与 counter
            // 并行消费同一 LIKE 消息，counter 服务写入前 search 读到旧值（实测 likeCount 更新为 0）。
            // analytics 的 SADD 在 like 接口同步完成（HTTP 响应前已写），无消费时序竞争。
            String likeSetKey = "myxhs:like:note:" + bizId;
            Long likeSetSize = stringRedisTemplate.opsForSet().size(likeSetKey);
            final long likeCount = likeSetSize != null ? likeSetSize : 0L;

            // partial update（不重写全量文档，避免与 canal ExternalGte 版本域冲突）
            try {
                esClient.update(u -> u
                                .index(noteIndexName)
                                .id(String.valueOf(bizId))
                                .doc(Map.of("likeCount", likeCount)),
                        Map.class);
                log.info("[点赞计数同步] ES likeCount 更新: noteId={}, likeCount={}", bizId, likeCount);
            } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException e) {
                if (e.status() == 404) {
                    log.debug("[点赞计数同步] 笔记不存在于 ES，跳过: noteId={}", bizId);
                } else {
                    throw e;
                }
            }
        } catch (Exception e) {
            log.error("[点赞计数同步] 处理失败: msgId={}", msg.getMsgId(), e);
            throw new RuntimeException("点赞计数同步失败", e);
        } finally {
            MqTraceHelper.clearTraceId();
        }
    }
}
