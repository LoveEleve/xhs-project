---
iteration: ITER-014
status: confirmed
version: 1
created: 2026-10-19
updated: 2026-10-19
---

# PRD: home BFF 生产化改造

## 背景与目标

**三轮深探结论**（38 文件全读 + 跨模块契约核验）：

- **🔴 F1 线程池隔离回旋镖**：A3 修复（内层 future 迁 batchFeignPool 防饥饿）不完整——NoteAgg 全迁（9 处）；**FeedService 的 batchFeignPool 注入未用（5 个内层全在 aggregatorPool）**、**UserProfileAgg 无该池（6 个全在）**、Product/Cart 的 L1（各 2 个）仍在 → 外层（controller 也在 aggregatorPool）占满线程等内层、内层排队饿死；
- **🔴 F2 likeAndCollectCount 恒 0（真 bug，T-119 修复不彻底）**：① `id instanceof Number` 只认 Number——R4 全局 Long→String 使 id 为 String → noteIds 恒空（total 修了、id 漏了）；② >100 篇时单请求超 `CounterBatchRequest @Size(max=100)` → 400；
- **🔴 F3 关键依赖超时语义不一致**：Product/Note/UserProfile 已修"超时→503"；Feed 的 notesFuture 超时→空 Feed（"没有笔记"误导）、CartAgg 超时→空购物车（且与自身 503-code→503 分支不自洽）；
- **🔴 F4 DLQ 缺口 ×2 组**（feed-push / note-delete——家族 echo）；
- **🟡 G1 卡片 createdAt/score 从未填充**（数据源已有 createdAt）；**G2 空 Feed 的 unreadCount 恒 0**（两处 early-return 未查 notification）；
- **🟡 F5 大V空缓存不缓存**（无大V关注时每次 feed 全量重扫 FOLLOW_LIST+MGET）；**F7 超大粉丝量保护 `>` vs 阈值 `>=`** + 保护路径漏 markPushCompleted；
- **🟡 G3 大V缓存 add+expire 非原子**（崩溃窗口 → 无 TTL 永不过期）；**F6 分页注释与行为相反**（开区间保翻页进度、代价=同分跨页剩余跳过——注释宣称"避免跳过"）；
- 🟡 注释/常量：line 147"逐个调用"陈旧（P2-3 已批量）、`note:deleted` 原始字符串重复 ×2、"5min"注释 vs 7d、FollowingRecall javadoc 缺 myxhs 前缀；**§7-5 五端点零限流**；§7-6"无前缀"勘误（实际有）；
- 记录（讲）：§7-3 卡片半成品、G5 索引分页跳过（取关/新增窗口）、G7 可见性/主页卡片无计数、大V双缓存双 TTL、SCAN 全量清理、断点 1h TTL。

## 非目标

BFF 建库；Feed 推拉重设计；卡片半成品补全；复合游标/值游标（演进）。

## 需求（REQ）

| ID | 需求描述 | 优先级 |
|----|---------|--------|
| REQ-1 | **池隔离补全（F1）**：Feed 5 处 + UserProfile 6 处 + Product L1 2 处 + Cart L1 2 处内层 future 迁 batchFeignPool（UserProfile 补注入） | P0 |
| REQ-2 | **likeCollect 修复（F2）**：id 提取 String-aware（复用 toLongValue）+ 查询分批 ≤100/批（部分成功累计）+ 页数上限 10（≈500 篇近似统计，文档化） | P0 |
| REQ-3 | **超时语义对齐（F3）**：Feed notesFuture 超时（!isDone）→ DownstreamUnavailable；CartAgg cartFuture 同；javadoc 同步 | P0 |
| REQ-4 | **DLQ 兜底（F4）**：DLQ 重放 ×2（feed-push → FEED_TOPIC；note-delete → SOCIAL_TOPIC 保留 tag NOTE_DELETE）；attempt≤3；失败不吞 | P0 |
| REQ-5 | **Feed 语义修正（G2/F5/F7）**：空路径 unreadCount 返回真实值；bigV 空结果 sentinel 缓存；阈值保护改 `>=` + 补 markPushCompleted | P1 |
| REQ-6 | **工程对齐（G1/G3/G4/G6/F6/§7-1/§7-5）**：卡片填 createdAt/score；bigV 缓存 pipeline 原子；注释 ×4；note:deleted 提常量；5 端点限流 | P1 |
| REQ-7 | **收口**：测试集从零（≥40）+ 十四模块回归 + §8 回写（11-homeBFF）+ 面试材料（08-Feed推拉）同步 | P0 |

## 验收标准（AC）

| ID | 归属 | 验收标准 | 来源 |
|----|------|---------|------|
| AC-1 | REQ-1 | 四服务内层 future 提交 batchFeignPool（verify execute）+ aggregatorPool 不承接内层（单测） | F1 |
| AC-2 | REQ-2 | String id 可提取；>100 分批（captor 断言每批 ≤100）；部分失败累计不归零；页数上限 | F2 |
| AC-3 | REQ-3 | notes/cart 超时 → DownstreamUnavailable（503），不再空流/空车（单测） | F3 |
| AC-4 | REQ-4 | DLQ 重投 ×2（tag 保留；attempt≤3；失败不吞）（单测） | F4 |
| AC-5 | REQ-5 | 空路径 unreadCount 真实值；空 bigV 缓存 sentinel；`>=` 边界 + 保护路径 markPushCompleted（单测） | G2/F5/F7 |
| AC-6 | REQ-6 | createdAt/score 填充；缓存 pipeline；注释零漂移（grep）；常量落位；限流注解 ×5 | G1/G3/G4/G6 |
| AC-7 | REQ-7 | 测试集 ≥40 + 十四模块全绿 + §8/材料回写 | 回归 |

## 边界情况

- Feed 超时 → 503（与 Product/Note/UserProfile 对齐；客户端可重试）；
- likeCollect 部分批次失败 → 累计已成功部分（近似统计，log 明示）；
- bigV 空缓存 sentinel "-1"（读路径过滤）；
- DLQ 重放 feed 消息幂等（ZADD）/note-delete 幂等（SCAN 清理 + 标记重设）；
- 池迁移后 batchFeignPool 负载上升（容量 30/80/500，CallerRuns 降级——记录）。

## 非功能需求

无运行时验证（编译 + 单测）；集群基线（无 Lua 多键；SCAN/pipeline 经 Lettuce 全量语义 ✓）；无新增依赖。
