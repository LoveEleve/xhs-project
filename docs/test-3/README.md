# test3 — 全链路分层回归测试（当前代码状态）

> 2026-08-12 创建 | 被测对象：**P0/P1/P2 全部修复后、15 服务运行中、中间件 25 容器**的当前代码状态。
> 方法论语录（必守）：`methodology/TEST-METHODOLOGY.md`（L1→L4 分层，禁止越层）+ `methodology/METHODOLOGY-L2L3-SUPPLEMENT.md`（L2 数据验证 + L3 九透镜）。

## 〇、本目录已复制（来自 test-2，副本独立）
- `methodology/`（3 篇测试方法论）
- `TEST-REFERENCE-V2.md`（端点/覆盖基准）
- `pitfalls.md`（踩坑记录 #1~#78，测试时对照）
- `review-consolidated.md`（修复进度/已知问题，L3 对照）
- `review-production-config.md`（生产配置/架构 review，L2 数据验证的 key/表/指标参考）

## 一、为什么新开 test3

- test-2 的 execution/ 是 Task2 时代的执行记录（代码已大改，全部过时）
- 本轮修复涉及 **19 个代码变更**（P-B4 令牌、P2 批量、事务收窄、SHA-256 幂等键、DB 兜底等），需要一次**按方法论的分层回归**，而非直接跑旧脚本

## 二、测试范围与顺序（严格按 L1→L4，不可越层）

### Layer 1 — 业务逻辑正确性（先通过）
**分组**（7 组，定时任务归所属业务组，数据上下文连贯）：
| 组 | 目录 | 业务链 | 归属定时/联动 |
|---|---|---|---|
| G1 | `cases/G1-auth-user/` | 认证/用户/关注/粉丝 | followCounterRepairJob |
| G2 | `cases/G2-content-social/` | 笔记/批量详情(P2-3)/评论/点赞收藏 | FeedMessageRetryJob、note 索引(canal→ES) |
| G3 | `cases/G3-product-cart/` | SKU/加购/购物车(P2-7)/对账(P2-8) | cartReconcileJob、product 索引 |
| G4 | `cases/G4-coupon/` | 模板/领券/用券 | couponExpireJob、couponReconcileJob |
| G5 | `cases/G5-trade/` | 下单(P2-12)/支付(P2-4)/退款/关单/库存 | order/payment/inventory 全部定时任务 + 事务消息/延时/重试 |
| G6 | `cases/G6-search-home/` | 搜索/热搜/推荐/Feed | feedCleanupJob、recommend×3、索引重建/增量 |
| G7 | `cases/G7-notify-im-counter/` | 通知/IM/计数 TTL | unreadReconcile/counterReconcile/CounterBuffer/SSE 心跳/IM 心跳 |
每组的测试文档=业务链路 + 该域定时任务 + 该域数据一致性一体验证。

### Layer 2 — 数据正确性（每端点 L1 通过后）
- Redis：key 存在性/TTL（P2-4 status key 7 天、P2-6 计数 30 天续期）
- MySQL：订单/支付/库存/消息表落库正确
- MQ：topic 投递/消费（本地消息表 status 流转）
- ES：索引同步（canal → topic → 消费者 → ES）

### Layer 3 — 生产级质量（L1+L2 通过后）
- 九透镜抽查：幂等/回滚/超时/并发/安全（重点：P2-12 幂等键、P-B4 令牌、P2-11 fail-closed）

### Layer 4 — 可观测性
- SkyWalking：trace 完整（**P-T1 异步插件启用后异步段是否挂上**）
- Prometheus：25 指标组 + 业务指标（P-D42 预注册后恒有数据）
- ES 日志：TCP 15044 实时链路

## 三、前置条件（已就绪）
- 15 微服务运行（19000-19016 health UP）
- 中间件 25 容器 healthy（试验机）
- 测试脚本已修复硬编码（test-2 下脚本：IP/端口/token 已更新——**但按方法论不以脚本为准，逐端点独立执行**）
- `/tmp/test_token.txt`（登录 token 基准）

## 四、执行记录
- `execution/` 按模块逐端点执行文件（L1 起，禁止 ✅ 占位）
- `review/` 测试中发现的问题记录（新问题编号延续 T- 系列）

---

## 五、控制台观察清单（人工观察，用户登录执行）

> 测试双轨制：**我的自动断言**（API/DB/MQ/ES 查询）+ **用户人工观察**（登录控制台看）。每个关键用例文档标注"🔍 人工观察"。

| 控制台 | 地址 | 账号 | 观察用途 |
|---|---|---|---|
| SkyWalking UI | http://21.130.247.89:8080 | 无 | 全链路 trace（跨服务 span、**P-T1 异步段**）|
| Kibana | http://21.130.247.89:15601 | elastic / Xhs@2026#Elastic | 日志检索（myxhs-logs-*，按 traceId 关联）|
| Grafana | http://21.130.247.89:13000 | admin / Xhs@2026#Admin | 指标看板（服务/业务指标）|
| Prometheus | http://21.130.247.89:19090 | 无 | 指标/规则原始查询 |
| RocketMQ Dashboard | http://21.130.247.89:18081 | 无 | topic/消息/消费进度（延时/重试/DLQ 验证）|
| XXL-Job Admin | http://21.130.247.89:18080 | admin / 123456 | 任务执行日志（手动触发回执）|
| Sentinel | http://21.130.247.89:8858 | sentinel / sentinel | 限流/熔断规则与命中 |
| Nacos | http://21.130.247.89:18848 | nacos / nacos | 配置/注册中心 |

> 云主机部署后地址改为云主机 IP。账号为部署默认值（安全项用户已决定不收紧）。
