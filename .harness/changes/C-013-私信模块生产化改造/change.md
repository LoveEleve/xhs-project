---
id: C-013
slug: 私信模块生产化改造
status: confirmed
created: 2026-10-19
updated: 2026-10-19
iteration: ITER-013     # 机器可读迭代协议：.harness/iterations/ITER-013-私信模块生产化改造/
relations:
  relates_to:
    - slug: docs/学习/模块对照/10-私信
      reason: §7 六条线索收口 + 二轮深探 D1~D7（僵尸未读/无收敛/ACK 丢失三真问题）
    - slug: docs/学习/模块对照/00-执行方案
      reason: Phase 3 消息域收官站（im——真正的 WebSocket 模块；notification 为 SSE）
  depends_on: []
---

# C-013 私信模块生产化改造

> **立项（2026-10-19）**：Phase 3 消息域收官站。**两轮深探（18 文件全读 + SQL 基线核对 + 异常窗口逐条推演）**：
> 核心 = **D1 软删会话僵尸未读（真 bug）** + **D2 DB/Redis 双写无收敛（连接时重建替代对账 Job）** + **D3 post-persist 无降级/ACK 不保** + **F1 幽灵未读竞态（未读前移）**；
> 另有 F2 registerRoute 非原子（同族 Lua 缺位）、F3 上限缺 containsKey 豁免（SSE 同型比对）、F6 发送侧无幂等键（clientMsgId）、F4/D6 注释漂移 ×7、D5 SQL 基线副本漂移 ×2（echo C-010 同型）、F5 死物（LB/死方法/死键/死列）、/ws/ticket 无限流。
> 定位口径：**真正的 WebSocket 模块**（双向；notification 是 SSE 单向）；at-least-once 投递 + 客户端 ACK/去重契约。
> 规划详见 `.harness/iterations/ITER-013-私信模块生产化改造/`。

## 用户故事

作为消息域开发者，我想要把私信模块从"未读漂移无收敛 + ACK 不保 + 注册竞态 + 死物混杂"升级到
"**未读双源可收敛 + 投递降级有序 + 路由注册原子 + 发送侧幂等**"，并建立测试集、收官消息域。

## 非目标（Out of Scope）

- 多设备/多端同步（单用户单连接模型保留——未实现边界，讲）；
- 真写扩散（共享存储 1 份保留——设计取舍，讲）；conversation_id 分片落地（单库预留，讲）；
- 对账 Job（无 XXL——以"连接时重建"轻量收敛替代，讲）；Last-Event-ID 式补推（离线 ZSet + ACK 已够）。

## 契约影响

- WS 协议：`ImMessage` 新增**可选** `clientMsgId`（不传则行为不变——向后兼容）；ACK 语义不变
- REST: /ws/ticket、conversations、messages、unread-count 补 @RateLimit
- Redis: `myxhs:im:online:{u:id}` 键族移除（死键）；unread hash 补 30d TTL；新增 `myxhs:im:msgdedup:*`（clientMsgId 幂等）
- 数据模型: 无 DDL 变更（死列 is_read 留注）；SQL 基线副本同步 ×2 处（注释 + 索引）

## 影响面

- 模块/服务: my-xhs-im（主体）
- wiki: 无（归档时回流）

## 测试策略

- **测试集从零建立**（模块当前零测试）：ChatService（未读顺序/降级/幂等/软删）、OnlineRoute（Lua 矩阵）、Handler（容量/踢线/生命周期）、Subscriber（KICK/透传/降级）、握手（票据三关）、Controller（票据签发/启动自检）、离线补发（排序/清理）、未读重建
- 回归：**十三模块**（im 首次纳入回归集）
