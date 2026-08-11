# Elasticsearch 排障（索引健康 / 同步延迟 / 查询 / 分片）

> my-xhs 有**两套 ES**：业务搜索(19200) + SkyWalking 存储(19201)。搜索与日志分析都依赖它。

## 业务问题（AI 能回答）
- **集群健康**：green/yellow/red、分片分配、relocating。
- **同步延迟**：Canal→MQ→Consumer→ES 链路是否跟上。
- **搜索质量**：无结果率、查询延迟、高亮/分词。
- **索引问题**：note/product 索引一致性、重建失败。

## 可用的数据资产与就绪度
| 排障目标 | 数据来源 | 就绪度 |
|---------|---------|:---:|
| 集群健康 | `_cluster/health` / `_cat/indices` | ✅ |
| 同步延迟 | Canal 状态 + search 同步日志 + IncrementalIndexSyncJob | ✅ |
| 索引重建 | IndexRebuildJob(凌晨4点) + 失败记录 Redis Set | ✅ |
| 查询延迟 | search 服务耗时指标 | ⚠️ 需观测 |
| 无结果率 | ES 查询结果 | ⚠️ 需统计 |

## 关键诊断点
1. **同步链路**：MySQL → Canal(flatMessage=false) → MQ → Consumer。Canal 失联 → ES 索引停止同步；恢复后追赶 Binlog。
2. **失败补同步**：IndexSync 消费者失败 → ID 记录 Redis Set → IncrementalIndexSyncJob 补。
3. **延迟容忍**：正常 <1s；重建期间搜索短暂降级（search 可降级 MySQL 直查）。
4. **双 ES 隔离**：业务 ES(19200) 与 SkyWalking ES(19201) 独立，故障互不影响。

## 关联
- 搜索无结果/商品搜不到 → 常因索引同步延迟 → 关联 pillar-1 discovery + pillar-2 catalog。
- 恢复见 `failover-scenarios.md` §六。
