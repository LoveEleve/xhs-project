# RV18：四路深度审查与 P0/P1 修复

> 日期：2026-09-13｜方式：4 路并行审查（文档口径 / SRE 运行态 / 跨项目一致性+简历 / 代码第四轮）+ 修复闭环
> 结论：**2 个 P0、3 个 P1 全部修复并验证（22/22 单测，5f67f89）**；遗留项转入 M4 清单

## 一、代码第四轮（重点）

| 级别 | 问题 | 根因 | 修复 |
|------|------|------|------|
| P0 | "再入 DLQ"识别为死代码，重投结果会被误判成功 | `redeliver` 落库的 newMsgId 是客户端 UNIQ_KEY，而 DLQ 的 `DLQ_ORIGIN_MESSAGE_ID` 是 broker 物理 msgId，两者永不相等 | effect 同时落 `offsetMsgId`（`sendResult.getOffsetMsgId()`）与 newMsgId；核验按双 ID 匹配（`RedeliverVerifier`） |
| P0 | 消费位点核验跨 topic 汇总，存在假阳性/假阴性 | `consumerOffsetSum(group)` 聚合全部 topic，与消息是否被消费无关 | 落库消息所落队列坐标（brokerName/queueId/queueOffset）；核验只比对该队列 consumerOffset 是否越过 queueOffset（`DlqAdminService.queueProgress`） |
| P1 | `/execute` check-then-act，并发可重复执行 | 读 executionStatus 与写执行结果非原子 | `UPDATE ... WHERE status='approved' AND result 不含 executing/executed` CAS 抢占 |
| P1 | 事件总线无任何 listener，"跨实例感知"为空 | 订阅消息仅打日志 | 接入 Micrometer 监听器（`ai_approval_events_received_total`）+ 指数退避重连（5s→60s）+ 停机 join |
| P1 | 位点诊断端点无鉴权 | 直接暴露消费组位点 | 限管理/内部令牌（未授权 401/403）；pending 复用刷新 `requested_at` 防误过期 |
| P2 | 引用短名匹配只覆盖 architecture/code-map；code_locate 路径可穿越；空索引被缓存 | — | 短名取最后一段全域匹配；`..` 拒绝 + normalize 前缀校验；空扫描不缓存 |

验证：`mvn test` 22/22（新增 `RedeliverVerifierTest` 三分支：reentered_dlq / verified_consumed / verified_no_reentry）；重启后诊断端点未授权 401、admin 200（cart 组 77/77 diff=0），事件总线订阅启动。

## 二、文档口径审计（已回填）

- README：v0.4→v0.5、`docs/eval/`→`eval/`、补 50 条答案评测集与 RV18 索引、55 卡口径、运行状态与下一步更新、Embedding 封存说明。
- `docs/resume-and-metrics.md`：M2.x"未实现"→已交付；答案级 50/50+引用 100% 回填；成本回填 18,292/1,433、≈¥0.012–0.048、~74%（N=10+100 轻量）；红队 6→8；产物路径全部校正；Part C 打勾。
- 手册：大 V 阈值 5w→10w（代码 default 100000）、`maxReconsumeTimes` 3/5 分组口径、DlqMetrics 22 核心组。
- RV08/RV10/RV13/RV15 挂账项标注"RV17/RV18 已交付"。

## 三、SRE 运行态审计（事实）

- systemd active（23:16 重启后 NRestarts=0）；24h 内 72 次重启为历史抖动（当时 crash 循环被 StartLimit 拦截），当前窗口为 0。
- 审批事件订阅线程存活；`ai_approval`：approved=19、expired=1、pending=0；MCP 子进程 3 个。
- 遗留清理项：测试审批 id=19/20、Redis 测试键（转 M4 清理）。

## 四、跨项目一致性

- 工作区脏项已处理：`knowledge/code-map/infra-anchors.yaml`（第 55 卡）随本提交入库；`answer-eval-2026-09-13.json` 为评测产物，按最新复跑结果提交。
- 简历口径与实测证据逐条对齐（MTTR/KB/答案级/红队/成本/M2.x）；未回填项清零。

## 五、遗留（M4 清单）

1. ~~压测 N≥100（P50/P95/P99 CSV）~~ ✅ 已完成（C=5 基线 + C=20 超载，`docs/reports/load-test-20260913.md`）
2. ~~FMEA 演练 4-6 项（停 ES/Redis failover/kill MCP/滚动重启）~~ ✅ 已完成（RV19；发现并修复 ES MCP 权限 P0）
3. ~~工具预算护栏~~ ✅ 已完成（软32/硬40 + `ai_agent_tools_total` Gauge；硬预算要求先实现 tool_search）
4. ~~平台侧 DlqMetrics 覆盖差 8 组~~ ✅ 已修复：清单对齐为 26 组（含 cart-event-sink-group/order-pay-result/order-refund/note-delete/counter-es-sync/coupon-return-redis-repair/like-unlike/favorite-unlike），移除 5 个陈旧组；my-xhs-cart 已灰度重启验证 26 组全部暴露；其余 14 个服务 jar 已用修复版 common 全量重建（clean package），随下次重启/部署生效
5. ~~KB 评测 55 卡复跑~~ ✅ 已完成：答案级 30/30、聚合引用 100%（首轮 2 例幻觉路径经定向复测修正，硬校验门禁生效）
