# Search 模块 Review

## ES 索引一致性
1. **[高] 增量补偿查询 SQL 未带物理库前缀（跨库）** (IncrementalIndexSyncJob.java:205-231)
   `queryNotesByIds` 直接 `FROM t_note`、`queryProductsByIds` 直接 `FROM t_spu`。
   笔记在 my_xhs_content、商品在 my_xhs_product 库；若 search 服务 JDBC 默认 schema 不是对应库，
   补偿查询失败/查错库 → 补偿失效。对应 P0-5（跨库前缀）。需确认 search 数据源配置并显式带库名。

2. **[高] 补偿版本号与 Canal 版本号域不一致 → 补偿后增量更新被 ES 拒绝**
   - Canal 消费端用 `es`（Canal 全局事件序列，小整数）作 ExternalGte version (NoteIndexSyncConsumer.java:129,216)。
   - 增量补偿 job 用 `version() = System.currentTimeMillis()`（~1.7e12 巨值）(IncrementalIndexSyncJob.java:69,253)。
   - 同一 note 先被补偿写入 version≈now，之后 Canal UPDATE 的 es 版本远小于 now →
     ExternalGte 拒绝 → 该文档停在补偿时快照，后续增量更新永久失效，直到全量重建。
   - **需统一版本域**：补偿也应沿用 Canal es/binlog 版本，或补偿读库时用最大 binlog pos。

## 搜索 / 业务
3. **[中] 搜索失败静默返回空结果** (NoteSearchService.java:110-118) —— 可用性优先，但需监控告警区分"ES 挂了 vs 无结果"。
4. **[低] 用 `recordOrderCreateLatency` 记录搜索耗时** (NoteSearchService.java:106) —— 指标名误导，应专用 timer。
5. Search After 深分页 + ExternalGte 防乱序 + 高亮 —— 实现正确。

## 可靠性
6. 失败 noteId 记 Redis Set + 增量补偿 + 每日全量重建 —— 闭环完整。
7. DELETE 用 status=-1 标记删除（防乱序复活）—— 思路正确（虽未带 version，靠既有 doc version 防旧写）。

## 备注
- Recommend（ItemCF/协同/召回多策略）与 HotSearch 模块未逐行深读；按代码规模(>3k行)建议后续专项。
