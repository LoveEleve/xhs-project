---
iteration: ITER-003
status: confirmed            # draft / confirmed
version: 1
created: 2026-10-17
updated: 2026-10-17
# 上游依赖：solution 未确认前，本文件不得进入 coding（见 .harness/rules/开发流程规范.md「阶段间接口」）
# 阶段间接口：REQ 编号一旦确认，禁止在不更新下游产物的情况下改写
---

# PRD: 渠道契约对齐支付宝

> 机器可读的迭代协议产物之一。本文件定义**业务需求与验收标准**，是 solution 的唯一事实来源。

## 背景与目标

C-002 完成后渠道层的现状（证据：代码核验 + 支付宝官方文档核验，2026-10-17）：

- **自造形态**：回调无 HTTP 端点——模拟器 `@Scheduled` **直调** `paymentService.handlePayCallback`（`PayCallbackSimulator:200`）；脚本走 `POST /api/payment/callback/{payType}`（JSON + 内部头）也是直调 service。无签名、无 form 参数、无 ACK/重试语义；
- **与真实契约的差距**（支付宝官方文档已核验）：异步通知=form 表单 + RSA2 验签 + 二次校验（out_trade_no/total_amount/seller_id/app_id）+ 明文 "success" 应答 + 重试 8 次（4m/10m/10m/1h/2h/6h/15h）+ notify_id 不变 + "通知+查单"双保险；换 out_trade_no 前应**先查后决**；退款为**同步返回**（fund_change）+ out_request_no"同号只退一次"；
- **退款回调整链将成死代码**：99 退款同步（`PaymentService:745`），1/2 才 `registerRefundCallback`（`:748`）——支付宝退款同步化后，`registerRefundCallback`/`simulateRefundCallback`/`/refund-callback` 端点/`handleRefundCallback` 全链无调用方；
- **模拟服务器缺交易台账**：`registerCallback(paymentNo, payType)` 只存时间戳，**没有金额**——而 notify 参数必须含 `total_amount`（二次校验也要比对）。

本迭代把渠道层升级为**支付宝真实契约形态**（单渠道）：HTTP 通知端点 + 真实验签 + 四态分流 + 通知投递/重试/ACK + 同步退款 + 先查后决。目标：学习即最终形态；接真渠道时只换密钥与实现。

## 非目标（Out of Scope）

- 微信渠道（删除）；真 SDK jar / 网关联调 / 证书模式（无资质）
- 通知落库→异步消费模式（同步处理段仅 CAS+发 MQ，够快；记录理由）
- 订单/库存改动（域间契约不变，零改动）
- 平台管道深挖（X-Internal-Call / 白名单 / 权限：最小必要 + 记录）
- 完整 25h 重试节奏（简化可配节奏，保留"次数上限 + notify_id 不变"语义）

## 需求（REQ）

| ID | 需求描述 | 优先级 |
|----|---------|--------|
| REQ-1 | **支付宝契约层与密钥体系**：新增 `alipay` 契约包（`AlipayTradeStatus` 四态枚举、通知参数模型、查单结果、退款同步结果）+ RSA2 签名/验签工具（**双向**：商户请求签名 + 支付宝通知验签）+ **两对本地密钥**（应用密钥对 / 支付宝密钥对，配置化）；`queryPayStatus` 四态→三值映射表显式化 | P0 |
| REQ-2 | **异步通知端点**（真实形态）：`POST /api/payment/notify/alipay`——form 参数 + RSA2 验签 + 二次校验（out_trade_no 存在 / total_amount 一致 / app_id+seller_id 一致）+ 明文 "success"/"fail" 应答 + **四态分流**（TRADE_CLOSED 双语义按本地状态分流） | P0 |
| REQ-3 | **模拟支付宝服务器**：交易台账（Redis：out_trade_no → 金额/标题/状态/通知投递态）；"用户付款"模拟（延迟+成功率）；通知投递器（参数构造 + 签名 + HTTP POST + ACK 判断 + 简化节奏重试，notify_id 不变、上限后放弃）；query/close/refund 契约（退款同步返回 fund_change + out_request_no 幂等）；故障注入（退款异常/通知失败可模拟） | P0 |
| REQ-4 | **渠道链路切换**：模拟器自动回调与脚本触发口均走**真实链路**（签名→HTTP→端点→验签→处理→ACK）；`/callback/{payType}` 路径保留重实现（1→真实链路；99→兼容直调）；**退款同步化**（refund 返回结果→成功/失败/未知三分支）；**退款回调整链删除**；微信渠道删除 | P0 |
| REQ-5 | **换渠道"先查后决"**（官方指引）：query 前一个 → 已付=受理收敛（不新建）/ 未付=close→新开 / 不可达=不切换（报错）；**close 失败再查**（已付受理 / 未付报错） | P0 |
| REQ-6 | **收口**：网关两条白名单加 notify 路径（管道类，两行）；脚本同步（test-10/race 删 refund-callback 步骤）；deploy zip 补 `V2__pending_unique.sql`（C-002 遗留）；文档修订记录；全模块回归 | P1 |

## 验收标准（AC）

| ID | 归属 | 验收标准 | 来源 |
|----|------|---------|------|
| AC-1 | REQ-1 | RSA2 签名/验签 roundtrip 正确（含空值剔除、中文/特殊字符）；篡改参数/错误密钥 → 验签失败 | 官方文档（验签规则） |
| AC-2 | REQ-1 | `queryPayStatus` 四态映射：WAIT_BUYER_PAY→0 / TRADE_SUCCESS\|TRADE_FINISHED→1 / TRADE_CLOSED→0 / 不可达→null，单测全覆盖 | 官方文档（trade_status） |
| AC-3 | REQ-2 | 验签通过 + 二次校验通过 + TRADE_SUCCESS → `handlePayCallback(true)` + 应答明文 "success"；TRADE_FINISHED 同 SUCCESS | 官方文档（通知契约） |
| AC-4 | REQ-2 | 验签失败 / 二次校验失败（金额不符、单号不存在、商户不符）→ 应答 "fail" + 告警日志，**不误处理** | 官方文档（二次校验） |
| AC-5 | REQ-2 | TRADE_CLOSED 双语义分流：本地 pending → 置失败（渠道确认关闭语义）；本地已成功(1)/已退款(3) → 幂等/退款收敛（不误置失败）；WAIT_BUYER_PAY → 忽略 | 官方文档（TRADE_CLOSED 双语义） |
| AC-6 | REQ-3 | 交易台账：createTrade 建立（金额/标题/trade_no/WAIT_BUYER_PAY）；状态迁移原子（WAIT→SUCCESS 仅一次） | 深 Review 发现 |
| AC-7 | REQ-3 | 通知投递：参数完整（含 total_amount/app_id/seller_id/notify_id）+ RSA2 签名 + HTTP POST + ACK 判断；未 ACK 按简化节奏重试（notify_id 不变），上限后放弃（台账记 undelivered，靠查单兜底） | 官方文档（重试策略） |
| AC-8 | REQ-3 | 查单/关单/退款契约：refund **同步返回**（fund_change）；**同 out_request_no 只退一次**（幂等）；故障注入可模拟退款异常/通知失败 | 官方文档（退款/幂等） |
| AC-9 | REQ-4 | 自动回调与 `/callback/1` 触发均走真实链路（签名→HTTP→端点→验签→处理→ACK）；`/callback/99` 兼容直调；脚本路径兼容 | 代码核验（引用面） |
| AC-10 | REQ-4 | 退款同步化三分支：成功→`handleRefundSuccessInternal`；失败→`markRefundFailed`；未知（异常）→停留退款中（恢复任务同 out 键重试）；退款回调整链删除（端点/扫描/接口方法/服务方法/常量/测试清理）；微信渠道删除 | 代码核验（745/748） |
| AC-11 | REQ-5 | 先查后决矩阵：已付→受理收敛（不新建）；未付→close→新开；不可达→不切换（报错）；close 失败→再查→已付受理/未付报错 | 官方文档（换单号指引） |
| AC-12 | REQ-6 | 网关两条白名单含 notify 路径（两行配置）；脚本更新（test-10/race 删 refund-callback 步骤）；deploy zip 补齐 V2 迁移 | 跨模块核验 |
| AC-13 | REQ-6 | 六模块全量测试基线保持全绿（261+）+ 新增用例通过 | 261 基线 |

## 边界情况

- 重复通知（重试同 notify_id）→ 业务幂等（CAS）→ 应答 "success"
- 本地已关闭(4) 收到迟到 TRADE_SUCCESS → 受理（保持 C-002 行为）
- 通知处理抛异常 → 应答 "fail"（渠道重试兜瞬时故障）
- 通知重试 8 次放弃 → 查单兜底（对账核查分支已立，保持）
- 换渠道 close 与用户付款竞态 → close 失败再查
- 退款调用异常（结果未知）→ 停留退款中（"不知道"≠"没退成"）
- 非 form 内容/缺参数通知 → 验签必失败 → "fail"

## 非功能需求

| 维度 | 指标 |
|------|------|
| 性能 | 通知处理同步段（CAS+发 MQ）保持短耗时（防重试风暴）；重试退避；台账扫描 SCAN 批处理 |
| 可靠性 | 幂等（CAS + 唯一索引 + out 键）；通知重试 + 查单兜底；"不知道"≠"没付/没退成" |
| 安全 | RSA2 真实验签 + 二次校验；密钥仅配置读取；敏感参数不落日志 |

## 依赖与影响面

- 模块/服务: my-xhs-payment（主）；my-xhs-gateway（两行白名单）；my-xhs-order / my-xhs-inventory（零改动）
- 外部 API: 新增 `POST /api/payment/notify/alipay`；`/callback/{payType}` 语义升级（路径保留）；`/refund-callback/{payType}` 删除
- 数据模型: 无 SQL 变更；Redis 键新增（台账/退款幂等/投递锁）
- 配置: `payment.alipay.mock.*`（appId/sellerId/两对密钥/notify-url/重试节奏/成功率/延迟）
- 涉及 wiki: 接口协议 / 数据模型（归档时回流）

## 人工决策点（已决策，2026-10-17——Owner 授权 AI 决策）

- **决策 1（范围）** = 支付宝单渠道：微信删除；Mock(99) 保留为测试渠道（同步直通，脚本依赖）。理由：Owner 明确"不要搞多渠道"；Mock 是测试夹具非渠道。
- **决策 2（架构）** = 契约层方案 B：新增 `alipay` 契约包隔离真实契约；`PayChannelStrategy` 仅做必要变更（删 `handleRefundCallback`、`refund` 返回同步结果）。理由：业务链路（超时处置/换渠道/幂等）零改动，防返工。
- **决策 3（验签）** = **真做**：本地两对密钥（应用密钥对 + 支付宝密钥对）；通知方向真实 HTTP + 真实验签；请求方向进程内 + 真实签名。理由：验签是渠道契约核心（面试深水）；"本地密钥对模拟支付宝公钥体系，流程与线上一致"。
- **决策 4（退款）** = 同步化 + 退款回调整链删除；"退款中"窗口测试改故障注入（退款异常→停留→恢复重试）。理由：支付宝退款同步是真实契约；死代码必须清理。
- **决策 5（触发口）** = `/callback/{payType}` 路径保留为测试触发口（1→真实链路；99→兼容直调）；真实 notify 端点无内部鉴权头（验签即鉴权）；网关白名单两行（管道类最小处理）。理由：脚本零改动 + 测试从此覆盖完整真实链路。
- **决策 6（处理时机）** = notify 同步处理 + 幂等（不做落库异步）；重试上限后靠查单兜底。理由：处理段仅 CAS+发 MQ；避免范围蔓延。
