package com.myxhs.ai.tools;

import java.util.regex.Pattern;

/**
 * 工具参数校验（M12：从 PolicyGuard 迁移的静态校验，app 与 mcp 复用）。
 * 规则单一事实源：window = MetricWindow；hours/keyword/tailLines/group 在此。
 */
public class ToolParamValidators {

    /** 组名标签过滤白名单（防 PromQL 注入） */
    private static final Pattern GROUP_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");

    private ToolParamValidators() {
    }

    /** 返回错误消息，null=合法（规则单一事实源 MetricWindow） */
    public static String validateWindow(String window) {
        try {
            MetricWindow.parse(window);
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /** hours 校验：1~168 整数 */
    public static String validateHours(String hours) {
        if (hours == null || hours.isBlank()) {
            return "hours 必填（最近小时数 1~168）";
        }
        try {
            int h = Integer.parseInt(hours.trim());
            if (h < 1 || h > 168) {
                return "hours 必须在 1~168 之间";
            }
            return null;
        } catch (NumberFormatException e) {
            return "hours 必须为整数";
        }
    }

    /** 组名白名单（字母数字下划线连字符；空=全部，合法） */
    public static String validateGroup(String group) {
        if (group == null || group.isBlank()) {
            return null;
        }
        return GROUP_PATTERN.matcher(group.trim()).matches()
                ? null : "组名仅允许字母数字下划线连字符";
    }

    /** 受控日志检索 keyword 校验：委托 DirectLogSearchAccess（单一事实源——复制即漂移，P0 教训）。
     *  原 PolicyGuard 亦委托同一实现，规则变化只改一处。 */
    public static String validateKeyword(String keyword) {
        return DirectLogSearchAccess.validateKeyword(keyword);
    }

    /** tailLines 解析：委托 DirectLogSearchAccess（clamp 到 1~5000，非法回退默认 500） */
    public static int parseTailLines(String tailLines) {
        return DirectLogSearchAccess.parseTailLines(tailLines);
    }

    /** dlq.redeliver：msgId 必须 32 位 hex（RocketMQ 消息 ID 格式） */
    public static String validateMsgId(String msgId) {
        if (msgId == null || !msgId.matches("[0-9a-fA-F]{32}")) {
            return "msgId 必须为 32 位十六进制消息 ID";
        }
        return null;
    }

    /** dlq.redeliver：consumerGroup 白名单（同 MQ 组名校验） */
    public static String validateConsumerGroup(String consumerGroup) {
        if (consumerGroup == null || consumerGroup.isBlank()) {
            return "consumerGroup 必填";
        }
        return validateGroup(consumerGroup);
    }

    public static String validateRetryTopic(String retryTopic) {
        if (retryTopic == null || retryTopic.isBlank()) {
            return null;
        }
        if (!retryTopic.matches("[A-Za-z0-9_.%\\-]{1,255}")) {
            return "retryTopic 含非法字符";
        }
        return null;
    }
}
