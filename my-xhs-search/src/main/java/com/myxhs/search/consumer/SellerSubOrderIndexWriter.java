package com.myxhs.search.consumer;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.util.Map;

/**
 * 商家子单 ES 索引写入器（C-004）
 * <p>
 * 正确性守卫：<b>ES 只做发现</b>；一切写操作/终态判断以 DB + CAS 为准
 * （索引的最终一致延迟不得导致错误操作）。
 * </p>
 * <p>
 * 从消费者中抽出的 ES 薄胶水（便于消费者单测 mock）：
 * <ul>
 *   <li><b>版本化 upsert</b>：{@code ExternalGte} + binlog ts——乱序/陈旧事件不覆盖新版本（同既有消费者模式）</li>
 *   <li><b>软删除标记</b>：逻辑删除（deleted=1）写 {@code deleted=true} 标记文档（列表查询过滤），
 *       同样带版本防乱序；物理 DELETE 事件亦走此路径（防御性）</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SellerSubOrderIndexWriter {

    private final ElasticsearchClient esClient;

    @Value("${search.seller-order.index-name:seller_sub_order_index}")
    private String indexName;

    /** 版本化 upsert（doc id = subOrderNo；同 id 重放幂等） */
    public void upsert(String subOrderNo, Map<String, Object> doc, long version) throws IOException {
        indexDoc(subOrderNo, JSON.toJSONString(doc), version);
    }

    /** 软删除标记（列表查询以 deleted=false 过滤） */
    public void softDelete(String subOrderNo, long version) throws IOException {
        indexDoc(subOrderNo, "{\"subOrderNo\":" + JSON.toJSONString(subOrderNo) + ",\"deleted\":true}", version);
    }

    private void indexDoc(String subOrderNo, String jsonDoc, long version) throws IOException {
        try {
            esClient.index(IndexRequest.of(idx -> idx
                    .index(indexName)
                    .id(subOrderNo)
                    .versionType(VersionType.ExternalGte)
                    .version(version)
                    .withJson(new StringReader(jsonDoc))));
        } catch (IOException | RuntimeException e) {
            if (isVersionConflict(e)) {
                log.info("[卖家子单索引] 陈旧版本事件已忽略(ES 已有更新版本): subOrderNo={}, version={}",
                        subOrderNo, version);
                return;
            }
            throw e;
        }
    }

    /** 版本冲突判定（对齐既有消费者实现：cause 链 + 双写法匹配） */
    private boolean isVersionConflict(Throwable e) {
        Throwable t = e;
        while (t != null) {
            String m = String.valueOf(t.getMessage());
            if (m.contains("version_conflict") || m.contains("version conflict")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }
}
