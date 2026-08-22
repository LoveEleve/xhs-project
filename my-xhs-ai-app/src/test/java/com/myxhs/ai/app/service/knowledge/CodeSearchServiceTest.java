package com.myxhs.ai.app.service.knowledge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeSearchServiceTest {

    private final CodeSearchService service = new CodeSearchService();

    @Test
    void 库存预扣问题_优先返回InventoryService() {
        String answer = service.search("哪个类负责库存预扣？");
        assertNotNull(answer);
        assertTrue(answer.contains("InventoryService"), answer);
        assertTrue(answer.indexOf("服务：inventory") >= 0, answer);
        int inventoryPos = answer.indexOf("服务：inventory");
        int orderPos = answer.indexOf("服务：order");
        assertTrue(orderPos < 0 || inventoryPos < orderPos, answer);
        assertTrue(answer.contains("方法级 blame："), answer);

        CodeSearchResult structured = service.searchStructured("哪个类负责库存预扣？最近谁改过？");
        assertNotNull(structured);
        assertNotNull(structured.topHit());
        assertTrue(structured.topHit().blameSummary() != null && !structured.topHit().blameSummary().isBlank(), String.valueOf(structured.topHit()));
        assertTrue(structured.topHit().changeExplanation() != null && !structured.topHit().changeExplanation().isBlank(), String.valueOf(structured.topHit()));
        assertTrue(structured.topHit().diagnosisTriplet() != null && !structured.topHit().diagnosisTriplet().isBlank(), String.valueOf(structured.topHit()));
        assertTrue(structured.topHit().methodBlameSummary() != null && !structured.topHit().methodBlameSummary().isBlank(), String.valueOf(structured.topHit()));
        assertTrue(structured.topHit().methodSnippet() != null && !structured.topHit().methodSnippet().isBlank(), String.valueOf(structured.topHit()));
        assertTrue(structured.topHit().methodSnippet().contains("preDeduct(PreDeductRequest request)"), String.valueOf(structured.topHit()));
        assertTrue(structured.topHit().relatedTraceSamples() != null && !structured.topHit().relatedTraceSamples().isEmpty(), String.valueOf(structured.topHit()));
        assertTrue(structured.topHit().relatedTraceSamples().size() <= 3, String.valueOf(structured.topHit()));
        assertTrue(structured.topHit().relatedTraceSamples().get(0).traceId().equals("5304dc5a8afb4741b8bc74cee49c3980"), String.valueOf(structured.topHit()));
    }

    @Test
    void 支付链路问题_能优先命中PaymentFeignClient() {
        String answer = service.search("order 到 payment 的 Feign 客户端是哪一个？");
        assertNotNull(answer);
        assertTrue(answer.contains("PaymentFeignClient"), answer);
        assertTrue(answer.contains("order -> payment"), answer);
    }

    @Test
    void topic消费问题_能命中OrderTransactionConsumer() {
        String answer = service.search("哪个 consumer 消费 ORDER_TRANSACTION_TOPIC？");
        assertNotNull(answer);
        assertTrue(answer.contains("OrderTransactionConsumer"), answer);
        assertTrue(answer.contains("ORDER_TRANSACTION_TOPIC") || answer.contains("order-transaction-topic"), answer);
    }

    @Test
    void followup问题_能命中现有codeMap主路径() {
        String answer = service.search("为什么优先看 InventoryService.preDeduct()？");
        assertNotNull(answer);
        assertTrue(answer.contains("InventoryService"), answer);
    }

    @Test
    void 无匹配问题_返回null() {
        var r = service.search("今天天气怎么样");
        assertTrue(r == null || r.isBlank());
    }
}
