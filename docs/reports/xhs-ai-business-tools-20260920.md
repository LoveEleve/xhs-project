# xhs-ai 业务工具上线：运维+业务双场景（2026-09-20）

> 目标：把 Agent 从"运维 Copilot"扩为"运维+业务"双场景——新增 4 个业务只读工具，真实数据实测，补齐评测用例。

## 一、新增能力（只读，全部走审计）
| 工具 | 参数 | 返回 | 典型问题 |
|---|---|---|---|
| `order_trace` | userId + orderNo | 订单状态/明细/状态事件链/支付单/退款单/库存预扣/关联通知 | "这单为什么没发货/退款到哪了" |
| `order_stats` | hours(默认24) | 订单量/状态分布/GMV/支付分布/退款成功金额（16 分片全量聚合） | "今天成交额多少" |
| `inventory_query` | skuId | 可用/锁定/冻结库存 + TCC 冻结明细 + 补偿记录 + 商品名/价 | "库存为什么少了/下不了单" |
| `coupon_query` | userId | 最近20张券：券名/面额/门槛/有效期/状态/使用订单 | "用户说券不能用" |

安全边界：`myxhs_ai` 库用户对业务库**仅授 SELECT**；订单分片路由与订单服务同款哈希（`Long.hashCode&0x7fffffff`，db=h%4，table=(h/4)%4，三例抽查全对上）。

## 二、真实数据实测（首轮抓到 3 个问题，2 修 1 记录）
| 问题 | 性质 | 处理 |
|---|---|---|
| `order_trace` 返回 `json serialize failed` | **真 bug**：ToolSupport 的 ObjectMapper 未注册 JavaTimeModule，MySQL DATETIME→LocalDateTime 无法序列化 | 已修（JavaTimeModule + 关时间戳序列化） |
| `inventory_query` SQL 报错 `Unknown column 'id'` | **真 bug**：`t_tcc_freeze_detail` 无 id 列 | 已修（ORDER BY created_at DESC） |
| 模型答案中误报工具参数（实际审计记录正确） | 模型表述问题 | 已在工具描述加纪律："userId 必须取问题中明确给出的值，不得用会话用户ID" |

**复测结果（全部真数据）**：
- `order_trace` 39s：ORD2026092012504092242420044 = 已付款(1)，分片 db0.t_order_0，下单 12:50:40 → 支付 12:50:42（2 秒），无退款；事件链 ORDER_CREATED→ORDER_PAID ✓
- `order_stats` 16s：24h 55 单；GMV ¥4,771；退款成功 ¥3,801；状态分布 1-已付款11/3-已完成7/4-已取消21/5-已退款16 ✓
- `inventory_query` 19s：SKU1 连衣裙-红色-S ¥199；可用45/锁定4/冻结0 —— **与 DB 真值逐项一致** ✓
- `coupon_query` 23s：目标用户 count=0（审计参数正确）✓

## 三、回归与用例
- xhs-ai E2E：**10/10** 通过。
- `eval/tool-cases.yaml` 新增 TS-15..18（4 个业务工具的选择用例）。
- `eval/answer-cases.yaml` 新增 BIZ-01..04（business 类型答案用例，含事实关键词）。
- 已提交 `365770d8`（9 文件，+546 行），推送 4 refs。

## 四、定位升级口径（面试用）
"Agent 平台底座（模型网关/上下文/HITL/审计/评测）+ 双场景落地：智能运维（积压/死信/日志/指标/代码定位）+ 业务诊断（订单全链/经营指标/库存/券）"。
业务侧价值点：`order_trace` 一条链跨 4 个域（订单/支付/库存/通知），分片路由与主服务一致——证明 Agent 不只是"读日志"，而是能落业务域做诊断。
