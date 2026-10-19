---
iteration: ITER-014
status: confirmed
version: 1
created: 2026-10-19
updated: 2026-10-19
---

# 测试设计: home BFF 生产化改造

## 验证策略

- **单测为主**（项目规则：无运行时；Redis/Feign 全 mock；删 target/test-classes 强制全量重编）；
- **测试集从零建立**（模块当前零测试）：目标 ≥40 用例 / ~8 类；
- 线程池断言：`verify(batchFeignPool, atLeastOnce()).execute(any())` + `verify(aggregatorPool, never()).execute(any())`（服务层）——池隔离防回归；
- 集成/E2E：不做。

## AC → 测试方法映射

| AC | 测试方法 | 层级 | 观测方式 |
|----|---------|------|---------|
| AC-1 | 四服务测试：内层 future 提交 batchFeignPool（execute 断言） | 单测 | mock 双池 verify |
| AC-2 | `UserProfileAggServiceTest`：String id 提取 / 分批 captor ≤100 / 部分失败累计 / 页上限 | 单测 | captor |
| AC-3 | `FeedServiceTest`（notes 超时→503）/ `CartAggServiceTest`（cart 超时→503） | 单测 | future 挂起模拟（mock 阻塞） |
| AC-4 | `HomeDlqReplayConsumerTest` ×2：重投目的地/tag 保留/attempt≤3/失败不吞 | 单测 | captor |
| AC-5 | `FeedServiceTest`：空路径 unread 真实值 / sentinel 缓存 / `>=` 边界 / markPushCompleted 四路径 | 单测 | verify |
| AC-6 | grep 核验（注释/常量）+ `HomeControllerTest` 限流注解 ×5 | 核验 | grep/反射 |
| AC-7 | 十四模块 `mvn test` 全绿 + §8/材料回写 | 回归 | 构建输出 |

## 测试类清单（8 类）

1. `FeedServiceTest`：组装（合并/去重/游标/hasMore/脏成员）+ 池隔离 + F3 + G2 + G1 + F5 + F7（部分在 consumer）
2. `FeedPushConsumerTest`：已删拦截 + 四路径 markPushCompleted + 大V/推模式 + 进度 + `>=` 保护
3. `NoteDeleteConsumerTest`：outbox 清理 + following SCAN + 标记先行 + 常量
4. `HomeDlqReplayConsumerTest`：两监听器 ×4 断言
5. `UserProfileAggServiceTest`：池隔离 + likeCollect（F2 全矩阵）+ 503/404 + noteCount
6. `ProductAggServiceTest`：池隔离（L1）+ 超时 503 + sku 映射（T-112 回归）
7. `CartAggServiceTest`：池隔离（L1）+ cart 超时 503 + 汇总计算 + C-14 字段回归
8. `HomeControllerTest`：限流注解 ×5 + DownstreamUnavailable → 503 包装

## 关键边界用例

- Feed notes 超时（future 未完成）→ 503 而非空流；cart 同
- likeCollect：50 篇（单批）/ 250 篇（3 批）/ 批 2 失败（累计批 1+3）；id 为 String
- bigV 空缓存 sentinel 读回（"-1" 过滤 → 空列表，不再重扫）
- 保护路径（fans >= threshold 且缓存陈旧 "0"）→ outbox + markPushCompleted
- 已删标记命中 → 跳过 + markPushCompleted（补偿 Job 不重投）
