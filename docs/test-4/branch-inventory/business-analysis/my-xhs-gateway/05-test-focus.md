# gateway 测试重点

## 1. 鉴权与安全
- JWT 令牌解析与校验 (含黑名单)
- HMAC 签名校验 + 时序攻击测试 (伪造 nonce / 篡改 Header)
- CORS 安全策略 (Origin 白名单校验)
- 压测标记防伪造

## 2. 路由与分发
- 14 个核心业务服务路由 + `recommend-service` + `ai-app-service`
- AI RewritePath、query 保留、SSE 长连接与实际 timeout
- 灰度/版本路由的属性透传；当前不宣称实例筛选闭环已完成

## 3. 限流与熔断
- routeId 级 Sentinel 限流
- 核实用户级限流是否真正接线，不能只凭 KeyResolver 判断已实现
- Nacos 动态规则是否存在、fallback 是否覆盖动态规则
- 连接池调优参数验证；区分限流与熔断，当前源码不能直接证明已实现熔断

## 4. 日志链路
- TraceId 全链路透传验证 (SkyWalking UI)
- 入站 JSON 日志落盘完整性 (Kibana/ES)

## 5. 当前阶段说明
本文件暂不执行接口测试；这里只登记后续验证重点。当前优先级是先完成源码问题确认和必要修复，再按 L1→L4 执行测试。

## 6. 对账结果
- 候选文件池共 20 个：顶层文件 3 个、resources 2 个、Java 15 个
- 20 个文件均已进入 `06-source-deep-analysis.md` 的阅读范围
- `target/` 是构建生成目录，不纳入源码分析
- 当前仍有需要运行态确认的事项：自定义 WebHandler 是否实际注册、AI 路由 metadata timeout 是否真正生效、Sentinel 动态数据源是否接线
- 不能把“文件已阅读”表述为“所有运行行为已验证”
