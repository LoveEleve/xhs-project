# 多实例分布式验证（2026-09-20）：IM 跨实例 ✓ / SSE 跨实例（发现两处真 Bug 并修复）

> 方法：用 `INSTANCE_ID=b PORT_OVERRIDE=<port> scripts/release-service.sh <module>` 起第二实例，验证多实例设计声明；单实例回归收尾。
> 结论：**IM 跨实例投递可用；SSE 跨实例此前完全不可用（两处叠加 Bug），已修复并验证**；Snowflake 同机多实例重号风险已修复。

## 一、IM 跨实例投递（验证通过）

- 拓扑：A 连主实例 19014、B 连第二实例 19114（`POST /api/im/ws/ticket` 两步握手 → `/api/im/ws?ticket=`）。
- A 发 `{"type":"CHAT","to":B,...}` → **B 在另一实例收到**完整帧（含 msgId/seqNo/traceId）。
- 结论：**路由表 + Redis pub/sub 跨实例投递设计成立**。
- 证据：`docs/reports/im-cross-instance-run-20260920-114055.json`；脚本 `scripts/test-im-cross-instance.py`。

## 二、SSE 跨实例（发现 2 个叠加 Bug，已修复）

**现象**：SSE 连主实例，通知事件由第二实例处理 → 主实例 SSE 收不到推送。

| # | 根因（代码级） | 修复 |
|---|---|---|
| Bug1 | `NotificationService.processEvent` 用 `sseEmitterManager.isOnline()`（**仅查本地 map**）做前置判断 → 事件被非连接所在实例处理时，跨实例推送被短路（`pushNotification` 内的 Redis 路由逻辑成死代码） | 新增 `isOnlineAnywhere()`（本地 `emitters` **或** Redis 路由键 `myxhs:notification:sse:{userId}`），processEvent 改用它 |
| Bug2 | `SseCrossInstanceSubscriber.toLong()` 对 Jackson 节点处理错误：项目 Jackson 将 Long 序列化为**字符串**（防 JS 精度丢失），而 `TextNode.toString()` 带引号 → `parseLong("\"2101…\"")` 必然失败 → **所有**跨实例消息被判"格式异常"（该功能从未生效） | `toLong` 增加 `JsonNode` 分支：`isNumber()→longValue()`；`isTextual()→parseLong(asText())` |

**修复后验证**：instance-b 处理事件 → 连在 19013 的 SSE 实时收到该通知帧（content 匹配）✓。

## 三、Snowflake worker-id 同机多实例重号（真问题，已修复）

| 项 | 结果 |
|---|---|
| 现象 | 同机两个 order 实例（19011/19111）**worker-id 均为 257**（仅按 IP 派生） |
| 风险 | 同毫秒同序列 → 两实例生成**相同 Snowflake ID**（订单主键冲突/覆盖） |
| 修复 | `resolveWorkerId` 第 3 级派生改为 `hash(ipPart*31 + server.port) % 1024`（端口随实例唯一；仍支持 `WORKER_ID` env / `worker.id` 系统属性优先） |
| 验证 | 重发双实例：**354（19011） vs 454（19111）** ✓ |

## 四、回归与收尾

- 回归：test-11 **12/12**、test-12 **8/8**、test-09 **16/16**（改动的 notification/order 全覆盖）。
- 第二实例（order-b 19111 / im-b 19114 / notification-b 19113）验证后已停止，恢复单实例标准环境（15/15，端口无残留）。

## 五、口径（面试可讲）
1. **"多实例"不是声明而是验证项**：单实例跑全绿不代表多实例正确——本轮两个跨实例 Bug 均只在双实例下暴露（本地判断短路、序列化类型差异）。
2. **跨实例三件套**：路由表（Redis key + TTL 续期）、发布订阅（pub/sub 广播、目标实例本地投递）、幂等/降级（离线回退到列表查询）。
3. **ID 生成与分片的联动**：worker-id 唯一性是 Snowflake 的硬约束；同机多实例必须用端口/PID/env 做区分（仅 IP 不够）。

## 六、产物
- 代码：`ShardingSphereDataSourceConfig`（worker-id）、`SseEmitterManager`（isOnlineAnywhere）、`NotificationService`（跨实例判断）、`SseCrossInstanceSubscriber`（toLong）
- 脚本/证据：`scripts/test-im-cross-instance.py`、`im-cross-instance-run-20260920-114055.json`
