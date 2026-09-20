# 第10题 | SSE 跨实例与断线处理

> 难度：★★★☆☆｜频率：★★★☆☆｜区分度：中
> 关键词：SseEmitter、ticket 两步法、心跳 10s、Redis 路由 30s TTL、共享 Channel 广播、连接替换守卫、落库拉取

## 问题
问题：站内通知用 SSE 推送，多实例下用户连在别的实例上怎么推？断了怎么办？

## 面试可讲版（五段式）

**① 业界背景**
服务端推送三条路：WebSocket（双向、重）、SSE（单向、基于 HTTP、浏览器自动重连、企业通知首选）、长轮询（最兼容、最费）。SSE 的工程难点有两个：多实例路由（连接在哪个实例）与断线恢复（重连后不丢消息）；还有一个常被忽略的：**EventSource 不能自定义 Header，token 只能放 URL**——URL 会进日志/Referer，属安全反模式。

**② 项目选择**
`SseEmitterManager`：**ticket 两步法**解决鉴权——`POST /sse/ticket` 发 30s 一次性 ticket，`GET /sse?ticket=` 建立连接（token 不进 URL）；连接注册本地 emitter map + Redis 路由 `myxhs:notification:sse:{userId}` → serverId（host:port，**30s TTL，`@Scheduled` 每 10s 批量续期**）；推送先查路由——本实例直推，否则发**共享 Channel** `myxhs:notification:sse:channel`（所有实例都收，持有该用户连接的实例命中处理）；emitter 超时 30 分钟兜底。

**③ 坑**
- **连接替换竞态**：新连接进来时先 complete 旧 emitter，但旧连接的 complete 回调可能把**新连接**的 Redis 路由删掉——用删除守卫（先比对 emitter 身份再删）+ 推送失败清理用 `emitters.remove(key, value)` 双参数防误删；
- **发后即忘**：Pub/Sub 不可靠（订阅端重启窗口丢失）→ 推送只是尽力而为，可靠性靠通知落库 + 拉取；
- **长连接资源**：容器线程/内存有限额，超时与异常回调必须统一清理（onCompletion/onTimeout/onError 三处都要清）；
- SSE 鉴权别把 token 塞 URL（日志泄漏）——所以有 ticket 两步法。

**④ 兜底**
- 通知**先落库**（`t_notification`），用户可通过 `/list`、`/unread-count`、`/read-by-type`、`/read-all` 拉取与补偿——不依赖 SSE 送达；
- 路由表 30s 过期 → 视为离线，推送返回失败并清理连接；
- 跨实例发布失败返回 false，由业务侧重试/落库兜底；
- 服务重启后所有连接断，客户端重连到任意实例，未读以 DB 为准。

**⑤ 话术**
> "SSE 推送是尽力而为，可靠性在'落库 + 拉取'：跨实例用路由表定位、共享 channel 转发；连接替换必须用守卫，否则旧连接的 close 回调会把新连接的路由删掉。鉴权用 ticket 两步法，不把 token 放进 URL。"

## 追问与参考回答
**追问1：SSE 和 WebSocket 怎么选？** 单向通知 SSE 足够且实现便宜（HTTP 语义/自动重连）；双向聊天还是要 WebSocket（IM 模块就是）。
**追问2：路由表怎么知道连接还活着？** 10s 心跳批量续期 30s TTL；过期即视为不可达，走离线/拉取路径。
**追问3：为什么用共享 channel，不用 IM 那种实例专属 channel？** SSE 推送前已经查过路由，只有"跨实例"时才发；共享 channel 所有实例都收、本地命中者处理，实现更简单。IM 是每条消息都要定向，所以用 `im:route:{serverId}` 专属 channel。
**追问4：为什么不用 Last-Event-ID 补推？** SSE 标准支持 `Last-Event-ID`，但我们的补偿走"通知落库 + 拉取列表"，不依赖事件 ID 重放——更简单也更可靠（拉取天然幂等）。这是明确的设计取舍，不是遗漏。
**追问5：新连接替换旧连接怎么防双推？** 新连接注册时 complete 旧 emitter（旧连接收到终止），删除守卫保证旧回调不误删新路由；推送目标始终以本地 map 当前值为准。

## 面试官评分点
**高级开发级**：能说清路由表+转发的链路；知道三处回调清理与替换竞态；会用 ticket 解决 SSE 鉴权。
**架构师加分**：长连接容量与网关超时、离线补拉 model（落库拉取 vs Last-Event-ID）、共享 channel vs 定向 channel 的取舍、SSE/WS/长轮询选型。
**危险信号**：无路由直接本地推；连接替换无守卫；断线无补拉；token 放 URL 且无 ticket。

## 本项目真实证据
- `SseEmitterManager`：`SSE_KEY_PREFIX=myxhs:notification:sse:`、`SSE_TTL=30s`(:56-57)、`NOTIFY_SSE_CHANNEL=myxhs:notification:sse:channel`(:60)、心跳 `@Scheduled(fixedRate=10000)` 批量续期(:255-269)、emitter `30 分钟超时`(:73)、serverId=host:port(:311-317)、发布 `convertAndSend`(:239)、推送失败 `emitters.remove(userId, emitter)` 双参数守卫(:217-223)、旧连接 complete + 删除守卫(:80-103)。
- `NotificationController`：`/sse/ticket`(:46)、`/sse`(:59)、`/list`(:73)、`/unread-count`(:88)、`/read-by-type`(:109)、`/read-all`(:120)、`/sse/online-count`(:132)。
- 实测：test-4 双实例（19013/19023）SSE 连 inst1、两实例共同消费，客户端收聚合事件。

## 发散追问地图（横向）
- 推送协议对比：SSE/WebSocket/长轮询/HTTP2 push 的适用面。
- 连接治理：心跳、超时、自动重连、Last-Event-ID 语义与取舍。
- 跨实例：路由表定位、共享/专属 channel、MQ 扇出对比。
- 鉴权：EventSource 无自定义 Header → ticket/一次性 token/Cookie 方案对比。
- 断线恢复：落库+游标拉取 vs 事件重放；幂等与顺序。
- 网关/LB 配置：idle timeout、缓冲、压缩、连接数上限。
- 推送治理：合并批次、降频、优先级、静默时段。

## 版本与来源
SSE 规范（EventSource/Last-Event-ID）与 Spring `SseEmitter` 文档；本项目 notification 模块代码、test-4 运行态对账报告。

## 真实性说明
ticket 两步法、双键/TTL/心跳/守卫/超时均为代码事实；"网关超时"未在本轮核实，不写具体数值（emitter 自身 30min 兜底）；Last-Event-ID 未实现（用落库拉取替代，属有意取舍）。

## 本轮补充（2026-09-20 多实例实测：两处真 Bug 并修复）
- 实测拓扑：SSE 连实例A，通知事件由实例B处理。**修复前完全收不到推送**：
  ① `processEvent` 用 `isOnline()`（仅本地 map）做前置判断 → 跨实例分支被短路（`pushNotification` 的 Redis 路由成死代码）；
  ② 订阅者 `toLong` 对 Jackson `TextNode` 解析失败（项目 Jackson 把 Long 序列化为字符串、`toString()` 带引号）→ **所有**跨实例消息判"格式异常"。
- 修复：新增 `isOnlineAnywhere`（本地或 Redis 路由键）+ `toLong` 支持 `JsonNode`；**修复后实测推送成功**。
- 口径：跨实例推送三件套＝路由键（TTL 续期）、pub/sub 广播、目标实例本地投递；**单实例跑绿 ≠ 多实例正确**（两个 Bug 只在双实例暴露）。
