# my-xhs 模块梳理交接文档

> 更新时间：2026-07-29
> 当前进度：10/16 模块完成

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

---

## 待梳理模块

| 编号 | 模块 | 端口 | 关键特征 |
|:--:|------|:--:|------|
| 11 | notification | 19013 | SSE 长连接 |
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
| payment → order | — | ⚠️ 待验证（callback 测试期间未报错但未确认 URL override） |
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
| cart | Feign URL override | application.yml 1行 |

---

## 环境信息

- **服务机**: 21.214.97.212 (eth1)
- **中间件机**: 21.130.247.89
- MySQL: 13306/13307/13308/13309
- Redis: 16379(Sentinel)/16380(Cache)/16381(Business)
- ES: 19200 (elastic/Xhs@2026#Elastic)
- Nacos: 18848 (namespace=my-xhs)
- RocketMQ NS: 9876;9877
- XXL-Job Admin: 18080
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
