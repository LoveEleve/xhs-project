---
id: C-016
slug: 支付预支付参数补齐
status: done
created: 2026-10-19
updated: 2026-10-19
relations:
  relates_to:
    - slug: docs/学习/支付-讲解/01-渠道契约层
      reason: 用户指出"按真实契约应返回预支付参数"——响应契约补齐
  depends_on: []
---

# C-016 支付发起响应契约补齐（预支付参数）

> **缺口（用户 review 发现）**：`AlipayTradeClient.tradeCreate` 返回的 `tradeNo` 只打日志——`Payment`/`t_payment`
> 没有 trade_no 列、`PaymentVO` 没有 tradeNo/支付参数 → **客户端拿不到任何拉起支付的凭据**（真实世界这一步必须返回
> orderStr/qr_code/trade_no）。"没有客户端"不是理由：响应契约应成立（C-003 契约对齐原则的遗漏）。

## 修复

1. `t_payment` 补 `trade_no`（迁移 `V3__trade_no.sql` + 6 基线副本）；
2. `PaymentVO` 增 `tradeNo` + `payParams`；
3. 支付宝 `payParams` = **alipay.trade.app.pay 的 orderStr**（应用私钥本地签名 + URL 编码，可直接交 SDK）；
   Mock = 等价 JSON 载荷；
4. 复用支付单/状态查询重建参数（orderStr 带 timestamp，时间敏感不可缓存）；通知回调回填 trade_no（历史单/落库失败窗口）。

## 证据

- payment **76 全绿**（+3：orderStr 契约/签名可验、策略委托、pay 响应带 tradeNo+payParams+落库）；
- 十五模块回归 726 全绿。
