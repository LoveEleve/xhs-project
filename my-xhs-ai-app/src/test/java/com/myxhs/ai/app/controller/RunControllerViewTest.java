package com.myxhs.ai.app.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myxhs.ai.app.service.agent.harness.AgentBudget;
import com.myxhs.ai.app.service.agent.harness.AgentRun;
import com.myxhs.ai.app.service.agent.harness.TerminationReason;
import com.myxhs.ai.app.service.knowledge.CodeSearchResult;
import com.myxhs.ai.app.service.trace.TraceDiagnosisResult;
import com.myxhs.ai.app.service.trace.TraceSearchResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RunControllerViewTest {

    @Test
    @SuppressWarnings("unchecked")
    void view_包含traceDiagnosis结构() throws Exception {
        AgentRun run = new AgentRun("run_1", "trace", AgentBudget.defaults());
        run.terminate(TerminationReason.COMPLETED, "done");
        TraceDiagnosisResult diagnosis = new TraceDiagnosisResult(
                "trace-id",
                "remote-es",
                "uncertain",
                "hypothesis_only",
                "bounded-reviewer-v1",
                "需要更多证据",
                List.of("suspicious"),
                List.of("hypothesis"),
                List.of("next"),
                List.of(new TraceDiagnosisResult.ServiceProfile(
                        "my-xhs-order", "交易链路", "交易主链同步编排中心",
                        List.of("OrderService"), List.of("OrderController"), List.of("OrderCompensationConsumer"),
                        "my-xhs-order/src/main/java/com/myxhs/order/service/OrderService.java",
                        new TraceDiagnosisResult.Owner("LoveEleve", "120724735+LoveEleve@users.noreply.github.com", "1dcd5c8", "chore: sync pending project changes"),
                        List.of("1dcd5c8 chore: sync pending project changes"),
                        List.of("发起支付、查询支付状态、触发退款链"),
                        "OrderController", "my-xhs-order/src/main/java/com/myxhs/order/controller/OrderController.java", "snippet-controller",
                        "OrderService", "my-xhs-order/src/main/java/com/myxhs/order/service/OrderService.java", "snippet-service",
                        "优先检查：文件=my-xhs-order/src/main/java/com/myxhs/order/service/OrderService.java；方法=createOrder()；最近提交=1dcd5c8 chore: sync pending project changes",
                        "sync pending project changes",
                        " 247  // 5. 本地事务已在 Listener 中执行完毕，获取 orderId\n 248  if (context.getOrderId() == null) {",
                        "my-xhs-order 这个主类/方法负责什么，为什么优先看 OrderService.createOrder()？",
                        List.of("PaymentFeignClient", "OrderTransactionListener"),
                        List.of(new TraceDiagnosisResult.ClassSource(
                                "OrderService", "my-xhs-order/src/main/java/com/myxhs/order/service/OrderService.java", "snippet")),
                        "createOrder",
                        "LoveEleve 最近修改了 my-xhs-order/src/main/java/com/myxhs/order/service/OrderService.java（1dcd5c8）",
                        "变更解释：当前问题首先落在 OrderService.createOrder()；最近提交者是 LoveEleve（1dcd5c8）；最近一次改动是 `1dcd5c8 chore: sync pending project changes`。排查时优先从最新改动和这些协作点交界处入手。")),
                new TraceSearchResult("trace-id", "remote-es",
                        List.of(new TraceSearchResult.ServiceHit("my-xhs-order", "交易链路", 2)),
                        List.of(), List.of(), List.of(), "my-xhs-order", "my-xhs-order"),
                new com.myxhs.ai.app.service.trace.TraceServiceContextLoader.ServiceContext(
                        "my-xhs-order", null, List.of(), List.of(), List.of(), List.of()),
                "answer");
        CodeSearchResult codeSearch = new CodeSearchResult(
                "哪个类负责库存预扣？",
                "代码结构：inventory",
                new CodeSearchResult.Hit(
                        "inventory", "inventory", "强状态库存控制与预扣中心", null,
                        "哪个类负责库存预扣？", null, null, null,
                        "my-xhs-inventory/src/main/java/com/myxhs/inventory/service/InventoryService.java",
                        List.of("InventoryService"), List.of("InventoryController"), List.of("OrderTransactionConsumer"), List.of(),
                        List.of("预扣/释放/确认库存"),
                        new TraceDiagnosisResult.Owner("LoveEleve", "120724735+LoveEleve@users.noreply.github.com", "1dcd5c8", "chore: sync pending project changes"),
                        List.of("1dcd5c8 chore: sync pending project changes"),
                        "LoveEleve 最近修改了 my-xhs-inventory/src/main/java/com/myxhs/inventory/service/InventoryService.java（1dcd5c8）",
                        "变更解释：当前问题首先落在 InventoryService；最近提交者是 LoveEleve（1dcd5c8）；最近一次改动是 `1dcd5c8 chore: sync pending project changes`。排查时优先从最新改动和这些协作点交界处入手。",
                        "InventoryService", "my-xhs-inventory/src/main/java/com/myxhs/inventory/service/InventoryService.java", "snippet-service",
                        "preDeduct",
                        "优先检查：文件=my-xhs-inventory/src/main/java/com/myxhs/inventory/service/InventoryService.java；方法=preDeduct()；最近提交=1dcd5c8 chore: sync pending project changes",
                        "sync pending project changes",
                        " 222       * </p>\n 223       */\n 224      public void preDeduct(PreDeductRequest request) {\n 225          Long skuId = request.getSkuId();\n 226          Long orderId = request.getOrderId();",
                        List.of(new CodeSearchResult.RelatedTraceSample(
                                "trace_clean_order_payment_20260821",
                                "5304dc5a8afb4741b8bc74cee49c3980",
                                "gateway -> order -> payment",
                                "clean multi-service payment trace used for reviewer acceptance")),
                        List.of("OrderTransactionConsumer"), 99, "代码结构：inventory"),
                List.of());
        Method method = RunController.class.getDeclaredMethod("view", AgentRun.class, com.myxhs.ai.app.service.trace.TraceDiagnosisResult.class,
                com.myxhs.ai.app.service.knowledge.CodeSearchResult.class, String.class, String.class, String.class, String.class);
        method.setAccessible(true);
        Map<String, Object> view = (Map<String, Object>) method.invoke(null, run, diagnosis, codeSearch,
                "codeSearch", "为什么优先看 InventoryService.preDeduct()？", "run_prev", "inventory");
        Map<String, Object> trace = (Map<String, Object>) view.get("traceDiagnosis");
        assertNotNull(trace);
        assertEquals("trace-id", trace.get("traceId"));
        assertNotNull(trace.get("recommendedFollowups"));
        assertEquals("remote-es", trace.get("source"));
        assertNotNull(trace.get("serviceProfiles"));
        assertEquals("uncertain", trace.get("verdict"));
        assertEquals("bounded-reviewer-v1", trace.get("reviewerMode"));
        assertEquals("需要更多证据", trace.get("reviewerRationale"));
        assertEquals("my-xhs-order", trace.get("entryService"));
        assertEquals("answer", trace.get("renderedAnswer"));
        Map<String, Object> code = (Map<String, Object>) view.get("codeSearch");
        assertNotNull(code);
        assertEquals("哪个类负责库存预扣？", code.get("query"));
        assertNotNull(view.get("followupMeta"));
        Map<String, Object> followupMeta = (Map<String, Object>) view.get("followupMeta");
        assertEquals("run_prev", followupMeta.get("sourceRunId"));
        assertEquals("inventory", followupMeta.get("sourceService"));
        assertNotNull(code.get("topHit"));
        assertNotNull(code.get("recommendedFollowups"));
        Map<String, Object> topHit = (Map<String, Object>) code.get("topHit");
        assertNotNull(topHit.get("blameSummary"));
        assertNotNull(topHit.get("changeExplanation"));
        assertNotNull(topHit.get("diagnosisTriplet"));
        assertNotNull(topHit.get("methodBlameSummary"));
        assertNotNull(topHit.get("methodSnippet"));
        assertNotNull(topHit.get("relatedTraceSamples"));
        assertEquals("preDeduct", topHit.get("methodHint"));
        java.util.List<?> profiles = (java.util.List<?>) trace.get("serviceProfiles");
        assertNotNull(profiles);
        java.util.Map<?,?> first = (java.util.Map<?,?>) profiles.get(0);
        assertNotNull(first.get("owner"));
        assertNotNull(first.get("callChainHints"));
        assertEquals("OrderController", first.get("primaryController"));
        assertEquals("my-xhs-order/src/main/java/com/myxhs/order/controller/OrderController.java", first.get("primaryControllerPath"));
        assertEquals("OrderService", first.get("primaryService"));
        assertEquals("my-xhs-order/src/main/java/com/myxhs/order/service/OrderService.java", first.get("primaryServicePath"));
        assertNotNull(first.get("primaryServiceSnippet"));
        assertNotNull(first.get("primaryControllerSnippet"));
        assertNotNull(first.get("nextHops"));
        assertNotNull(first.get("classSources"));
    }
}
