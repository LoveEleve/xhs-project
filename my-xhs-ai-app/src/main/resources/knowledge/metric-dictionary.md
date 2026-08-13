# 指标字典（D0 / D1 前置）

> 版本：**v0.2 定稿** | 日期：2026-08-10 | 状态：✅ **业务方已拍板**（§5 纪要）
> 定位：6 场景（`D0-scenarios.md`）所需指标的**口径/维度/时区/来源/就绪度**定义——工具输出与 Agent 答案的验收基准（去风险盲点 B1）。
> 原则：口径推荐基于代码/文档证据；**业务语义项（口径）需业务方确认**，技术项（来源/时区）我们自定。
> 每个指标含：推荐口径 + 证据 + 是否需确认。

---

## 0. 全局约定（先定）
| 项 | 值 | 需确认？ |
|----|-----|:--:|
| 时区 | Asia/Shanghai（与业务库一致）| 否 |
| 数据延迟容忍 | 指标工具返回 `asOf`（数据时间），口径标注"截至 X 时" | 否 |
| 时间窗 | 诊断默认对比：当前窗口 vs 上一同长窗口（或指定基线）| ✅ 已确认（2026-08-13）|

---

## 1. 能力面 A —— 业务指标

### A1. 订单量下降（漏斗）
| 指标 | 推荐口径 | 来源 | 就绪度 | 需确认？ |
|------|---------|------|:--:|:--:|
| **下单量**（漏斗第3环）| 按 `t_order.created_at` 计数（排除 deleted=1；**含已取消/已退款**——订单创建即入漏斗）| `t_order_0~3`（my_xhs_order）| ✅ | ✅ 已确认（2026-08-13：含取消/退款）|
| **支付量**（第4环）| `t_payment.status=1`（成功）按 paid_at 计数 | `t_payment` | ✅ | 否 |
| **商品浏览**（第1环，sku）| 需新增 sku 维度商品浏览事件（当前无）| ❌ 缺（A5 缺口）| ❌ | ✅ 已确认（2026-08-13：电商漏斗用 sku 商品浏览，与内容曝光分开）|
| **加购量**（第2环）| 需新增加购事件流（t_cart_item 仅当前态）| ❌ 缺（A5 缺口）| ❌ | 否 |
| **漏斗转化率** | 各环节量/上一环节量（浏览→加购→下单→支付 4 段）| 依赖上面 | ⚠️ | ✅ 已确认断点定义 |

> **待确认**：漏斗第一环"浏览"是 **sku 商品浏览**（电商域，需新增）还是复用现有**推荐流曝光**（note 域，语义不匹配）？——推荐：**电商漏斗用 sku 商品浏览**，与内容曝光分开。**【已拍板：sku 商品浏览，见 §5#2】**

### A2. 支付成功率下降
| 指标 | 推荐口径 | 来源 | 就绪度 | 需确认？ |
|------|---------|------|:--:|:--:|
| **支付成功率** | **成功 / (成功 + 失败)**（分母排除 0待支付/3退款）| `t_payment.status`（代码实证：0待支付/1成功/2失败/3退款）| ✅ | ✅ 已确认（2026-08-13）|
| 分渠道成功率 | 按 `pay_type` 分组（**1支付宝/2微信/99 Mock**——实测修正）| `t_payment.pay_type` | ✅ | 渠道为 Mock 须注明 |
| 失败原因分布 | 需 `fail_code/fail_stage`（当前缺——t_payment 无 fail 字段实证）| ❌ 缺（A1 缺口）| ❌ | 否 |

> **注意**：`pay_type` 真实语义 = **1 支付宝 / 2 微信 / 99 Mock(模拟支付)**（`PaymentService.isMockMode`: `payType==99`，真实数据以 99 为主），非 1/2 二值；归因结论须注明渠道语义。另：**asOf 漂移**——分母排除 0待支付，待支付会随时间转 1/2，同窗口历史 rate 会漂移，须返回 asOf 并注明"按查询时刻已决支付计"。

### A3. 内容互动骤降
| 指标 | 推荐口径 | 来源 | 就绪度 | 需确认？ |
|------|---------|------|:--:|:--:|
| 每日新增互动 | `t_user_behavior` 按 created_at 统计 点赞(3)/收藏(4)/评论(5)/分享(6)（**API 7 值枚举**）| `t_user_behavior`（**content 库**，P1 修复后可用）| ✅ | ✅ 已确认（2026-08-13）|
| 曝光（推荐流）| `behavior_type=1`（代码=曝光）| `t_user_behavior` | ✅ | ✅ 已确认（曝光 vs 浏览：曝光）|
| 曝光（关注流）| 需新增（`getFollowFeed` 上报，home 模块当前无上报实证）| ❌ 缺（A6 缺口）| ❌ | 否 |

> **待确认**：`behavior_type` 枚举统一（**推荐：代码 7 值枚举** 1曝光…7停留，修热榜 SQL/注释）；曝光分推荐流/关注流两表面。
> **【已拍板 2026-08-13】**：统一为 **API 7 值枚举（1曝光 2点击 3点赞 4收藏 5评论 6分享 7停留）**；**仅修 t_user_behavior 表注释**（当前 6 值错位）；**热榜 SQL 无需修**（RecommendComputeJob/ItemCF 消费语义与 API 一致，backend-investigation-behavior-type.md 原"写读不一致"结论已勘误）。

---

## 2. 能力面 B —— 技术指标（多已由部署包就绪）

| 场景 | 指标 | 推荐口径 | 来源（已部署）| 就绪度 |
|------|------|---------|------|:--:|
| B1 | 慢查询数/耗时 | `slow_query_log`（≥0.5s 已开）| MySQL slow log | ⚠️ 管道待确认 |
| B1 | 死锁次数 | `mysql_innodb_deadlock_total`（deadlock 脚本）| node textfile | ✅ |
| B2 | 消费积压 | `rocketmq_consumer_*`（lag/tps）| textfile 指标 | ✅ |
| B2 | 死信数 | `%DLQ%` topic | MQ（需 DLQ 消费者=B4缺口）| ⚠️ |
| B3 | 5xx 错误率 | `http_server_requests` status_group | Prometheus | ✅ |
| B3 | P99 延迟 | `http_server_requests_seconds` | Prometheus | ✅ |
| B4 | 复制延迟 | `mysql_slave_status_seconds_behind_master` | mysqld-exporter-slave 9105 | ✅ |
| B4 | Io/Sql Running | `mysql_slave_status_*_running` | 同上 | ✅ |

> B 面多为确定性指标，无需业务口径确认；**慢查询/error log 管道**：slow log 已开（主 0.5s/从 1s，路径 /var/lib/mysql/slow.log），**进 ES 管道待云主机部署时接入**；error log 保持容器 stderr（docker logs 按需抓取）。**DLQ 死信监控**：rocketmq-metrics.sh 已补 `rocketmq_dlq_topics`/`rocketmq_retry_topics`（2026-08-13）。

---

## 3. 统一"待确认清单"（业务方拍板，收口所有决策）—— ✅ 全部已确认（2026-08-13）

| # | 项 | 拍板结论 | 依据 |
|:--:|----|--------|------|
| 1 | **behavior_type 枚举统一** | ✅ 用 **API 7 值枚举**（1曝光 2点击 3点赞 4收藏 5评论 6分享 7停留）；**仅修表注释**，热榜 SQL 不修（写读一致实证）| BehaviorRequest 注释 + RecommendComputeJob/ItemCF 消费语义 + 表注释三套对比 |
| 2 | **漏斗第一环"浏览"** | ✅ 电商漏斗用 **sku 商品浏览**（新增事件），与内容曝光分开 | D0-scenarios A1 领域错配修正 |
| 3 | **曝光两表面** | ✅ 关注流自建 + 推荐流复用 | A3 |
| 4 | **取关事件点** | ✅ 接受 **t_follow 无历史留存**（表仅 user_id/follow_user_id/created_at，取关即 DELETE 无痕迹）；粉丝流失只能**当前态对比**；关注关系权威=Redis | t_follow 建表实证 |
| 5 | **退款商品明细** | ✅ `t_refund_item` 子表（跨多商品）| 商品退款率 |
| 6 | **事件表归属库** | ✅ **analytics 库统一**（t_like/t_favorite 同域）——**例外：t_user_behavior 已在 content 库**（search 数据源=content 库的既定架构，P1 修复对齐，2026-08-13）| 运行态实证 |
| 7 | **支付成功率分母** | ✅ 成功/(成功+失败)，排除 0/3 | t_payment.status 代码实证 |
| 8 | **下单量口径** | ✅ 排除已删，**含取消/退款**（订单创建即入漏斗）| t_order deleted+status 实证 |
| 9 | **下降判定基线** | ✅ 当前 vs 上一同长窗口 | 全局约定 |
| 10 | **慢查询/error log 管道** | ✅ slow log 已开（主 0.5s/从 1s）；**进 ES 管道待云主机部署接入**；error log 保持 stderr | compose 实证（本轮补阈值与路径）|

> 每项都给推荐 + 理由，业务方只需"确认/改"。

---

## 4. 下一步
1. ✅ 业务方确认 §3 清单（2026-08-13 全部拍板）。
2. 指标字典定稿 → `order.query_volume` 等真实工具按口径实现（D1）。
3. 每个指标固化 `definitionVersion`（如 `order.order_volume/v1`），工具输出带 `definitionVersion/asOf/window/source`（对接 DAD §4.3 工具契约）。

---

## 5. 拍板纪要（2026-08-13，业务/技术负责人）
- 上述 §3 十项全部确认（推荐值即最终口径）
- **勘误**：`backend-investigation-behavior-type.md` 的"热榜读库错位/写读不一致"**不成立**——RecommendComputeJob:79/426-430 消费语义（3 点赞 4 收藏 5 评论 6 分享 7 停留+时长）与 API 写库枚举**一致**；仅建表注释（6 值错位）需修
- **P1 修复**：`t_user_behavior` 原建在 my_xhs_analytics，但 search 服务数据源=my_xhs_content → 写入 1146 失败、行为链路全丢、推荐计算降级跳过——**已在 content 库建表（7 值枚举注释）+ 删 analytics 空表 + init-all.sql 修正 + 运行态验证上报落库成功**（上报端点实测 `/api/recommend/behavior`）
- **技术就绪度更新**：A3 互动/曝光指标由 ⚠️ 转 ✅（行为链路可用）；B 面 DLQ 指标已补；slow log 阈值/路径已定
