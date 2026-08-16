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
 *     ?msgId=<msgId>&consumerGroup=<group>&topic=%RETRY%<group>&clientId=
 *  3. 批量重投备用：POST {base}/dlqMessage/batchResendDlqMessage.do
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
            HttpResponse<String> csrf = http.send(HttpRequest.newBuilder(
                            URI.create(baseUrl + "/rocketmq-dashboard/csrf-token"))
                    .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_S)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            String cookies = extractCookies(csrf.headers().allValues("Set-Cookie"));
            String token = parseToken(csrf.body());
            if (token == null) {
                return node.put("message", "管理通道会话初始化失败（csrf 响应: HTTP " + csrf.statusCode() + "）")
                        .toString();
            }
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
            if (resp.statusCode() >= 200 && resp.statusCode() < 300 && !body.isBlank()
                    && body.contains("\"status\":0")) {
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

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
