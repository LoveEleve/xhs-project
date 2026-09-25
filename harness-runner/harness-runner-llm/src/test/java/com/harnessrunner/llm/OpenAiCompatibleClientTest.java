package com.harnessrunner.llm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleClientTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private LlmConfig config() {
        return new LlmConfig(baseUrl, "test-key", "test-model", Duration.ofSeconds(5));
    }

    @Test
    void sendsOpenAiCompatibleRequestAndParsesResponse() {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        String content = "{\\\"summary\\\":\\\"s\\\",\\\"ac\\\":[]}";
        respond(200, """
                {"model":"test-model","choices":[{"message":{"content":"%s"}}],
                 "usage":{"prompt_tokens":11,"completion_tokens":7}}
                """.formatted(content), authorization, requestBody);

        LlmResponse response = new OpenAiCompatibleClient(config()).complete("系统提示", "用户提示");

        assertEquals("{\"summary\":\"s\",\"ac\":[]}", response.content());
        assertEquals("test-model", response.model());
        assertEquals(11, response.promptTokens());
        assertEquals(7, response.completionTokens());
        assertEquals("Bearer test-key", authorization.get());
        assertTrue(requestBody.get().contains("\"role\":\"system\""), requestBody.get());
        assertTrue(requestBody.get().contains("\"response_format\""), requestBody.get());
        assertTrue(requestBody.get().contains("用户提示"), requestBody.get());
    }

    @Test
    void httpErrorBecomesLlmException() {
        respond(500, "{\"error\":\"boom\"}", new AtomicReference<>(), new AtomicReference<>());

        LlmException exception = assertThrows(LlmException.class,
                () -> new OpenAiCompatibleClient(config()).complete("s", "u"));

        assertTrue(exception.getMessage().contains("500"), exception.getMessage());
    }

    @Test
    void missingContentBecomesLlmException() {
        respond(200, "{\"model\":\"m\",\"choices\":[]}", new AtomicReference<>(), new AtomicReference<>());

        LlmException exception = assertThrows(LlmException.class,
                () -> new OpenAiCompatibleClient(config()).complete("s", "u"));

        assertTrue(exception.getMessage().contains("content"), exception.getMessage());
    }

    private void respond(int status, String body,
                         AtomicReference<String> authorization, AtomicReference<String> requestBody) {
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }
}
