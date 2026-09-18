package com.myxhs.ai.model;

import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CT（契约测试）：LLM 外部系统契约 —— fixture 录制/回放 + 异常面。
 *
 * <p>覆盖（对照测试矩阵 TC-CT-LLM-01..04）：
 * <ul>
 *   <li>200 正常：fixture 回放 → 解析出文本内容</li>
 *   <li>429：限流响应 → 异常上抛（不吞）</li>
 *   <li>500：服务端错误 → 异常上抛</li>
 *   <li>连接中断：传输层错误被网关识别为可重试 → 第二次成功（重试契约）</li>
 * </ul>
 */
class ModelContractTest {

    private static HttpServer server;
    private static int port;
    private static final AtomicInteger calls = new AtomicInteger();
    private static volatile String mode = "ok";
    private static String okBody;

    @BeforeAll
    static void start() throws Exception {
        okBody = Files.readString(Path.of("src/test/resources/contract/llm-chat-completion.json"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/v1/chat/completions", ex -> {
            int n = calls.incrementAndGet();
            try {
                if ("reset_once".equals(mode) && n == 1) {
                    ex.close();
                    return;
                }
                int code = "429".equals(mode) ? 429 : "500".equals(mode) ? 500 : 200;
                String body = "429".equals(mode)
                        ? "{\"error\":{\"message\":\"rate limited\",\"type\":\"rate_limit_exceeded\"}}"
                        : "500".equals(mode)
                        ? "{\"error\":{\"message\":\"internal server error\"}}"
                        : okBody;
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(code, bytes.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (Exception ignored) {
            }
        });
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @BeforeEach
    void reset() {
        calls.set(0);
        mode = "ok";
    }

    private OpenAIChatModel model() {
        return OpenAIChatModel.builder()
                .baseUrl("http://127.0.0.1:" + port + "/v1")
                .apiKey("test-key")
                .modelName("contract-stub")
                .stream(false)
                .build();
    }

    private List<Msg> msgs() {
        return List.of(new UserMessage("hi"));
    }

    private GenerateOptions options() {
        return GenerateOptions.builder().build();
    }

    @Test
    void normalResponseParsedFromFixture() {
        List<ChatResponse> responses = model().stream(msgs(), List.of(), options()).collectList().block();
        assertNotNull(responses);
        assertFalse(responses.isEmpty(), "应有响应");
        StringBuilder text = new StringBuilder();
        for (ChatResponse r : responses) {
            for (ContentBlock b : r.getContent()) {
                if (b instanceof TextBlock tb) {
                    text.append(tb.getText());
                }
            }
        }
        assertTrue(text.toString().contains("pong"), "fixture 内容应被解析: " + text);
        assertTrue(calls.get() >= 1, "应发生至少一次请求");
    }

    @Test
    void rateLimitedSurfaced() {
        mode = "429";
        assertThrows(Exception.class, () ->
                model().stream(msgs(), List.of(), options()).collectList().block());
    }

    @Test
    void serverErrorSurfaced() {
        mode = "500";
        assertThrows(Exception.class, () ->
                model().stream(msgs(), List.of(), options()).collectList().block());
    }

    @Test
    void connectionResetIsRetriedByGateway() {
        mode = "reset_once";
        ModelGateway gateway = new ModelGateway(model(), model(),
                org.mockito.Mockito.mock(TokenBudgetService.class), new SimpleMeterRegistry(),
                2, 10, 3, 1000);
        List<ChatResponse> responses = gateway.stream(msgs(), List.of(), options()).collectList().block();
        assertNotNull(responses);
        assertFalse(responses.isEmpty(), "重试后应成功");
        assertTrue(calls.get() >= 2, "连接中断应触发传输重试, 实际请求数=" + calls.get());
    }
}
