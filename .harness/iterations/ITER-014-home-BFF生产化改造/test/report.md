---
iteration: ITER-014
status: confirmed
version: 1
created: 2026-10-19
updated: 2026-10-19
---

# 测试报告: home BFF 生产化改造

## 执行摘要

- 执行时间: 2026-10-19（三阶段：① 可靠性收敛 → ② 工程对齐 → ③ 收口）
- 环境 profile: JDK 17 + Maven 3.9 离线；无运行时（单测口径；删 target/test-classes 强制全量重编）
- 命令: 十四模块 `mvn -o ... -am test`
- 结果: ✅ **十四模块 703 全绿**（common 100 / product 35 / coupon 24 / order 132 / inventory 33 / payment 73 / search 66 / cart 33 / counter 15 / analytics 29 / content 17 / notification 57 / im 50 / **home 39**——home 首次纳入回归集）
- **勘误**：规划称"模块零测试"有误——home 既有 **9 用例**（HomeControllerTest ×5 异步 503 包装 + HomeFeignFallbackFactoryTest ×4 降级分档）；C-014 新增 **30**（FeedService 6 / FeedPush 5 / NoteDelete 3 / DLQ 4 / UserProfile 5 / Product 2 / Cart 4 / 限流 1），合计 39。

## 用例执行结果（要点）

| TC | 归属 AC | 结果 | 断言/证据 |
|----|---------|------|----------|
| TC-1~3 | AC-1 | 🟢 | Feed/UserProfile/Product/Cart 测试：`verify(batchFeignPool, atLeastOnce()).execute(...)`（池隔离防回归） |
| TC-4 | AC-2 | 🟢 | `UserProfileAggServiceTest#likeCollectChunked`：String id 正确提取（不再恒 0） |
| TC-5 | AC-2 | 🟢 | 256 篇 → 分批 100/100/50（captor 断言）+ 累计 500 |
| TC-6 | AC-2 | 🟢 | `#likeCollectPartialFailure`：批 2 抛异常 → 300（部分成功不归零） |
| TC-7 | AC-2 | 🟢 | 页上限 10（≈500 篇） |
| TC-8/9 | AC-3 | 🟢 | `FeedServiceTest#notesTimeoutDegradesTo503` / `CartAggServiceTest#cartTimeoutDegradesTo503`（挂起 5s → L1 超时 3s → 503） |
| TC-10/11/12 | AC-4 | 🟢 | `HomeDlqReplayConsumerTest` ×4：FEED_TOPIC / SOCIAL_TOPIC:NOTE_DELETE（tag 保留）/ 超限 / 失败不吞 |
| TC-13 | AC-5 | 🟢 | `#emptyFeedReturnsRealUnread`：空 Feed unreadCount=5（非写死 0） |
| TC-14 | AC-5 | 🟢 | `#bigVEmptySentinelReadBack`（"-1" 命中不重扫）/ `#bigVEmptyWritesSentinel`（pipeline 写入） |
| TC-15/16 | AC-5 | 🟢 | `FeedPushConsumerTest#protectionBoundaryAtThreshold`（`>=` + markPushCompleted）/ `#deletedNoteSkippedAndCompleted` |
| TC-17 | AC-6 | 🟢 | `FeedServiceTest#cardFieldsPopulated`：createdAt + score 填充 |
| TC-18/19 | AC-6 | 🟢 | grep（注释 ×4 + 常量）+ `HomeControllerRateLimitTest` ×5 |
| TC-20 | AC-7 | 🟢 | 十四模块 **703 全绿** |
| TC-21~40 | 各 AC | 🟢 | 既有回归：脏成员跳过/去重保高分/大V拉取/进度断点/zCard 单调用/空车保留优惠券/T-112·C-14 字段/503 语义 |

## 阶段落地（提交记录）

| 阶段 | 提交 | 内容 |
|------|------|------|
| 规划 | `docs(harness): C-014/ITER-014 ...`（72e265eb） | 五件套（三轮深探 F1~F9 + G1~G7 裁决） |
| ①② | 见提交链 | 池隔离 ×15 + likeCollect 修复 + 超时对齐 + DLQ ×2 + G1/G2/F5/F7/G3/G6 + 注释/常量 + 限流 ×5 |
| ③ | 见提交链 | 测试集 30（新增）+ §8 回写 + 08-Feed 面试材料同步 |

## 深审查（清单先行，见 review-c014.md）

- 测试构建修正 ×5（R<Map<String,Object>> 泛型推断 / R.ok 键类型 / InOrder 需含全部 mock / void set 用 doThrow / captor 泛型）——锁定后全绿；
- 规划勘误 1 处（"零测试"→既有 9 用例）；
- 结论：见 review-c014（清单全闭环）。

## 收口清单

- [x] 十四模块全量回归 703 全绿（home 39 首纳）
- [x] §8 回写（11-homeBFF）
- [x] 面试材料同步（08-Feed推拉：阈值口径 5万/10万矛盾、5min→7天、标注先行、DLQ）
- [x] change 卡 → done
- [x] 运行时验证按项目规则后置
