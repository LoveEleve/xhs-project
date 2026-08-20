package com.myxhs.search.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductIndexDocumentBuilderTest {

    private final ProductIndexDocumentBuilder builder = new ProductIndexDocumentBuilder();

    @Test
    void shouldBuildCompleteProductDocumentFromAuthoritativeFields() {
        Map<String, Object> product = new HashMap<>();
        product.put("id", 10L);
        product.put("name", "商品");
        product.put("category_id", 20L);
        product.put("category_name", "数码");
        product.put("brand_id", 30L);
        product.put("min_price", "19.90");
        product.put("status", 1);

        Map<String, Object> document = builder.build(product, Map.of());

        assertThat(document).containsEntry("categoryName", "数码");
        assertThat(document).containsEntry("price", new java.math.BigDecimal("19.90"));
        assertThat(document).containsEntry("brandId", 30L);
    }

    @Test
    void shouldUseMinimumSkuPrice() {
        Map<String, Object> product = new HashMap<>();
        product.put("id", 11L);
        product.put("category_name", "服饰");

        Map<String, Object> detail = Map.of(
                "categoryName", "服饰",
                "skuList", List.of(Map.of("price", "99.00"), Map.of("price", "49.00")));

        Map<String, Object> document = builder.build(product, detail);

        assertThat(document).containsEntry("price", new java.math.BigDecimal("49.00"));
    }

    @Test
    void shouldRejectIncompleteProductInsteadOfWritingPlaceholders() {
        Map<String, Object> product = new HashMap<>();
        product.put("id", 12L);
        product.put("name", "商品");

        assertThatThrownBy(() -> builder.build(product, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("商品分类信息不完整");

        product.put("category_name", "食品");
        assertThatThrownBy(() -> builder.build(product, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SKU 价格信息不完整");
    }
}
