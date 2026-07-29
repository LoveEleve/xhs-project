# my-xhs 模块梳理交接文档

> 更新时间：2026-07-29
> 当前进度：11/16 模块完成

---

## 完成模块

### 01-user — 用户模块 ✅
- 架构文档 + curl 测试（2.1~2.3 验证码/注册/登录）

### 02-content — 内容模块 ✅
- 架构文档 + curl 测试（3.1~3.11）+ 深度 13 篇

### 03-analytics — 数据分析模块 ✅
- 架构文档 + curl 测试（4.1~4.19）+ 深度 8 篇

### 04-counter — 计数服务 ✅
- 架构文档 + curl 测试（8 用例）+ 深度 5 篇

### 05-product — 商品服务 ✅
- 架构文档 + curl 测试（5 用例）+ 深度 5 篇

### 06-cart — 购物车服务 ✅
- 架构文档 + curl 测试（9 用例）+ 深度 6 篇

### 07-inventory — 库存服务 ✅
- 架构文档 + curl 测试（9/9 15层）+ 深度 6 篇
- 核心：三级扣减 + TCC + 热点检测 + Canal
- 修复：confirm.lua :bucket残留 + ReinitRequest DTO

### 08-coupon — 优惠券服务 ✅
- 架构文档 + curl 测试（8/8）+ 深度 4 篇
- 核心：Lua原子领券 + 责任链校验 + 对账Job
- 修复：新增 CouponReconcileJob

### 09-order — 订单服务 ✅
- 架构文档 + curl 测试（14/14 15层）+ 深度 4 篇
- 核心：事务消息 + ShardingSphere 4×4分片 + Event Sourcing + Feign编排
- 修复：traceId跨MQ传播 + Feign URL + MockPayService完整模拟

### 10-payment — 支付服务 ✅（简模块，无深度文档）
- 架构文档 + curl 测试（5/5）
- 核心：JdbcTemplate + 策略模式 + Feign双向回调
- 修复：extractPaymentNo/refundNo JSON解析 + mvn clean package

### 11-notification — 通知服务 ✅
- 架构文档 + curl 测试（9/9 + 聚合/SSE推送/心跳/MQ/Sentinel/XXL-Job 全15层）
- 深度 3 篇：SSE跨实例推送 / 聚合机制 / 未读计数与对账
- 核心：SSE长连接 + Ticket两步认证 + Redis Pub/Sub跨实例推送 + 5min窗口聚合(SETNX-first) + Lua防负数 + XXL-Job游标分页对账
- 修复：uk_aggregate唯一约束→普通索引 + processWithAggregate重构为SETNX-first
- XXL-Job: JobGroup=5(appname=my-xhs-notification), handler=unreadReconcileJob, job ID=5

---

## 待梳理模块

| 编号 | 模块 | 端口 | 关键特征 |
|:--:|------|:--:|------|
| 12 | im | 19014 | 即时通讯 |
| 13 | home BFF | 19015 | 聚合层，Feign 调所有服务 |
| 14 | search | 19016 | Elasticsearch 8.12 |
| 15 | common | — | 公共模块 |
| 16 | gateway | 19000 | HMAC 签名 |

---

## 跨模块 Feign 调用现状

| 调用方 | 被调用方 | 修复状态 |
|------|------|:--:|
| cart → product | — | ✅ URL override |
| order → inventory | — | ✅ URL override |
| order → coupon | — | ✅ URL override |
| order → payment | — | ✅ URL override |
| payment → order | — | ⚠️ 待验证 |
| notification → * | — | N/A（仅消费 MQ，无 Outbound Feign） |
| home → 所有服务 | — | 待处理 |

**Feign URL override 修复方式**：Spring Cloud 2023.0.1 + Nacos 2.3.0 的 LoadBalancer hashCode NPE bug，修复为 `spring.cloud.openfeign.client.config.{service}.url=http://localhost:{port}`。

---

## 代码修复汇总

| 模块 | 修复 | 文件 |
|------|------|------|
| inventory | confirm.lua :bucket残留 | confirm.lua +1行 |
| inventory | reinit DTO冗余校验 | 新建 ReinitRequest.java |
| coupon | 缺失对账Job | 新建 CouponReconcileJob.java |
| order | traceId跨MQ传播 | OrderService.java +1行 MqTraceHelper |
| order | Feign URL override ×3 | application.yml 3行 |
| order | MockPayService完整模拟 | MockPayService.java PaymentResult |
| order | pay-fail→自动取消 | OrderService.java onPaymentFailed |
| payment | extractPaymentNo JSON解析 | PaymentController.java |
| payment | extractRefundNo JSON解析 | PaymentController.java |
| payment | Optionals启动失败 | mvn clean package |
| notification | uk_aggregate唯一约束过严 | DROP UNIQUE → REGULAR INDEX |
| notification | processWithAggregate INSERT先于SETNX | 重构为SETNX-first |
| cart | Feign URL override | application.yml 1行 |

---

## 环境信息

- **服务机**: 21.214.97.212 (eth1)
- **中间件机**: 21.130.247.89
- **SSH to 中间件**: `ssh -p 36000 21.130.247.89`（21.214.97.212 已加白名单，2026-07-29）
- MySQL: 13306/13307/13308/13309
- Redis: 16379(Sentinel master) / 16380(Cache, allkeys-lru) / 16381(Business, noeviction)
- ES: 19200 (elastic/Xhs@2026#Elastic) — traceId 作为独立字段索引（Logstash grok 2026-07-29修复）
- Nacos: 18848 (namespace=my-xhs)
- RocketMQ NS: 9876;9877
- XXL-Job Admin: 18080 (admin/123456)
- Sentinel Dashboard: 8858 (sentinel/sentinel)
- Prometheus: 19090
- Grafana: 13000 (admin/Xhs@2026#Admin)
- SkyWalking UI: 8080
- Logstash→ES: myxhs-logs-YYYY.MM.DD (traceId grok已启用)

---

## 测试标准（15层验证）

| 层 | 内容 |
|:--:|------|
| L1 | API 响应 (HTTP status + JSON body) |
| L2 | ACCESS 日志 (traceId) |
| L3 | Redis (Key/值/TTL/端口) |
| L4 | MySQL (分片路由/字段值) |
| L5 | 应用日志 (Service/Consumer) |
| L6 | Nacos 注册 |
| L7 | XXL-Job Handler |
| L8 | MQ 消息链路 |
| L9 | @RateLimit 触发 |
| L10 | Sentinel |
| L11 | SkyWalking traceId 跨服务 |
| L12 | Gateway 路由 |
| L13 | Actuator 健康检查 |
| L14 | ES 日志采集 (traceId grok) |
| L15 | Prometheus 指标 |

---

## 文档结构规范

每模块：`docs/test-2/NN-module/`

```
01-{module}-module.md      — 架构文档（12节）
02-{module}-test-record.md — curl 测试（逐个用例，8-15层验证）
03-xxx.md                   — 深度文档
04-xxx.md                   — ...
```

深度文档必须有：业务背景/架构决策/源码追踪/面试Q&A/生产实验/发散章节。

---

## Redis 连接注意事项

- 应用层 `StringRedisTemplate` 通过 **Sentinel**（26379/26380/26381, master=mymaster）路由到 **16379**
- **直接用 CLI 查 `redis-cli -p 16379` 可能查不到**——CLI 可能不在 PATH，优先用 Python `redis.Redis(port=16379)`
- Sentinel master 地址：`python -c "import redis; r=redis.Redis(port=26379,password='...'); r.execute_command('SENTINEL','get-master-addr-by-name','mymaster')"` → `['21.130.247.89', '16379']`
- 16380 = Cache（幂等标记等），16381 = Business（SSE路由/Ticket/聚合/未读）

---

## 关键踩坑记录

1. **Spring Cloud 2023.0.1 LoadBalancer hashCode NPE**：所有有 Feign 客户端的模块都需要 URL override
2. **notification 的 `@Profile("dev")` 测试控制器**：测试时需 `--spring.profiles.active=dev`，密码/Token 也依赖此 Profile
3. **Sentinel 规则创建**：Dashboard API `POST /v2/flow/rule`，JSON 必须含 `app` 字段，DELETE 不支持 REST（规则随服务重启清理）
4. **XXL-Job JobGroup 创建**：title 字段疑似 VARCHAR(8) 限制（中文超出报 Data too long），建议用简短名称
5. **notification 无 Feign Outbound**：只消费 RocketMQ，不调用其他服务——L11 SkyWalking 跨服务不适用
6. **RocketMQ NOTIFICATION_TOPIC**：测试可写 Java Producer（notification 模块已有 MQ 依赖），`mvn exec:java` 运行
