package com.myxhs.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MQ 死信重投执行器（M11 HITL 受控执行；2026-08-16 对接真实 Dashboard 契约）：
 * 目标 = RocketMQ Dashboard（管理通道，iptables 白名单内可直连）。
 * 真实契约（中间件团队核实 + 本机实测）：
 *  1. 会话初始化：GET {base}/rocketmq-dashboard/csrf-token → session cookie + csrfToken
 *     （Dashboard 无需登录；POST 必须带 X-XSRF-TOKEN 头 + session cookie）
 *  2. 按 msgId 重投：POST {base}/message/consumeMessageDirectly.do
 *     ?msgId=<ORIGIN_MESSAGE_ID>&consumerGroup=<group>&topic=%RETRY%<group>&clientId=
 *     ★ msgId 语义 = DLQ 消息的 ORIGIN_MESSAGE_ID（原始消息 ID，非 DLQ 消息自身 ID）
 *  3. 批量重投备用：POST {base}/dlqMessage/batchResendDlqMessage.do
 * 已知边界（2026-08-16 实测）：获取 ORIGIN_MESSAGE_ID 的查询接口
 *  （/dlqMessage/queryDlqMessageByConsumerGroup.query）当前版本有 NPE（UI 同踩）——
 *  **已解决**：新增 queryDlqMessages 方法，用 /message/queryMessageByTopic 查 %DLQ%<group>
 *  topic 绕过 NPE 接口，从 message.properties.ORIGIN_MESSAGE_ID 提取原始消息 ID。
 *  E2E 闭环：queryDlqMessages → 提取 ORIGIN_MESSAGE_ID → redeliver(msgId, group)。
 * 参数白名单（PolicyGuard 同规则）：msgId 32hex / consumerGroup 字母数字；
 * 每次调用新建会话（L3 低频动作，防 token 过期）；未配置通道 → ERROR 如实。
 */
public class DlqRedeliverTool implements DlqRedeliverAccess {

    private static final int CONNECT_TIMEOUT_S = 5;
    private static final int REQUEST_TIMEOUT_S = 15;

    private final HttpClient http;
    private final ObjectMapper om;
    private final String baseUrl;

    public DlqRedeliverTool(String baseUrl) {
        this(baseUrl, new ObjectMapper());
    }

    public DlqRedeliverTool(String baseUrl, ObjectMapper om) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? null : baseUrl;
        this.om = om;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_S)).build();
    }

    @Override
    public String redeliver(String msgId, String consumerGroup) {
        ObjectNode node = om.createObjectNode();
        node.put("status", "error");
        node.put("tool", "dlq.redeliver");
        String invalid = ToolParamValidators.validateMsgId(msgId);
        if (invalid != null) {
            return node.put("message", invalid).toString();
        }
        invalid = ToolParamValidators.validateConsumerGroup(consumerGroup);
        if (invalid != null) {
            return node.put("message", invalid).toString();
        }
        if (baseUrl == null) {
            return node.put("message", "管理通道未配置（myxhs.ai.hitl.dlq-redeliver.url），拒绝执行")
                    .toString();
        }
        try {
            // 1. 会话初始化：csrf token + session cookie（每次新建，防过期）
            String[] session = initSession();
            String token = session[0];
            String cookies = session[1];
            // 2. 按 msgId 重投（命令模板写死：固定路径 + 白名单参数；topic 由消费组推导 %RETRY%<group>）
            String retryTopic = "%RETRY%" + consumerGroup;
            String url = baseUrl + "/message/consumeMessageDirectly.do?msgId=" + enc(msgId)
                    + "&consumerGroup=" + enc(consumerGroup)
                    + "&topic=" + enc(retryTopic) + "&clientId=";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_S))
                    .header("X-XSRF-TOKEN", token)
                    .header("Cookie", cookies)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            ObjectNode ok = om.createObjectNode();
            ok.put("status", "ok");
            ok.put("tool", "dlq.redeliver");
            ok.put("msgId", msgId);
            ok.put("consumerGroup", consumerGroup);
            ok.put("retryTopic", retryTopic);
            ok.put("httpStatus", resp.statusCode());
            String body = resp.body() == null ? "" : resp.body();
            // JSON 精确判断 status==0（字符串 contains 可能误匹配嵌套字段，P2 修复）
            boolean success = resp.statusCode() >= 200 && resp.statusCode() < 300
                    && parseStatus(body) == 0;
            if (success) {
                ok.put("result", body.length() > 1000 ? body.substring(0, 1000) + "…" : body);
            } else {
                ok.put("status", "error");
                ok.put("message", "重投失败（HTTP " + resp.statusCode() + "）: "
                        + (body.length() > 300 ? body.substring(0, 300) : body));
            }
            return ok.toString();
        } catch (Exception e) {
            return node.put("message", "调用管理端点失败: " + e.getMessage()).toString();
        }
    }

    /** 查询 DLQ 消息列表，提取 ORIGIN_MESSAGE_ID（绕过有 NPE 的 queryDlqMessageByConsumerGroup 接口）。
     *  使用 Dashboard /message/queryMessageByTopic?topic=%DLQ%<group> 查 DLQ topic，
     *  从返回的 message.properties.ORIGIN_MESSAGE_ID 提取原始消息 ID。
     *  返回 JSON：{status, tool, consumerGroup, dlqTopic, count, messages:[{originMsgId, msgId, storeHost, queueId, queueOffset}]}
     */
    @Override
    public String queryDlqMessages(String consumerGroup) {
        ObjectNode node = om.createObjectNode();
        node.put("status", "error");
        node.put("tool", "mq.dlq_query");
        String invalid = ToolParamValidators.validateConsumerGroup(consumerGroup);
        if (invalid != null) {
            return node.put("message", invalid).toString();
        }
        if (baseUrl == null) {
            return node.put("message", "管理通道未配置（myxhs.ai.hitl.dlq-redeliver.url），拒绝执行")
                    .toString();
        }
        try {
            String[] session = initSession();
            String dlqTopic = "%DLQ%" + consumerGroup;
            String url = baseUrl + "/message/queryMessageByTopic?topic=" + enc(dlqTopic)
                    + "&begin=" + enc(System.currentTimeMillis() - 72 * 3600_000L + "")
                    + "&end=" + enc(System.currentTimeMillis() + "");
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_S))
                    .header("X-XSRF-TOKEN", session[0])
                    .header("Cookie", session[1])
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            String body = resp.body() == null ? "" : resp.body();
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return node.put("message", "DLQ 查询失败（HTTP " + resp.statusCode() + "）："
                        + (body.length() > 300 ? body.substring(0, 300) : body)).toString();
            }
            var root = om.readTree(body);
            int dashStatus = root.path("status").asInt(-999);
            if (dashStatus != 0) {
                return node.put("message", "DLQ 查询业务失败（status=" + dashStatus + "）").toString();
            }
            var messages = root.path("data").path("messages");
            if (!messages.isArray() || messages.isEmpty()) {
                node.put("status", "ok");
                node.put("consumerGroup", consumerGroup);
                node.put("dlqTopic", dlqTopic);
                node.put("count", 0);
                node.put("note", "DLQ topic 无死信消息");
                return node.toString();
            }
            var arr = om.createArrayNode();
            for (var msg : messages) {
                String originId = msg.path("properties").path("ORIGIN_MESSAGE_ID").asText(null);
                if (originId == null || originId.isBlank()) {
                    originId = msg.path("msgId").asText(null);
                }
                if (originId != null && !originId.isBlank()) {
                    var item = om.createObjectNode();
                    item.put("originMsgId", originId);
                    item.put("msgId", msg.path("msgId").asText(""));
                    item.put("storeHost", msg.path("storeHost").asText(""));
                    item.put("queueId", msg.path("queueId").asText(""));
                    item.put("queueOffset", msg.path("queueOffset").asText(""));
                    arr.add(item);
                }
            }
            node.put("status", "ok");
            node.put("consumerGroup", consumerGroup);
            node.put("dlqTopic", dlqTopic);
            node.put("count", arr.size());
            node.set("messages", arr);
            return node.toString();
        } catch (Exception e) {
            return node.put("message", "DLQ 查询失败: " + e.getMessage()).toString();
        }
    }

    /** 初始化 Dashboard 会话：返回 {csrfToken, cookie} */
    private String[] initSession() throws Exception {
        HttpResponse<String> csrf = http.send(HttpRequest.newBuilder(
                        URI.create(baseUrl + "/rocketmq-dashboard/csrf-token"))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_S)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String cookies = extractCookies(csrf.headers().allValues("Set-Cookie"));
        String token = parseToken(csrf.body());
        if (token == null) {
            throw new RuntimeException("管理通道会话初始化失败（csrf 响应: HTTP " + csrf.statusCode() + "）");
        }
        return new String[]{token, cookies};
    }

    /** Set-Cookie 头 → "name=value; name=value"（取分号前的主 cookie 段） */
    private static String extractCookies(List<String> setCookieHeaders) {
        return setCookieHeaders.stream()
                .map(h -> h.split(";")[0].trim())
                .collect(Collectors.joining("; "));
    }

    private String parseToken(String csrfBody) {
        try {
            return om.readTree(csrfBody).path("data").path("token").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Dashboard 业务状态码：JSON path("status")；解析失败返回 -999（非 0=失败语义） */
    private int parseStatus(String body) {
        try {
            return om.readTree(body).path("status").asInt(-999);
        } catch (Exception e) {
            return -999;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
