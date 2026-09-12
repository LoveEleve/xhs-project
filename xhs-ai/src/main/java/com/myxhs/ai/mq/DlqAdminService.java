package com.myxhs.ai.mq;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPullConsumer;
import org.apache.rocketmq.client.consumer.PullResult;
import org.apache.rocketmq.client.consumer.PullStatus;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.remoting.protocol.admin.TopicStatsTable;
import org.apache.rocketmq.remoting.protocol.body.TopicList;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RocketMQ DLQ 管理（M2.0 竖切①：DIAG-08 / OPS-01）
 * <p>只读能力：DLQ topic 清单、消息详情（含原始 topic/msgId 属性）；
 * 变更能力：单条重投（仅由审批执行器调用，带执行后核验）。</p>
 */
@Slf4j
@Service
public class DlqAdminService {

    private static final String DLQ_PREFIX = "%DLQ%";
    private static final int BODY_TRUNCATE = 4000;

    @Value("${ai.mq.namesrv-addr:127.0.0.1:9876}")
    private String namesrvAddr;

    private final Object lock = new Object();
    private DefaultMQAdminExt adminExt;
    private DefaultMQPullConsumer reader;
    private DefaultMQProducer producer;

    private DefaultMQAdminExt admin() {
        synchronized (lock) {
            if (adminExt == null) {
                try {
                    DefaultMQAdminExt ext = new DefaultMQAdminExt("xhs-ai-admin");
                    ext.setNamesrvAddr(namesrvAddr);
                    ext.start();
                    adminExt = ext;
                    log.info("[DLQ] admin started, namesrv={}", namesrvAddr);
                } catch (Exception e) {
                    throw new IllegalStateException("RocketMQ admin 启动失败: " + e.getMessage(), e);
                }
            }
            return adminExt;
        }
    }

    private DefaultMQPullConsumer reader() {
        synchronized (lock) {
            if (reader == null) {
                try {
                    DefaultMQPullConsumer c = new DefaultMQPullConsumer("xhs-ai-dlq-reader");
                    c.setNamesrvAddr(namesrvAddr);
                    c.start();
                    reader = c;
                } catch (Exception e) {
                    throw new IllegalStateException("RocketMQ pull consumer 启动失败: " + e.getMessage(), e);
                }
            }
            return reader;
        }
    }

    private DefaultMQProducer producer() {
        synchronized (lock) {
            if (producer == null) {
                try {
                    DefaultMQProducer p = new DefaultMQProducer("xhs-ai-redeliver-producer");
                    p.setNamesrvAddr(namesrvAddr);
                    p.start();
                    producer = p;
                } catch (Exception e) {
                    throw new IllegalStateException("RocketMQ producer 启动失败: " + e.getMessage(), e);
                }
            }
            return producer;
        }
    }

    /** DLQ topic 清单（含积压量） */
    public List<Map<String, Object>> listDlqTopics() throws Exception {
        TopicList topicList = admin().fetchAllTopicList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (String topic : topicList.getTopicList()) {
            if (!topic.startsWith(DLQ_PREFIX)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("dlqTopic", topic);
            item.put("group", topic.substring(DLQ_PREFIX.length()));
            try {
                item.put("backlog", dlqBacklog(topic));
            } catch (Exception e) {
                item.put("backlog", -1);
                item.put("error", e.getMessage());
            }
            result.add(item);
        }
        result.sort(Comparator.comparing(m -> String.valueOf(m.get("group"))));
        return result;
    }

    /** 查看 DLQ 中最新一条（或指定 msgId）消息详情 */
    public Map<String, Object> messageDetail(String group, String msgId) throws Exception {
        MessageExt msg = findMessage(dlqTopic(group), msgId);
        return toDetail(msg);
    }

    /**
     * 单条重投：发送回原始 topic（优先使用 DLQ 消息自带的 origin topic 属性）
     * <p>仅由审批执行器调用；调用后由执行器做 settlement 核验。</p>
     */
    public Map<String, Object> redeliver(String group, String msgId, String originalTopicOverride) throws Exception {
        String dlqTopic = dlqTopic(group);
        MessageExt msg = findMessage(dlqTopic, msgId);
        String originTopic = originalTopicOverride;
        if (originTopic == null || originTopic.isBlank()) {
            originTopic = originTopicOf(msg);
        }
        if (originTopic == null || originTopic.isBlank()) {
            throw new IllegalArgumentException("无法从消息属性解析原始 topic，请显式传 originalTopic");
        }
        Message resend = new Message(originTopic, msg.getTags(), msg.getKeys(), msg.getBody());
        SendResult sendResult = producer().send(resend);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sendStatus", sendResult.getSendStatus().name());
        result.put("newMsgId", sendResult.getMsgId());
        result.put("originTopic", originTopic);
        result.put("sourceMsgId", msg.getMsgId());
        result.put("sentAt", System.currentTimeMillis());
        return result;
    }

    public long dlqBacklog(String groupOrTopic) throws Exception {
        String topic = groupOrTopic.startsWith(DLQ_PREFIX) ? groupOrTopic : dlqTopic(groupOrTopic);
        TopicStatsTable stats = admin().examineTopicStats(topic);
        return stats.getOffsetTable().values().stream().mapToLong(org.apache.rocketmq.remoting.protocol.admin.TopicOffset::getMaxOffset).sum();
    }

    // ---------- internal ----------

    private String dlqTopic(String group) {
        return group.startsWith(DLQ_PREFIX) ? group : DLQ_PREFIX + group;
    }

    private MessageExt findMessage(String dlqTopic, String msgId) throws Exception {
        TopicStatsTable stats = admin().examineTopicStats(dlqTopic);
        List<Map.Entry<MessageQueue, org.apache.rocketmq.remoting.protocol.admin.TopicOffset>> queues = new ArrayList<>(stats.getOffsetTable().entrySet());
        queues.sort(Comparator.comparing(e -> e.getKey().getQueueId()));
        for (Map.Entry<MessageQueue, org.apache.rocketmq.remoting.protocol.admin.TopicOffset> entry : queues) {
            long max = entry.getValue().getMaxOffset();
            for (long offset = max - 1; offset >= Math.max(0, max - 20); offset--) {
                PullResult pull = reader().pullBlockIfNotFound(entry.getKey(), null, offset, 1);
                if (pull.getPullStatus() != PullStatus.FOUND || pull.getMsgFoundList() == null
                        || pull.getMsgFoundList().isEmpty()) {
                    continue;
                }
                MessageExt candidate = pull.getMsgFoundList().get(0);
                if (msgId == null || msgId.isBlank() || msgId.equals(candidate.getMsgId())) {
                    return candidate;
                }
            }
        }
        throw new IllegalArgumentException("DLQ 中未找到消息: topic=" + dlqTopic
                + (msgId == null ? "" : ", msgId=" + msgId));
    }

    private Map<String, Object> toDetail(MessageExt msg) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("msgId", msg.getMsgId());
        detail.put("uniqKey", msg.getProperty(MessageConst.PROPERTY_UNIQ_CLIENT_MESSAGE_ID_KEYIDX));
        detail.put("originMsgId", firstNonNull(
                msg.getProperty(MessageConst.PROPERTY_DLQ_ORIGIN_MESSAGE_ID),
                msg.getProperty(MessageConst.PROPERTY_ORIGIN_MESSAGE_ID)));
        detail.put("originTopic", originTopicOf(msg));
        detail.put("tags", msg.getTags());
        detail.put("keys", msg.getKeys());
        detail.put("reconsumeTimes", msg.getReconsumeTimes());
        detail.put("bornTime", msg.getBornTimestamp());
        detail.put("storeTime", msg.getStoreTimestamp());
        String body = new String(msg.getBody(), StandardCharsets.UTF_8);
        detail.put("body", body.length() > BODY_TRUNCATE ? body.substring(0, BODY_TRUNCATE) + "...(truncated)" : body);
        return detail;
    }

    private String originTopicOf(MessageExt msg) {
        return firstNonNull(
                msg.getProperty(MessageConst.PROPERTY_DLQ_ORIGIN_TOPIC),
                msg.getProperty(MessageConst.PROPERTY_RETRY_TOPIC));
    }

    private String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    @PreDestroy
    public void destroy() {
        synchronized (lock) {
            try {
                if (adminExt != null) {
                    adminExt.shutdown();
                }
            } catch (Exception ignored) {
            }
            try {
                if (reader != null) {
                    reader.shutdown();
                }
            } catch (Exception ignored) {
            }
            try {
                if (producer != null) {
                    producer.shutdown();
                }
            } catch (Exception ignored) {
            }
        }
    }
}
