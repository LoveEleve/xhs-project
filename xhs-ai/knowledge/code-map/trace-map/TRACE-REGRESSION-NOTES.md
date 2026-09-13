# Trace Regression Notes

## 目标

用真实 trace 样本覆盖 requestId/traceId 诊断的两种关键 verdict：

- `complete`：多服务链路完整、无入口异常
- `uncertain`：存在真实异常信号，不能冒充根因已证实

## 样本

### 1. clean multi-service
- traceId: `5304dc5a8afb4741b8bc74cee49c3980`
- route: `gateway -> order -> payment`
- expected:
  - `source=remote-es`
  - `verdict=complete`
  - `verificationStatus=evidence_sufficient`
  - `hitServices=[gateway, order, payment]`

### 2. anomaly multi-service
- traceId: `a429df1005fa43c48d156bd16564deff`
- route: `gateway -> order -> payment`
- anomaly:
  - gateway nonce/hmac 异常
- expected:
  - `source=remote-es`
  - `verdict=uncertain`
  - `verificationStatus=hypothesis_only`
  - `suspiciousEvents` 非空

### 3. single-service anomaly
- traceId: `f0d7831997f5442ebacebcb7d2aed1d3`
- route: `order only`
- expected:
  - `verdict=uncertain`
  - `verificationStatus=hypothesis_only`

### 4. no evidence
- traceId: `00000000000000000000000000000000`
- expected:
  - `verdict=uncertain`
  - `verificationStatus=missing_evidence`

## 验收点

1. `traceDiagnosis` 必须对外返回
2. `reviewerMode` 必须存在
3. `complete` 仅在多服务且无异常时出现
4. 有异常的多服务 trace 仍保持 `uncertain`
5. 前端不解析 `finalAnswer`，直接消费 `traceDiagnosis`
