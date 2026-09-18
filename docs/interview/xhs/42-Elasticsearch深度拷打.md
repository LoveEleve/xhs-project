# 第42题 | 组件深度拷打：Elasticsearch

> 难度：★★★★★｜频率：★★★★★｜区分度：高
> 关键词：倒排/正排、分片与副本、refresh/translog、external version、search_after、ILM、查询调优

## 问题
ES 的写入和查询原理？为什么搜索快？分片怎么设计？怎么保证不丢数据？性能怎么调？

## 面试可讲版（五段式）

**① 原理层（写/读路径）**
- **写入**：请求 → 协调节点按 `_routing`（默认 `_id`）路由分片 → 主分片写**内存 buffer + translog** → **refresh（默认 1s）** 生成新 segment（可被搜索）→ 定期 **flush** 落盘 + **segment merge**（合并小段、物理删除标记文档）；
- **近实时**：`refresh_interval` 决定"写入多久可搜"（1s 默认；本项目业务索引按需调整，日志类可加大）；
- **查询**：query 阶段各分片打分返回 docId → fetch 阶段取 `_source`；**倒排索引**（term→doc 列表）做匹配，**正排 doc_values** 做排序/聚合；
- **分片设计**：分片数在创建时固定（改要 reindex）；每个分片是独立 Lucene 索引；**副本数**决定高可用与读吞吐。

**② 本项目用法**
- **版本**：ES 8.19.19（单节点，19200）+ 独立 SkyWalking ES 8.12.2（隔离观测数据与业务）；
- **索引**：`note_index`（3 分片/0 副本）、`product_index`、`xhs_ai_knowledge`（知识卡 55 条）、`myxhs-logs-*`（日志，ILM 30 天删除 + 模板副本 0）；
- **版本控制**：外部版本 `external_gte`（Canal 毫秒 ts），旧写拒绝——同步链路（28 题）；
- **查询**：`multi_match` 加权重（question^3/title^2/keywords^2）、`search_after` 深分页、`completion suggester` 建议词、聚合做热搜快照；
- **写入**：Canal/MQ 双通道 + 增量补偿 + 全量重建（别名切换）；
- **客户端**：`io-thread-count: 16`、`max-conn-total: 400`、连接超时 30s（matching ES 实际能力）。

**③ 性能调优（实测）**
- **瓶颈定位**：压测中 ES 容器 CPU 打满 **202%**（配额 2 核）→ 配额提到 **6 核**；客户端 IO 线程 4→16、连接 400；recall 线程池 32/64；
- **结果**：search **508 → 1,448 RPS（2.9x）、P99 191 → 61ms**；
- **调优优先级**：资源（CPU/内存/磁盘）> 查询（避免 wildcard/大 from+size、用 filter、控制返回字段）> 写入（bulk 批量、refresh 间隔）；
- **单节点副本=0**：单节点副本无法分配（yellow）——业务与日志索引统一副本 0；`EsClusterNotGreen` 告警按此收敛（第 38 题）；**今日又发现一个 replicas=1 的漏网索引（yellow）→ 已修正并落地巡检脚本 + cron**。

**④ 可靠性（拷打重点）**
1. **"ES 会丢数据吗？"** 单节点：translog 保证已 ack 的写不丢（node 崩溃重启可恢复）；**但节点磁盘损坏就全丢**——ES 不是权威源（源在 MySQL），可重建；这就是"搜索索引可重建"的定位；
2. **"refresh 1s 延迟能接受吗？"** 搜索场景可接受；写后立即查（读己之写）不保证——需要的话用 `refresh=wait_for` 或读侧兜底（本项目写入后用户看到列表由 DB 兜底）；
3. **"分片数怎么定？"** 单分片 10-50GB 经验值；分片过多（小分片）增加协调开销；本项目数据量小（3 分片）主要考虑重建与扩展余量；
4. **"为什么不用副本？"** 单节点副本无法分配；生产多节点至少要 1 副本（提高可用与读吞吐）；
5. **"深分页怎么做？"** from+size ≤10000 限制 + 内存放大；`search_after` 游标（排序值）稳定高效；跳页不支持（产品上加载更多）；
6. **"mapping 能做变更吗？"** 已有字段类型不可改（要 reindex）；新增字段可；本项目用索引模板+别名策略（重建切换）。

**⑤ 话术**
> "ES 写入是内存 buffer 加 translog，refresh 后生成新 segment 可搜，flush 落盘、merge 合并段；查询靠倒排索引加正排 doc_values。我们 8.19 单节点，note 是 3 分片 0 副本，版本控制用 external_gte 防乱序，深分页用 search_after。性能上踩过一个明确的瓶颈：压测中 ES 容器 CPU 打满 202%，配额从 2 核提到 6 核、客户端 IO 线程 4 到 16，搜索从 508 提到 1,448 RPS、P99 从 191 降到 61ms。可靠性上 ES 不是权威源、可重建；单节点副本 0，今天还发现一个漏网副本 1 的日志索引导致 yellow，修完加了巡检 cron。"

## 发散追问地图（横向）
- 索引原理：倒排/正排、segment/merge、doc_values、FST。
- 集群：主分片/副本/选举、脑裂、跨集群复制（CCR）。
- 查询：query/filter 区别与缓存、打分（BM25）、聚合、scroll vs search_after。
- 写入：bulk、refresh/flush/translog、mapping 设计（keyword vs text）。
- 治理：ILM、快照/恢复、别名切换、reindex、监控（集群健康/JVM/慢日志）。

## 面试官评分点
**高级开发级**：能讲写入/查询路径与分片副本；知道 refresh 延迟。
**架构师加分**：CPU 瓶颈定位与参数调优的量化结果；ES 作为"可重建索引"的定位；yellow 索引巡检的运维闭环；search_after 的产品边界。
**危险信号**：把 ES 当权威存储；分片拍脑袋；深分页硬翻；CPU 打满还只调查询。

## 本项目真实证据
- ES 8.19.19 + 19200；`note_index` 3 分片/0 副本（运行态查询）；`myxhs-logs-*` ILM 30d（`apply-ilm.sh`）与巡检脚本 `scripts/es-log-index-check.sh`；
- 调优：`batch-release-baseline-20260917.md:19,29`（CPU 202%→6 核、508→1,448、P99 191→61）；客户端配置 `application.yml:100-101`；
- `EsClusterNotGreen`/`EsNodeHighJvmHeap` 告警规则（第 38 题）。

## 版本与来源
Elasticsearch 官方文档；本项目 search 配置与压测报告。

## 真实性说明
原理为标准知识；版本/索引/分片/调优数字/巡检脚本均为运行态与仓库事实。
