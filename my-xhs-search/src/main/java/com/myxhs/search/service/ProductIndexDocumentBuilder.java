package com.myxhs.search.service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class ProductIndexDocumentBuilder {

    public Map<String, Object> build(Map<String, Object> product, Map<String, Object> detail) {
        String categoryName = text(detail.get("categoryName"));
        if (categoryName == null) {
            categoryName = text(product.get("category_name"));
        }
        if (categoryName == null) {
            throw new IllegalStateException("商品分类信息不完整: spuId=" + product.get("id"));
        }

        BigDecimal minPrice = findMinPrice(detail.get("skuList"));
        if (minPrice == null) {
            minPrice = decimal(product.get("min_price"));
        }
        if (minPrice == null) {
            throw new IllegalStateException("商品 SKU 价格信息不完整: spuId=" + product.get("id"));
        }

        Map<String, Object> document = new HashMap<>();
        document.put("spuId", product.get("id"));
        document.put("name", product.get("name"));
        document.put("categoryId", product.get("category_id"));
        document.put("categoryName", categoryName);
        document.put("brandId", product.get("brand_id"));
        document.put("price", minPrice);
        document.put("image", firstImage(detail.get("images"), product.get("images")));
        document.put("status", product.get("status"));
        document.put("createdAt", product.get("created_at"));
        return document;
    }

    private BigDecimal findMinPrice(Object skuListObject) {
        if (!(skuListObject instanceof List<?> skuList)) {
            return null;
        }
        BigDecimal min = null;
        for (Object item : skuList) {
            if (!(item instanceof Map<?, ?> sku)) {
                continue;
            }
            Object price = sku.get("price");
            if (price == null) {
                continue;
            }
            try {
                BigDecimal value = new BigDecimal(price.toString());
                if (min == null || value.compareTo(min) < 0) {
                    min = value;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return min;
    }

    private String firstImage(Object detailImages, Object productImages) {
        if (detailImages instanceof List<?> images && !images.isEmpty() && images.get(0) != null) {
            return images.get(0).toString();
        }
        if (productImages instanceof String imagesJson && !imagesJson.isBlank()) {
            try {
                com.alibaba.fastjson2.JSONArray images = com.alibaba.fastjson2.JSON.parseArray(imagesJson);
                if (images != null && !images.isEmpty()) {
                    return images.getString(0);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String text(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return value.toString();
    }
}
