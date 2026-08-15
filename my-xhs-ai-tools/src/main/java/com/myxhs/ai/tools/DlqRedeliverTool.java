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
import java.util.Map;

/**
 * MQ 死信重投执行器（M11 HITL，受控执行）：
 * 命令模板写死（无 shell/拼接）：POST {baseUrl}/redeliver?msgId=..&group=..，
 * 参数白名单（msgId 32hex / consumerGroup 字母数字），baseUrl 配置化（未配置→ERROR 不执行）。
 * 结果如实回填（含服务端返回/失败原因）；不假装成功。
 */
public class DlqRedeliverTool implements DlqRedeliverAccess {

    private final HttpClient http;
    private final ObjectMapper om;
    private final String baseUrl;

    public DlqRedeliverTool(String baseUrl) {
        this(baseUrl, new ObjectMapper());
    }

    public DlqRedeliverTool(String baseUrl, ObjectMapper om) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? null : baseUrl;
        this.om = om;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
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
            // 命令模板写死：固定路径 + 白名单参数 URL 编码（无 shell/拼接）
            String url = baseUrl + "/redeliver?msgId=" + enc(msgId) + "&group=" + enc(consumerGroup);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            ObjectNode ok = om.createObjectNode();
            ok.put("status", "ok");
            ok.put("tool", "dlq.redeliver");
            ok.put("msgId", msgId);
            ok.put("consumerGroup", consumerGroup);
            ok.put("httpStatus", resp.statusCode());
            String body = resp.body() == null ? "" : resp.body();
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                ok.put("result", body.length() > 1000 ? body.substring(0, 1000) + "…" : body);
            } else {
                ok.put("status", "error");
                ok.put("message", "管理端点返回 " + resp.statusCode() + ": "
                        + (body.length() > 300 ? body.substring(0, 300) : body));
            }
            return ok.toString();
        } catch (Exception e) {
            return node.put("message", "调用管理端点失败: " + e.getMessage()).toString();
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
