# 搜索、热搜与推荐（内容/商品发现）

## 业务问题（AI 能回答）
- 日搜索量、热搜 Top20、搜索无结果率。
- 推荐 Feed CTR、冷启动降级占比。
- 搜索/推荐效果异常诊断。

## 口径与定义
- **ES 三索引**：note_index / product_index / suggest_index(FST)。
- **热搜**：Lua 反作弊(用户+IP+分钟窗口+屏蔽词) → ZSet 实时 + Set 置顶/屏蔽 + MySQL 快照。
- **推荐**：5 路召回(ItemCF/Content/Hot/Following/Geo) → 粗排精排重排 → 冷启动降级 hot+geo。
- 数据同步：MySQL → Canal → MQ → ES 增量；IndexRebuildJob 凌晨 4 点全量兜底。

## 数据来源与就绪度
| 指标 | 来源 | 就绪度 |
|------|------|:---:|
| 日搜索量、热搜 Top20 | ES + Redis + MySQL 快照 | ✅ |
| 搜索无结果率 | ES 查询结果 | ⚠️ 需统计 |
| 推荐 CTR | t_user_behavior + 曝光 | ⚠️ 需曝光数据 |
| 冷启动降级占比 | recommend | ⚠️ 需观测 |
| 搜索行为分布 | t_user_behavior(behavior 6=搜索) | ⚠️ |

## 关键不变量 / 可信边界
- 热搜反作弊（用户/IP 频率限制）——防刷。
- 置顶/屏蔽/词管理是管理端点（X-Admin），AI 只读。
- Canal→ES 延迟 <1s；索引重建期间搜索短暂降级。

## 关联诊断
- "搜索无结果率/CTR 异常" → 需关联：索引同步延迟、热搜反作弊误伤、推荐召回/精排变更、内容供给。

## 关联
- 商品搜索依赖 pillar-2 commerce 的 catalog 索引同步。
