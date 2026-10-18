package com.myxhs.search.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商家子单索引文档构建器单测（C-004）
 * <p>
 * 覆盖两条写入路径的输入形态：canal 字符串值（增量）与 order 域对象值（存量回填）——
 * 保证同一行数据在两条路径下产生结构一致的文档。
 * </p>
 */
class SellerSubOrderIndexDocumentBuilderTest {

    private final SellerSubOrderIndexDocumentBuilder builder = new SellerSubOrderIndexDocumentBuilder();

    private Map<String, Object> canalRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("sub_order_no", "ORDER-1001-1");
        row.put("order_no", "ORDER-1001");
        row.put("order_id", "1001");
        row.put("user_id", "2001");
        row.put("seller_id", "3001");
        row.put("status", "2");
        row.put("total_amount", "199.00");
        row.put("discount_amount", "9.00");
        row.put("pay_amount", "190.00");
        row.put("created_at", "2026-10-17 12:00:00");
        row.put("updated_at", "2026-10-17 12:30:00");
        row.put("deleted", "0");
        return row;
    }

    private Map<String, Object> orderScanRow() {
        Map<String, Object> row = new HashMap<>();
        row.put("sub_order_no", "ORDER-1001-1");
        row.put("order_no", "ORDER-1001");
        row.put("order_id", 1001L);
        row.put("user_id", 2001L);
        row.put("seller_id", 3001L);
        row.put("status", 2);
        row.put("total_amount", new BigDecimal("199.00"));
        row.put("discount_amount", new BigDecimal("9.00"));
        row.put("pay_amount", new BigDecimal("190.00"));
        row.put("created_at", LocalDateTime.of(2026, 10, 17, 12, 0, 0));
        row.put("updated_at", LocalDateTime.of(2026, 10, 17, 12, 30, 0));
        row.put("deleted", 0);
        return row;
    }

    @Test
    @DisplayName("canal 字符串行 → 文档（类型转换：数字/金额/布尔/时间）")
    void buildsFromCanalStringRow() {
        Map<String, Object> doc = builder.build(canalRow());

        assertThat(doc.get("subOrderNo")).isEqualTo("ORDER-1001-1");
        assertThat(doc.get("orderId")).isEqualTo(1001L);
        assertThat(doc.get("userId")).isEqualTo(2001L);
        assertThat(doc.get("sellerId")).isEqualTo(3001L);
        assertThat(doc.get("status")).isEqualTo(2);
        assertThat(doc.get("totalAmount")).isEqualTo(new BigDecimal("199.00"));
        assertThat(doc.get("createdAt")).isEqualTo("2026-10-17 12:00:00");
        assertThat(doc.get("deleted")).isEqualTo(false);
    }

    @Test
    @DisplayName("order 扫描对象行 → 文档（LocalDateTime 归一为 yyyy-MM-dd HH:mm:ss）")
    void buildsFromOrderScanObjectRow() {
        Map<String, Object> doc = builder.build(orderScanRow());

        assertThat(doc.get("subOrderNo")).isEqualTo("ORDER-1001-1");
        assertThat(doc.get("orderId")).isEqualTo(1001L);
        assertThat(doc.get("status")).isEqualTo(2);
        assertThat(doc.get("payAmount")).isEqualTo(new BigDecimal("190.00"));
        assertThat(doc.get("createdAt")).isEqualTo("2026-10-17 12:00:00");
        assertThat(doc.get("updatedAt")).isEqualTo("2026-10-17 12:30:00");
        assertThat(doc.get("deleted")).isEqualTo(false);
    }

    @Test
    @DisplayName("ISO 字符串时间（Feign/Jackson 输出）归一为 yyyy-MM-dd HH:mm:ss")
    void normalizesIsoDateTime() {
        Map<String, Object> row = orderScanRow();
        row.put("updated_at", "2026-10-17T12:30:00");

        Map<String, Object> doc = builder.build(row);

        assertThat(doc.get("updatedAt")).isEqualTo("2026-10-17 12:30:00");
    }

    @Test
    @DisplayName("versionOf：updated_at → epoch 毫秒（与 canal ts 同量级）")
    void versionOfParsesUpdatedAtMillis() {
        long expected = LocalDateTime.of(2026, 10, 17, 12, 30, 0)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

        assertThat(builder.versionOf(orderScanRow())).isEqualTo(expected);
        // canal 字符串行同样可解析
        assertThat(builder.versionOf(canalRow())).isEqualTo(expected);
        // 缺失 → 回退当前时间（> 0 且为毫秒量级）
        assertThat(builder.versionOf(new HashMap<>())).isGreaterThan(1_000_000_000_000L);
    }

    @Test
    @DisplayName("deleted 多形态解析（'1'/1/true → 删除）")
    void deletedVariants() {
        Map<String, Object> row = canalRow();
        row.put("deleted", "1");
        assertThat(builder.build(row).get("deleted")).isEqualTo(true);

        row.put("deleted", 1);
        assertThat(builder.build(row).get("deleted")).isEqualTo(true);

        row.put("deleted", true);
        assertThat(builder.build(row).get("deleted")).isEqualTo(true);

        row.put("deleted", null);
        assertThat(builder.build(row).get("deleted")).isEqualTo(false);
    }
}
