package com.myxhs.ai.app.service.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MetricDictionaryIngester 解析单元测试（纯逻辑）。
 * 覆盖：A 面表（指标列=第1列）/ B 面表（指标列=第2列，场景列标 section）/ 表头/分隔行/噪音。
 */
class MetricDictionaryIngesterTest {

    private static final String MD = """
            ## 0. 全局约定
            | 项 | 值 |
            |----|-----|
            | 时区 | Asia/Shanghai |

            ### A1. 订单量下降（漏斗）
            | 指标 | 推荐口径 | 来源 | 就绪度 |
            |------|---------|------|:--:|
            | 下单量（漏斗第3环）| 按 t_order.created_at 计数 | t_order_0~3 | ✅ |

            ## 2. 能力面 B —— 技术指标
            | 场景 | 指标 | 推荐口径 | 来源 | 就绪度 |
            |:--:|------|---------|------|:--:|
            | B1 | 慢查询数 | slow_query_log | MySQL | ✅ |
            | B2 | 消费积压 | rocketmq_consumer | MQ | ✅ |
            """;

    @Test
    void A面表_指标列第一列_正确提取() {
        List<Map<String, String>> docs = MetricDictionaryIngester.parse(MD);
        Map<String, String> order = docs.stream()
                .filter(d -> d.get("title").equals("下单量（漏斗第3环）")).findFirst().orElseThrow();
        assertEquals("A1. 订单量下降（漏斗）", order.get("section"));
        assertTrue(order.get("content").contains("按 t_order.created_at 计数"));
        assertTrue(order.get("source").contains("#A1. 订单量下降（漏斗）"));
    }

    @Test
    void B面表_指标列第二列_场景列标section() {
        List<Map<String, String>> docs = MetricDictionaryIngester.parse(MD);
        Map<String, String> slow = docs.stream()
                .filter(d -> d.get("title").equals("慢查询数")).findFirst().orElseThrow();
        assertEquals("B1", slow.get("section"));   // 场景列作为 section
        assertTrue(slow.get("content").contains("slow_query_log"));
    }

    @Test
    void 噪音行被过滤() {
        List<Map<String, String>> docs = MetricDictionaryIngester.parse(MD);
        // 表头行/分隔行/全局约定（时区）不应作为知识
        assertTrue(docs.stream().noneMatch(d -> d.get("title").equals("项")), "表头列名不应入库");
        assertTrue(docs.stream().noneMatch(d -> d.get("title").equals("时区")), "全局约定行应被过滤(无指标语义)");
        assertEquals(3, docs.size(), "应只有 3 条知识（下单量/慢查询数/消费积压）: " + docs);
    }
}
