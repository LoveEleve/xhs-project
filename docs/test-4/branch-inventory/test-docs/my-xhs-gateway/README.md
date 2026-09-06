# my-xhs-gateway 测试文档入口

本目录用于记录 `my-xhs-gateway` 后续测试，不记录源码分析结论。

## 测试依据
- 业务分析：`../../business-analysis/my-xhs-gateway/`
- 方法论：`docs/test-3/methodology/TEST-METHODOLOGY.md`
- L2/L3 补充：`docs/test-3/methodology/METHODOLOGY-L2L3-SUPPLEMENT.md`
- 历史踩坑：`docs/test-3/pitfalls.md`

## 测试层次
1. L1：入口鉴权、白名单、路由、响应状态
2. L2：Redis 黑名单/nonce、下游服务到达、日志与指标
3. L3：并发、重放、限流、超时、异常、Header 伪造、SSE/WebSocket
4. L4：TraceId、SkyWalking、Prometheus、ELK

## 当前测试文档状态
- 尚未执行具体测试用例
- 测试用例必须逐条记录实际请求、预期、实际结果和下游证据
- 不得用“服务健康”替代业务接口验证

## 后续建议文件
- `01-auth-and-whitelist.md`
- `02-routing-and-timeout.md`
- `03-hmac-replay-and-body.md`
- `04-rate-limit-and-resilience.md`
- `05-observability.md`
- `06-coverage-reconciliation.md`
