package com.myxhs.ai.model;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ModelGateway：重试 / 降级 / 熔断（D01）
 */
class ModelGatewayTest {

    private static final List<Msg> MSGS = List.of(new io.agentscope.core.message.UserMessage("hi"));

    private static class FakeModel implements Model {
        final String name;
        final List<Flux<ChatResponse>> outcomes;
        final AtomicInteger calls = new AtomicInteger();

        FakeModel(String name, List<Flux<ChatResponse>> outcomes) {
            this.name = name;
            this.outcomes = outcomes;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            int index = calls.getAndIncrement();
            return outcomes.get(Math.min(index, outcomes.size() - 1));
        }

        @Override
        public String getModelName() {
            return name;
        }
    }

    private ChatResponse ok() {
        return ChatResponse.builder().id("r1").finishReason("stop").build();
    }

    private Flux<ChatResponse> transportError() {
        return Flux.error(new IOException("Connection reset"));
    }

    private Flux<ChatResponse> socketError() {
        return Flux.error(new SocketException("Connection reset"));
    }

    @Test
    void retriesTransportErrorThenSucceeds() {
        FakeModel primary = new FakeModel("p", List.of(transportError(), Flux.just(ok())));
        FakeModel fallback = new FakeModel("f", List.of(Flux.just(ok())));
        ModelGateway gateway = new ModelGateway(primary, fallback, org.mockito.Mockito.mock(TokenBudgetService.class), new SimpleMeterRegistry(), 2, 10, 3, 1000);

        ChatResponse response = gateway.stream(MSGS, List.of(), null).blockLast();

        assertEquals("r1", response.getId());
        assertEquals(2, primary.calls.get());
        assertEquals(0, fallback.calls.get());
    }

    @Test
    void fallsBackWhenPrimaryExhausted() {
        FakeModel primary = new FakeModel("p", List.of(socketError()));
        FakeModel fallback = new FakeModel("f", List.of(Flux.just(ok())));
        ModelGateway gateway = new ModelGateway(primary, fallback, org.mockito.Mockito.mock(TokenBudgetService.class), new SimpleMeterRegistry(), 2, 10, 3, 1000);

        ChatResponse response = gateway.stream(MSGS, List.of(), null).blockLast();

        assertEquals("r1", response.getId());
        assertEquals(2, primary.calls.get());
        assertTrue(fallback.calls.get() >= 1);
    }

    @Test
    void opensBreakerAfterConsecutiveFailures() {
        FakeModel primary = new FakeModel("p", List.of(socketError()));
        FakeModel fallback = new FakeModel("f", List.of(Flux.just(ok())));
        ModelGateway gateway = new ModelGateway(primary, fallback, org.mockito.Mockito.mock(TokenBudgetService.class), new SimpleMeterRegistry(), 1, 10, 2, 60000);

        gateway.stream(MSGS, List.of(), null).blockLast();
        gateway.stream(MSGS, List.of(), null).blockLast();
        int callsBeforeBreaker = primary.calls.get();

        gateway.stream(MSGS, List.of(), null).blockLast();

        assertEquals(callsBeforeBreaker, primary.calls.get(), "熔断开启后不应再调用主通道");
        assertTrue(fallback.calls.get() >= 3);
    }

    @Test
    void propagatesWhenBothChannelsFail() {
        FakeModel primary = new FakeModel("p", List.of(socketError()));
        FakeModel fallback = new FakeModel("f", List.of(socketError()));
        ModelGateway gateway = new ModelGateway(primary, fallback, org.mockito.Mockito.mock(TokenBudgetService.class), new SimpleMeterRegistry(), 1, 10, 5, 1000);

        assertThrows(ModelGateway.ModelUnavailableException.class,
                () -> gateway.stream(MSGS, List.of(), null).blockLast());
    }

    @Test
    void doesNotRetryAfterPartialStreamEmitted() {
        ChatResponse partial = ok();
        FakeModel primary = new FakeModel("p",
                List.of(Flux.concat(Flux.just(partial), transportError())));
        FakeModel fallback = new FakeModel("f", List.of(Flux.just(ok())));
        ModelGateway gateway = new ModelGateway(primary, fallback, org.mockito.Mockito.mock(TokenBudgetService.class), new SimpleMeterRegistry(), 3, 10, 3, 1000);

        // 已出流后的失败：不重试、不降级重放，直接报错
        assertThrows(ModelGateway.ModelUnavailableException.class,
                () -> gateway.stream(MSGS, List.of(), null).blockLast());
        assertEquals(1, primary.calls.get());
        assertEquals(0, fallback.calls.get());
    }

    @Test
    void breakerResetsCountersOnPrimarySuccess() {
        FakeModel primary = new FakeModel("p", List.of(Flux.just(ok()), socketError(), socketError(), Flux.just(ok())));
        FakeModel fallback = new FakeModel("f", List.of(Flux.just(ok())));
        ModelGateway gateway = new ModelGateway(primary, fallback, org.mockito.Mockito.mock(TokenBudgetService.class), new SimpleMeterRegistry(), 1, 10, 3, 60000);
        gateway.stream(MSGS, List.of(), null).blockLast();   // success -> reset
        gateway.stream(MSGS, List.of(), null).blockLast();   // fail 1
        gateway.stream(MSGS, List.of(), null).blockLast();   // fail 2 (threshold 3 not reached)
        int callsBefore = primary.calls.get();
        gateway.stream(MSGS, List.of(), null).blockLast();   // 主通道仍可用（未被熔断）
        assertEquals(callsBefore + 1, primary.calls.get());
    }
}
