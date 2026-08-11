# 08. 搜索与推荐域（my-xhs-search）业务逻辑

> 端口 19016 | 18 端点 | ES 三索引 + Lua 反作弊 + ItemCF 推荐 + 5 路召回

## 一、业务定位
**内容发现 + 商品发现 + 个性化推荐**：搜索（笔记/商品/补全）、实时热搜、基于行为的推荐。是流量分发的第二入口（另一是 Feed）。

## 二、核心业务逻辑
1. **ES 搜索**：note_index(multi_match title^3+content, ik_smart) + product_index + suggest_index(FST)；SearchAfter 分页。
2. **实时热搜**：Lua 反作弊(用户+IP+分钟窗口+屏蔽词) → ZSet 热搜 + Set 置顶/屏蔽；快照落 MySQL。
3. **推荐**：5 路召回(ItemCF/Content/Hot/Following/Geo) → 粗排精排重排 → 冷启动降级 hot+geo。
4. **Canal 同步**：MySQL → Canal → MQ → ES 增量；IndexRebuildJob 凌晨 4 点全量兜底。
5. **行为上报**：R03 行为上报(MQ) → 供 ItemCF 离线计算。

## 三、异常路径
- ES 索引重建期间 → 搜索短暂不可用或返回旧数据。
- Canal 延迟 → 新数据未及时可搜。
- 无结果 → 平台级"搜索无结果率"指标（业务关注）。

## 四、对 AI 项目（指标/诊断）的价值
| 指标 | 来源 | 就绪度 |
|------|------|:---:|
| 日搜索量、热搜 Top20 | ES/Redis/MySQL | ✅ |
| 搜索无结果率 | ES 查询结果 | ⚠️ 需统计 |
| 推荐 Feed CTR | t_user_behavior + exposure | ⚠️ 需曝光数据 |
| 冷启动降级占比 | recommend | ⚠️ 需观测 |

> **内容支柱的发现能力**，业务价值高、数据就绪度尚可；PLAN V1 未把它列入诊断场景。
