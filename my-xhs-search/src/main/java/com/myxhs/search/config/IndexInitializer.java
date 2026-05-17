package com.myxhs.search.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.StringReader;

/**
 * ES 索引初始化
 * <p>
 * 应用启动时检查索引是否存在，不存在则自动创建。
 * 生产环境建议通过运维脚本管理索引，这里仅作为开发便利。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexInitializer implements ApplicationRunner {

    private final ElasticsearchClient esClient;

    @Value("${search.note.index-name:note_index}")
    private String noteIndexName;

    @Value("${search.product.index-name:product_index}")
    private String productIndexName;

    @Value("${search.suggest.index-name:suggest_index}")
    private String suggestIndexName;

    @Override
    public void run(ApplicationArguments args) {
        createIndexIfNotExists(noteIndexName, NOTE_INDEX_MAPPING);
        createIndexIfNotExists(productIndexName, PRODUCT_INDEX_MAPPING);
        createIndexIfNotExists(suggestIndexName, SUGGEST_INDEX_MAPPING);
    }

    private void createIndexIfNotExists(String indexName, String mapping) {
        try {
            boolean exists = esClient.indices().exists(
                    ExistsRequest.of(e -> e.index(indexName))).value();
            if (exists) {
                log.info("[ES索引] 索引已存在: {}", indexName);
                return;
            }

            esClient.indices().create(CreateIndexRequest.of(c -> c
                    .index(indexName)
                    .withJson(new StringReader(mapping))));

            log.info("[ES索引] 创建成功: {}", indexName);
        } catch (Exception e) {
            log.warn("[ES索引] 创建失败（ES 可能未启动）: index={}, error={}", indexName, e.getMessage());
        }
    }

    // ==================== 索引 Mapping 定义 ====================

    private static final String NOTE_INDEX_MAPPING = """
            {
              "settings": {
                "number_of_shards": 3,
                "number_of_replicas": 1,
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

    private static final String PRODUCT_INDEX_MAPPING = """
            {
              "settings": {
                "number_of_shards": 3,
                "number_of_replicas": 1
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

    private static final String SUGGEST_INDEX_MAPPING = """
            {
              "settings": {
                "number_of_shards": 1,
                "number_of_replicas": 1
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
