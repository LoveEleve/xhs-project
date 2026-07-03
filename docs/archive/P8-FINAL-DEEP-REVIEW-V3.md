# my-xhs 项目终极 P8 深度评审报告（v3.0）

> 审核日期：2026-06-01 | 审核范围：16 模块 × 396 文件 × 每行代码
> 前置文档：v1 基础评审 → v2 P8 深化评审 → v3 **终极全模块逐行审计**
> 新发现致命问题：**Feed 流管线完全断裂**（Content 不发送 MQ 到 Feed Consumer）

---

## 评分修正历史

| 版本 | 综合得分 | 关键词 |
|------|:---:|------|
| v1（表面评审） | 67/100 | "能不能跑" |
| v2（P8 深化） | 47/100 | "好不好用"—— Fallback 静默失效 |
| **v3（终极深挖）** | **38/100** | "有没有连上线"—— Feed 管线断裂 |

---

## 🔴 致命级发现（P0，本周必须修复）

### #1 🔴 Feed 流管线完全断裂

**根因**：`my-xhs-content` 的 `NoteService.publishNote()` 第 94 行：

```java
// TODO: 异步通知 Feed 服务（推送到粉丝收件箱），等 Feed 服务开发后接入 RocketMQ
```

没有发送任何 `FEED_TOPIC` MQ 消息。而 `my-xhs-home` 的 `FeedPushConsumer` 和 `FeedService` 约 800 行代码已经在等待该 Topic。**整个 Feed 流基础设施写了但没有连上线，所有 Feed 请求返回的是空数据或测试数据**。

| 组件 | 代码量 | 状态 |
|------|:---:|------|
| FeedPushConsumer | 194 行 | ✅ 完整实现 |
| FeedService | 471 行 | ✅ 完整实现 |
| NoteAggService | 230 行 | ✅ 完整实现 |
| NotePublishEvent (DTO) | 29 行 | ✅ 数据结构 |
| **NoteService 发送 MQ** | **0 行** | 🔴 **缺失** |

**Impact**：Feed 流（首页核心功能）完全不可用。

### #2 🔴 14 个 FallbackFactory 静默失效

| 服务 | FeignClient 数量 | Sentinel 依赖 | fallback 实际生效？ |
|------|:---:|:---:|:---:|
| order | 3 | ✅ | ✅ |
| payment | 1 | ✅ | ✅ |
| **home** | **11** | ❌ 无 | ❌ **全失效** |
| **cart** | **1** | ❌ 无 | ❌ **失效** |

**根因**：home 和 cart 的 pom.xml 没有引入 `spring-cloud-starter-alibaba-sentinel`，yml 中没有 `feign.sentinel.enabled: true`。FallbackFactory 写了但 Sentinel 不会执行它们。下游服务故障 → 直接 500。

### #3 🔴 ORDER_COMPENSATION_TOPIC 无消费者

`OrderCloseConsumer` 在关单失败时发送 `ORDER_COMPENSATION_TOPIC`，但全项目搜索该 Topic 的**消费者为 0 个**。关单补偿逻辑形同虚设。订单状态不一致无兜底保障。

---

## 🟡 重大发现（P1，两周内修复）

### #4 🟡 Nacos Config 全服务未启用

15 个业务服务全部 `spring.cloud.nacos.config.enabled: false`。`@RefreshScope` 全项目 0 处使用。改任何配置必须重启。大促降级无法秒级切换。

### #5 🟡 SkyWalking Agent 未挂载

OAP + UI 在 docker-compose 中运行，但微服务 `-javaagent` 启动参数未配置。全链路追踪靠手动 TraceId 透传（`TraceContextHolder` + `MqTraceHelper`），缺失 DB/Redis/MQ 调用的自动耗时采集。

### #6 🟡 Content 模块 CommentService 未接入 MQ

`CommentService.createComment()` 第 140 行：
```java
// TODO: 异步通知笔记作者（等通知服务开发后接入 RocketMQ）
```
评论发表后不通知笔记作者。No

[Note: Original content (41845 characters) exceeds maximum allowed characters (30000 characters). Please reduce the content by 11845 characters to ensure successful write operation.]
