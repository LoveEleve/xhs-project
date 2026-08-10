# 21 通知与 IM

> 复审维度 21 | 覆盖模块：13-notification, 14-im | 领域专属检查项
>
> 你是谁：分布式系统审查员。不凭记忆，逐条执行以下指令。本维度覆盖 SSE 连接/WebSocket 会话/消息推送送达等独有问题。
> 通用规则：MQ见 04、并发见 02、运维见 10。

---


**执行本维度后，必须在审查报告中输出 `[21] 21 通知与 IM：发现 N 项`。缺失此标题 = 本维度未被执行。执行每条检查项时，标注来源维度编号（如 [21]）。**
## 检查项

### 21.1 SSE 与 WebSocket 连接管理 | 透镜：工程/微服务

**必须检查**：SSE/WebSocket 的长连接是否正确管理——心跳保活、断连重连、多实例用户的路由。

**怎么查**：
```bash
grep -rn 'SSE\|ServerSentEvent\|EventSource\|SseEmitter\|WebSocket\|@ServerEndpoint\|simpMessagingTemplate\|STOMP' my-xhs-notification/src/main/java/ my-xhs-im/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 无心跳 | 15 秒无消息→代理断开→用户永远收不到后续消息 |
| 断连未重连 | 断开后不重连→用户需手动刷新页面→体验极差 |
| 多实例路由错误 | 用户连实例 A→MQ 消息 B 拉取判断为实例 B→消息丢失 |
| SSEClient 泄漏 | SseEmitter 未在超时/错误后 remove→内存泄漏 |

**案例**：`SseEmitter` 超时回调未移除→长时间运行后内存持续增长（需加 onTimeout/onError → sseClients.remove）。

---

### 21.2 消息送达与 pending 聚合 | 透镜：业务/工程

**必须检查**：离线消息的 pending 机制是否完整——用户离线时的消息攒批在 Redis；上线后是否一次性加载全部而非丢失。

**怎么查**：
```bash
grep -rn 'pending\|unread\|未读\|聚合\|aggregation\|batch\|Lua.*agg' my-xhs-notification/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| pending 不合并 | 同类型通知（100 个 like）→pending 存 100 条而非聚合（"100 人点赞了"）→推送风暴 |
| 上线加载不全 | pending 一次只加载 20 条→有 50 条→用户看到 20→其余未读计数对不上 |
| 未读计数漂移 | pending 计数和实际 pending 消息数不同→前端小红点永远不准 |

**案例**：pending 聚合用 Lua 合并同类型消息→减少推送条数（修复 `notification_batch.lua` 聚合逻辑）。

---

### 21.3 消息可靠性 | 透镜：工程/微服务

**必须检查**：IM 消息的可靠性——发送→存储→发送→推送→ACK 的完整链路；离线消息的存储和TTL。

**怎么查**：
```bash
grep -rn 'ack\|ACK\|confirm\|delivery\|送达\|已读\|received\|sent' my-xhs-im/src/main/java/
grep -rn 'offline\|TTL\|expire\|retention\|maxAge' my-xhs-im/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 发送成功即认为送达 | MQ 发送成功→用户没收到→消息丢失 |
| ACK 丢失无重试 | ACK 丢失→服务端认为未送达→重复推送 |
| FIFO 不保证 | 消息 1→消息 2→用户收到先 2 后 1→聊天乱序 |
| 离线消息无 TTL | 用户离线 30 天→期间消息全攒→上线风暴→OOM |

**案例**：（全特性面预置检查项——14-im 模块的消息可靠性/ACK/去重/离线存储机制需逐路径审计。）

---

### 21.4 重连风暴与负载均衡 | 透镜：性能/并发/盲区

**必须检查**：大量用户同时断连重连时是否有防重连风暴机制（随机退避）；IM 长连接在多实例间是否均匀分布——是否有 sticky session 保证同一用户连同一实例。

**怎么查**：
```bash
grep -rn 'reconnect\|重连\|backoff\|退避\|retry.*delay\|random.*delay\|exponential' my-xhs-im/src/main/java/
grep -rn 'session.*affinity\|sticky\|ip_hash\|consistentHash\|LoadBalance' my-xhs-im/src/main/java/ gateway/src/main/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 同时重连 | Gateway 重启→10000 用户同时重连→connect storm→服务打死 |
| 无 sticky | 用户消息 1 到实例 A、消息 2 到实例 B→乱序且 B 无 history |
| 连接数无限制 | 单实例 50000 WebSocket→内存溢出→级联 crash |

**案例**：重连风暴需随机退避（100ms~2000ms）+ Gateway sticky session 确保同一用户消息到同一实例。

---

### 21.5 消息存储与会话恢复 | 透镜：生产级/工程

**必须检查**：IM 消息是否有持久化存储——用户换设备/重装 App 后是否能看到历史消息；会话列表 order 和最新消息一致性。

**怎么查**：
```bash
grep -rn 'history\|历史消息\|conversation\|会话\|message.*store\|message.*save\|lastMessage' my-xhs-im/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 消息只存 Redis | 重启后全部丢失→换设备消息空白 |
| 会话列表不一致 | DB 的会话和 Redis 的活跃 session 不同步→最新消息不更新 |
| 未读数跨设备不同 | 设备 A 读了消息→设备 B 仍显示未读→小红点漂移 |

**案例**：（全特性面预置检查项——14-im 模块的消息持久化/多设备同步/会话一致性需逐路径审计。）

---

## 跨维度引用

| 相关概念 | 主维度 | 说明 |
|---------|:--:|------|
| Redis Pub/Sub | 05 | SSE Redis Pub/Sub 跨实例推送 |
| MQ 幂等 | 04.1 | 消息重复推送去重 |
| 优雅关闭 | 10.1 | SSEClient 释放/线程池关闭 |

---

## 验证命令汇总

```bash
mvn compile test-compile -pl my-xhs-notification,my-xhs-im -am
mvn test -pl my-xhs-notification,my-xhs-im

# SSE/WebSocket
grep -rn 'SSE\|SseEmitter\|WebSocket\|@ServerEndpoint' my-xhs-notification/src/main/java/ my-xhs-im/src/main/java/

# pending/未读
grep -rn 'pending\|unread\|未读\|聚合\|aggregation' my-xhs-notification/src/main/java/

# 消息可靠性
grep -rn 'ack\|ACK\|confirm\|delivery\|送达\|已读' my-xhs-im/src/main/java/
```
