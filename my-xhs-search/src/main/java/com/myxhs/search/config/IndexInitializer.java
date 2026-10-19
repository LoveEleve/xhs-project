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
    private final IndexAliasManager aliasManager; // C-011 T2.3：新部署直接建别名模型

    /** 索引副本数（生产集群基线默认 1——副本保 HA；单节点开发/演示环境显式设 0，避免 yellow 常鸣） */
    @Value("${search.index.replicas:1}")
    private int indexReplicas;

    @Value("${search.note.index-name:note_index}")
    private String noteIndexName;

    @Value("${search.product.index-name:product_index}")
    private String productIndexName;

    @Value("${search.suggest.index-name:suggest_index}")
    private String suggestIndexName;

    /** 商家维度子单索引（C-004：Canal order_instance → SUB_ORDER_INDEX_TOPIC → 本索引） */
    @Value("${search.seller-order.index-name:seller_sub_order_index}")
    private String sellerOrderIndexName;

    @Override
    public void run(ApplicationArguments args) {
        // C-011 T2.3：重建受管索引（note/product）走别名模型——新部署直接建 {alias}_v1 + 别名；
        // legacy 存量（同名实体无别名）保持原地，由部署说明引导迁移；suggest/seller 为原地索引（不受重建管理）
        ensureRebuildManagedIndex(noteIndexName, IndexMappings.NOTE_INDEX_MAPPING);
        ensureRebuildManagedIndex(productIndexName, IndexMappings.PRODUCT_INDEX_MAPPING);
        createIndexIfNotExists(suggestIndexName, IndexMappings.SUGGEST_INDEX_MAPPING);
        createIndexIfNotExists(sellerOrderIndexName, IndexMappings.SELLER_SUB_ORDER_INDEX_MAPPING);
    }

    /**
     * 重建受管索引就绪（C-011）：别名存在→OK；legacy 同名实体→原地；皆缺→建 v1 + 别名指向。
     */
    private void ensureRebuildManagedIndex(String alias, String mapping) {
        try {
            IndexAliasManager.ResolvedIndex resolved = aliasManager.resolve(alias, mapping);
            log.info("[ES索引] 重建受管索引就绪: alias={} → concrete={} (legacyInPlace={})",
                    alias, resolved.concreteIndex(), resolved.legacyInPlace());
        } catch (Exception e) {
            log.warn("[ES索引] 重建受管索引初始化失败（ES 可能未启动）: alias={}, error={}", alias, e.getMessage());
        }
    }

    private void createIndexIfNotExists(String indexName, String mapping) {
        try {
            // 副本数可配（默认 1=生产集群基线；单节点开发环境设 0）
            final String effectiveMapping = mapping.replace("%REPLICAS%", String.valueOf(indexReplicas));
            boolean exists = esClient.indices().exists(
                    ExistsRequest.of(e -> e.index(indexName))).value();
            if (exists) {
                log.info("[ES索引] 索引已存在: {}", indexName);
                return;
            }

            esClient.indices().create(CreateIndexRequest.of(c -> c
                    .index(indexName)
                    .withJson(new StringReader(effectiveMapping))));

            log.info("[ES索引] 创建成功: {}", indexName);
        } catch (Exception e) {
            log.warn("[ES索引] 创建失败（ES 可能未启动）: index={}, error={}", indexName, e.getMessage());
        }
    }

    // ==================== 索引 Mapping：见 IndexMappings（C-011 提取） ====================
}
