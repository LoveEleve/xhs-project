package com.myxhs.ai.tools;

/**
 * MQ 死信重投访问接口（M11 HITL：第一个真实 L3 执行类工具）。
 * 命令模板写死 + 参数白名单 + 配置化管理通道（沿用 log.search 安全模型）：
 *  - msgId 32 位 hex、consumerGroup 字母数字白名单（ToolParamValidators 同规则）
 *  - HTTP POST {baseUrl}/redeliver?msgId=..&group=..（模板写死，无 shell/拼接）
 *  - baseUrl 未配置 → ERROR 如实（不假装执行）
 */
public interface DlqRedeliverAccess {

    String redeliver(String msgId, String consumerGroup);

    default String redeliver(String msgId, String consumerGroup, String retryTopic) {
        return redeliver(msgId, consumerGroup);
    }

    String queryDlqMessages(String consumerGroup);

    default String queryDlqMessages(String consumerGroup, String keyword) {
        return queryDlqMessages(consumerGroup);
    }
}
