---
iteration: ITER-013
status: confirmed
version: 1
created: 2026-10-19
updated: 2026-10-19
---

# 测试报告: 私信模块生产化改造

## 执行摘要

- 执行时间: 2026-10-19（三阶段：① 可靠性收敛 → ② 工程对齐 → ③ 收口）
- 环境 profile: JDK 17 + Maven 3.9 离线；无运行时（单测口径；删 target/test-classes 强制全量重编）
- 命令: 十三模块 `mvn -o ... -am test`
- 结果: ✅ **十三模块 664 全绿**（common 100 / product 35 / coupon 24 / order 132 / inventory 33 / payment 73 / search 66 / cart 33 / counter 15 / analytics 29 / content 17 / notification 57 / **im 50（从零建立）**——im 首次纳入回归集）

## 用例执行结果（要点）

| TC | 归属 AC | 结果 | 断言/证据 |
|----|---------|------|----------|
| TC-1 | AC-1 | 🟢 | `ChatServiceTest#unreadIncrementedBeforeRouting`：InOrder（未读脚本→离线脚本→publish→ACK） |
| TC-2 | AC-1 | 🟢 | `ChatUserRelationMapperTest#softDeleteZeroesUnread`：SQL 注解含 `unread_count = 0` |
| TC-3/4 | AC-1 | 🟢 | `ChatServiceTest#refreshUnreadCache`：重建 + 30d TTL；空则删键 |
| TC-5/6 | AC-2 | 🟢 | `#routeFailureStillAcks` / `#storeOfflineFailureStillRoutesAndAcks` |
| TC-7/8 | AC-2 | 🟢 | `#handleReadRedisFailureStillNotifies` / `#handleReadLocal` |
| TC-9 | AC-3 | 🟢 | `OnlineRouteServiceTest#registerReturnsPrevious`：单脚本 + 旧值 + {u:id} tag |
| TC-10/11 | AC-3 | 🟢 | `ImWebSocketHandlerTest#capExemptionForExistingUser` / `#capRejectsNewUser` |
| TC-12~15 | AC-4 | 🟢 | `#clientMsgIdReplayAcksOriginal`（ACK 原 msgId 不落库）/ `#clientMsgIdFirstProcess` / `#persistFailureDeletesDedupMark` / 未传跳过 |
| TC-16/17 | AC-1 | 🟢 | `#rejectsSelfAndEmpty` / `#lockBusyNack` / `#longContentTruncatedAndSeqAssigned` |
| TC-18/19 | AC-3 | 🟢 | `#establishedKicksAndRestores`（踢本地+跨实例+重建+补发）/ `#closedGuard` |
| TC-20~22 | AC-2 | 🟢 | `ImRouteSubscriberTest`：推成功删副本/推失败降级（仅 CHAT）/97 踢线/98·99 透传 |
| TC-23 | AC-2 | 🟢 | `ImHandshakeInterceptorTest`：类型/jti 一次性/subject 三关 |
| TC-24/25 | AC-6 | 🟢 | `ImControllerTest`：ticket jti 登记 / fail-closed / 限流注解 ×4 |
| TC-26 | AC-1 | 🟢 | `#pushOfflineMessages`：seqNo 升序 + DB 缺失清理 |
| TC-27 | AC-1 | 🟢 | `#totalUnread`：Redis 求和 + DB fallback |
| TC-28 | AC-1 | 🟢 | `MessagePersistServiceTest`：1 消息 + 双方 upsert（0/1）/ dup 降级 / affected=0 补插 / 截断 |
| TC-29/30 | AC-5/6 | 🟢 | grep：注释零漂移 + SQL 6 副本一致；死物（LB/isOnline/online 键/isRead）零残留 |
| TC-31~40 | 各 AC | 🟢 | 既有路径回归（心跳/踢线 CAS/离线 ACK/typing 在线判定/截断代理对/JSON 容错） |

## 阶段落地（提交记录）

| 阶段 | 提交 | 内容 |
|------|------|------|
| 规划 | `docs(harness): C-013/ITER-013 ...`（587b018c） | 五件套（两轮深探 D1~D7 + F1~F6 裁决） |
| ①② | `feat(im): C-013①② ...`（3550f009） | 未读前移 + 软删清零 + 连接重建 + 降级/ACK 必达 + register Lua + 上限豁免 + clientMsgId + 死物移除 + 注释 ×7 + SQL 副本同步 + 限流 ×4 |
| ③ | 见提交链 | 测试集 50（9 类）+ §8 回写 + 09-IM 面试材料同步 |

## 深审查（清单先行，见 review-c013.md）

- 实施期测试构建修正 ×2（WebSocketSession.sendMessage 受检异常 / selectBatchIds 不可变列表 sort）——测试锁定后全绿；
- 二轮深探三真问题（D1/D2/D3）全部修复并经单测锁定；
- 结论：见 review-c013（清单全闭环）。

## 收口清单

- [x] 十三模块全量回归 664 全绿（im 50 首纳）
- [x] §8 回写（10-私信）
- [x] 面试材料同步（09-IM：online 键移除/三 Lua 原子/未读前移/ACK 必达/LB 移除）
- [x] change 卡 → done
- [x] 运行时验证按项目规则后置
