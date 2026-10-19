package com.myxhs.search.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.StringReader;

/**
 * ES 索引别名管理器（C-011：零中断重建）
 * <p>
 * 模型：读/写路径统一使用**别名**（= 配置的 index-name），实体索引为 {@code {alias}_v{n}}：
 * <ul>
 *   <li>{@link #resolve(String, String)}：别名 → 当前实体；legacy（无别名、同名实体存在）→ 同名实体（原地回退）；
 *       两者皆缺 → 建 v1 + 别名指向；</li>
 *   <li>{@link #createNextIndex(String, String)}：建 {@code {alias}_v{n+1}}（mapping 复用 {@link IndexMappings}）；</li>
 *   <li>{@link #switchAlias(String, String, String)}：**原子**切换（updateAliases：remove from + add to 单次调用）；</li>
 *   <li>{@link #deleteIndex(String)}：删旧实体（best-effort）。</li>
 * </ul>
 * <p>
 * legacy 兼容：存量部署索引名即配置名（无别名）——resolve 返回 legacy 标记，重建任务回退原地模式（告警 + 部署说明迁移）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexAliasManager {

    private final ElasticsearchClient esClient;

    /** 索引副本数（默认 0：单节点无法分配副本） */
    @Value("${search.index.replicas:0}")
    private int indexReplicas;

    /**
     * 解析结果：实体索引名 + 是否 legacy（原地）模式
     */
    public record ResolvedIndex(String concreteIndex, boolean legacyInPlace) {
    }

    /**
     * 解析别名当前指向的实体索引（含 legacy 判定与自动建账）。
     *
     * @param alias   别名（= 配置的 index-name）
     * @param mapping 索引 mapping（自动建账时使用；%REPLICAS% 占位）
     */
    public ResolvedIndex resolve(String alias, String mapping) throws Exception {
        // 1. 别名存在 → 返回其指向的实体（先 existsAlias 探测：别名缺失时 getAlias 会抛 404 异常）
        boolean aliasExists = esClient.indices().existsAlias(e -> e.name(alias)).value();
        if (aliasExists) {
            var aliasResp = esClient.indices().getAlias(g -> g.name(alias));
            String concrete = aliasResp.result().keySet().iterator().next();
            return new ResolvedIndex(concrete, false);
        }

        // 2. legacy：同名实体索引存在（存量部署）→ 原地回退
        boolean existsAsIndex = esClient.indices().exists(e -> e.index(alias)).value();
        if (existsAsIndex) {
            log.warn("[索引别名] legacy 部署（同名实体无别名），回退原地模式: alias={}（部署说明：迁移到别名模型）", alias);
            return new ResolvedIndex(alias, true);
        }

        // 3. 皆缺 → 自动建账 v1 + 别名指向
        String v1 = alias + "_v1";
        createIndex(v1, mapping);
        esClient.indices().updateAliases(u -> u
                .actions(a -> a.add(add -> add.index(v1).alias(alias))));
        log.info("[索引别名] 自动建账: alias={} → {}", alias, v1);
        return new ResolvedIndex(v1, false);
    }

    /**
     * 创建下一版本实体索引（v1 → v2 → ...）。
     *
     * @param currentConcrete 当前实体名（形如 {alias}_v{n}；legacy 模式下不应调用）
     */
    public String createNextIndex(String alias, String currentConcrete, String mapping) throws Exception {
        int nextVersion = 1;
        String suffix = "_v";
        int i = currentConcrete.lastIndexOf(suffix);
        if (i > 0) {
            try {
                nextVersion = Integer.parseInt(currentConcrete.substring(i + suffix.length())) + 1;
            } catch (NumberFormatException ignore) {
                // 非版本命名 → 从 v2 起步（避免与当前实体同名）
                nextVersion = 2;
            }
        }
        String next = alias + suffix + nextVersion;
        createIndex(next, mapping);
        log.info("[索引别名] 新实体索引已创建: {}（当前 {}）", next, currentConcrete);
        return next;
    }

    /** 原子切换别名（remove from + add to 单次 updateAliases 调用） */
    public void switchAlias(String alias, String from, String to) throws Exception {
        esClient.indices().updateAliases(u -> u
                .actions(a -> a.remove(r -> r.index(from).alias(alias)))
                .actions(a -> a.add(add -> add.index(to).alias(alias))));
        log.warn("[索引别名] 原子切换完成: {} {} → {}", alias, from, to);
    }

    /** 删除实体索引（best-effort，失败仅告警） */
    public void deleteIndex(String index) {
        try {
            esClient.indices().delete(d -> d.index(index));
            log.info("[索引别名] 旧实体索引已删除: {}", index);
        } catch (Exception e) {
            log.warn("[索引别名] 旧实体索引删除失败(不阻塞，可人工清理): index={}", index, e);
        }
    }

    private void createIndex(String index, String mapping) throws Exception {
        final String effectiveMapping = mapping.replace("%REPLICAS%", String.valueOf(indexReplicas));
        esClient.indices().create(c -> c
                .index(index)
                .withJson(new StringReader(effectiveMapping)));
    }
}
