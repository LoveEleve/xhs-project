package com.myxhs.search.config;

/**
 * ES 索引 Mapping 定义（C-011：从 IndexInitializer 提取为共用件——别名管理器/重建任务复用）
 * <p>%REPLICAS% 占位符由使用方替换为配置副本数。</p>
 */
public final class IndexMappings {

    private IndexMappings() {
    }

    // ==================== 索引 Mapping 定义 ====================

    public static final String NOTE_INDEX_MAPPING = """
            {
              "settings": {
                "number_of_shards": 3,
                "number_of_replicas": %REPLICAS%,
                "analysis": {
                  "analyzer": {
                    "ik_smart": {
                      "type": "custom",
                      "tokenizer": "ik_smart"
                    },
                    "ik_max_word": {
                      "type": "custom",
                      "tokenizer": "ik_max_word"
                    }
                  }
                }
              },
              "mappings": {
                "properties": {
                  "noteId": { "type": "long" },
                  "userId": { "type": "long" },
                  "title": { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
                  "content": { "type": "text", "analyzer": "ik_smart" },
                  "coverImage": { "type": "keyword", "index": false },
                  "likeCount": { "type": "long" },
                  "collectCount": { "type": "long" },
                  "commentCount": { "type": "long" },
                  "status": { "type": "integer" },
                  "createdAt": { "type": "date", "format": "yyyy-MM-dd HH:mm:ss||yyyy-MM-dd'T'HH:mm:ss||epoch_millis" }
                }
              }
            }
            """;

    public static final String PRODUCT_INDEX_MAPPING = """
            {
              "settings": {
                "number_of_shards": 3,
                "number_of_replicas": %REPLICAS%
              },
              "mappings": {
                "properties": {
                  "spuId": { "type": "long" },
                  "skuId": { "type": "long" },
                  "name": { "type": "text", "analyzer": "ik_max_word", "search_analyzer": "ik_smart" },
                  "categoryId": { "type": "long" },
                  "categoryName": { "type": "keyword" },
                  "brandName": { "type": "keyword" },
                  "price": { "type": "scaled_float", "scaling_factor": 100 },
                  "image": { "type": "keyword", "index": false },
                  "sales": { "type": "long" },
                  "status": { "type": "integer" },
                  "createdAt": { "type": "date", "format": "yyyy-MM-dd HH:mm:ss||yyyy-MM-dd'T'HH:mm:ss||epoch_millis" }
                }
              }
            }
            """;

    /**
     * 商家维度子单索引（C-004/ITER-004）
     * <p>
     * 数据源：Canal order_instance（16 张物理子单表 binlog）→ SUB_ORDER_INDEX_TOPIC → 消费者。
     * 定位：<b>ES 只做发现</b>（商家列表检索）；一切写操作/终态判断以 DB + CAS 为准
     * （列表的最终一致延迟不得导致错误操作——发货等操作已有 CAS 保护）。
     * </p>
     */
    public static final String SELLER_SUB_ORDER_INDEX_MAPPING = """
            {
              "settings": {
                "number_of_shards": 3,
                "number_of_replicas": %REPLICAS%
              },
              "mappings": {
                "properties": {
                  "subOrderNo": { "type": "keyword" },
                  "orderNo": { "type": "keyword" },
                  "orderId": { "type": "long" },
                  "userId": { "type": "long" },
                  "sellerId": { "type": "long" },
                  "status": { "type": "integer" },
                  "totalAmount": { "type": "scaled_float", "scaling_factor": 100 },
                  "discountAmount": { "type": "scaled_float", "scaling_factor": 100 },
                  "payAmount": { "type": "scaled_float", "scaling_factor": 100 },
                  "deleted": { "type": "boolean" },
                  "createdAt": { "type": "date", "format": "yyyy-MM-dd HH:mm:ss||yyyy-MM-dd'T'HH:mm:ss||epoch_millis" },
                  "updatedAt": { "type": "date", "format": "yyyy-MM-dd HH:mm:ss||yyyy-MM-dd'T'HH:mm:ss||epoch_millis" }
                }
              }
            }
            """;

    public static final String SUGGEST_INDEX_MAPPING = """
            {
              "settings": {
                "number_of_shards": 1,
                "number_of_replicas": %REPLICAS%
              },
              "mappings": {
                "properties": {
                  "keyword": {
                    "type": "completion",
                    "analyzer": "ik_max_word",
                    "search_analyzer": "ik_smart"
                  },
                  "weight": { "type": "long" }
                }
              }
            }
            """;
}
