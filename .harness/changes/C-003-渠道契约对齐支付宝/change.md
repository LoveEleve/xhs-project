---
id: C-003
slug: 渠道契约对齐支付宝
status: confirmed
created: 2026-10-17
updated: 2026-10-17
iteration: ITER-003     # 机器可读迭代协议：.harness/iterations/ITER-003-渠道契约对齐支付宝/
relations:
  relates_to:
    - slug: docs/学习/订单-讲解/06-支付联动
      reason: 渠道层形态收口（通知/查单/关单/退款按支付宝真实契约）；落地后补修订记录
    - slug: docs/学习/模块对照/04-支付
      reason: "渠道交互形态"线索的最终收口
    - slug: docs/学习/订单-改造/02-改造后文章修订记录
      reason: C-003 落地后补记修订条目（06/04-支付 相关）
  depends_on:
    - C-002
---

# C-003 渠道契约对齐支付宝

> **立项（2026-10-17）**：Owner 挑战——"回调/接口要按支付宝 SDK 文档来，要改造就彻底改造"。
> 已完成核验：支付宝官方文档（异步通知 / alipay.trade.query / alipay.trade.close / alipay.trade.refund 契约）+ 代码事实（两条回调路径、退款回调链、脚本引用面、跨模块影响面）。
> **范围锁定：支付宝单渠道**（微信删除；Mock(99) 保留为测试渠道）；**订单/库存零改动**（域间契约不变）。
> 规划详见 `.harness/iterations/ITER-003-渠道契约对齐支付宝/`（prd/solution/test）。

## 用户故事

作为支付域开发者，我想要把渠道层从"模拟器直调 service 的自造形态"升级为"支付宝真实契约形态"（HTTP 通知端点 + RSA2 验签 + 二次校验 + trade_status 四态 + 通知重试/ACK + 同步退款 + 先查后决），以便学习的是最终形态、接真渠道时只换密钥与实现，且回调安全与资金链路达到生产标准。

## 非目标（Out of Scope）

- 微信渠道（用户决策：单渠道——删除 `WechatPayStrategy` 及注册）
- 真 SDK jar / 网关联调 / 证书模式（无商户资质；仅做契约形态保真）
- 通知落库→异步消费模式（本项目处理段足够快；同步处理+幂等，记录理由）
- 订单/库存改动（零改动，仅回归核验）
- 平台管道深挖（X-Internal-Call / 白名单 / 权限——最小必要 + 记录，不作为设计题）
- 完整 25h 重试节奏（模拟器用简化可配节奏，保留语义）

## 验收标准

- AC-1 ~ AC-13 见 `.harness/iterations/ITER-003-渠道契约对齐支付宝/prd.md`（本变更走迭代协议）

## 边界情况

- 通知重试（同 notify_id）→ 业务幂等（CAS）→ 应答 success
- 验签失败 / 二次校验失败 → 返回 fail + 告警（不误处理）
- TRADE_CLOSED 双语义（未付超时 vs 全退后关闭）→ 按本地状态分流
- 通知重试上限后放弃 → 查单兜底（对账核查分支已立，保持）
- 换渠道 query(未付)→close 之间用户又付了 → close 失败再查 → 已付受理
- 退款调用异常（结果未知）→ 停留退款中 → 恢复任务同 out 键重试
- 本地已关闭(4) 收到迟到 TRADE_SUCCESS → 受理（保持 C-002 行为）

## 非功能需求

| 维度 | 指标 |
|------|------|
| 性能 | 通知处理同步段（CAS+发 MQ）保持短耗时；重试退避；台账扫描 SCAN 批处理 |
| 可靠性 | 幂等（CAS + 唯一索引 + out 键）；通知失败重试 + 查单兜底；"不知道"≠"没付" |
| 安全 | RSA2 真实验签（本地密钥对）+ 二次校验（金额/单号/商户）；敏感参数不落日志 |

## 设计约束

- 分阶段实施（契约层 → 端点 → 模拟服务器 → 链路切换 → 收口），禁止 big bang
- 契约层方案 B：新增 `alipay` 契约包隔离真实契约；`PayChannelStrategy` 仅做必要变更（删 `handleRefundCallback`、`refund` 返回同步结果）
- 生产标准：业务/工程/分布式/性能/微服务全维度；业务流程与结果正确优先
- 不改文章正文（修订清单更新至 `docs/学习/订单-改造/02`）

## 契约影响

- REST: 新增 `POST /api/payment/notify/alipay`（form 参数 + 明文 "success"/"fail"）；`/api/payment/callback/{payType}` 语义升级（测试触发口，路径保留）；`/api/payment/refund-callback/{payType}` 删除
- 模块间通信: 无变化（订单/库存与支付域的域间契约不动）
- 数据模型: 无 SQL 变更（交易台账/通知投递态在 Redis）
- 配置: 新增 `payment.alipay.mock.*`（appId/sellerId/两对密钥/notify URL/重试节奏/成功率/延迟）

## 影响面

- 模块/服务: my-xhs-payment（主）；my-xhs-gateway（两行白名单——管道类最小处理）；my-xhs-order / my-xhs-inventory（零改动，回归核验）
- 脚本: test-10-payment.py / test-order-state-race.py（删 refund-callback 步骤）；test-09 / e2e（零改动）
- deploy: `deploy/docker/my-xhs-deploy-zip/sql/migration/payment/` 补 `V2__pending_unique.sql`（C-002 遗留缺口）
- wiki: 接口协议 / 数据模型（归档时回流）

## 测试策略

- 先写失败测试：签名/验签（含空值/中文/篡改）、端点验签+二次校验、四态分流、台账状态迁移、通知投递重试、退款同步三分支、先查后决矩阵
- 边界测试：重复通知幂等、迟到成功、重试放弃、退款异常未知
- 回归：全模块基线保持（261+ 全绿）
