# 同区优先跨地域收益实测（netem 模拟，2026-09-18）

> 目的：补齐"同区优先带来多少收益"的实测数据（对标业界 10-30% 的响应时间收益）。
> 方法：单机内用 `tc netem` 给 zone-b 实例注入跨区延迟，对比网关同区优先开/关下的压测结果。

## 一、实验设置

- 拓扑：product zone-a（19006，本机）+ product zone-b（19026，第二实例，Nacos metadata.zone=zone-b）
- **跨区延迟模拟**：`tc qdisc/netem` 对 `lo` 上目的/源端口 19026 的流量注入 **25ms/方向**（TCP 握手+请求两跳 RTT ⇒ 端到端 +≈100ms；验证：health 8ms → 112ms）
- 调用方：网关（zone-a，`-Dmyxhs.current.availability.zone=zone-a`）→ `lb://my-xhs-product`
  - Phase A：同区优先 **ON**（same_zone 决策 20/20，instances total=2/same=1）
  - Phase B：同区优先 **OFF**（默认 LB，zone-b 参与分流 ≈50%）
- 压测：`wrk -t4 -c32` + JWT，经网关 `GET /api/product/spu/1`；30s（B 组另做 15-20s 复测）

## 二、结果

| 组 | RPS | P50 | P99 | 平均延迟 |
|---|---|---|---|---|
| A 同区优先 ON | **7,077** | 3.67ms | **37.34ms** | **6.07ms** |
| B 优先 OFF | 5,436 / 5,119（均值 5,278） | 3.90/4.82ms | 70.69/55.79ms | 13.11/10.75ms |
| 收益 | **+34%（7,077 vs 5,278）** | ≈持平 | **-33%~-47%** | **-44%~-54%** |

- 解读：P50 几乎不变（客户端 50% 命中本区）；**尾部与均值被跨区流量显著拉高**，同区优先主要消除"去程/回程跨区"的长尾。
- 与业界口径对齐：本项目实测收益 **≥10-30%**（因模拟 RTT 较大且直连占比更高）。

## 三、暴露的问题（真实发现）

1. **网关 zone 取值源不统一**：网关同区优先读取 JVM 系统属性 `myxhs.current.availability.zone`（缺省 `defaultZone`），**未接**服务侧同款 `ZoneLocator`（env/文件/IP 网段）；若不显式传参，网关记录 `decision=all, reason=invalid_zone` —— 优先策略"看起来启用但实际全量路由"。
   - 建议修复：网关读取 `MYXHS_ZONE`/`myxhs.availability.zone` 并复用 ZoneLocator 逻辑（或启动脚本统一注入属性）。
2. 测试注意：`localhost` 可能解析到 IPv6(`::1`)，绕过基于 IPv4 的 netem 过滤——压测必须用显式 IPv4。
3. 指标为懒计数：重启后无流量则 `myxhs_zone_*` 指标不出现，验证需先打流量。

## 四、边界

- 单机 netem 仿真（非真实跨机房/跨云链路），延迟均匀无抖动/丢包；绝对值不可直接外推，**收益方向与量级**可作参考；
- 单轮/两轮取样，P99 存在方差；网关与 product 在同一物理机（无真实跨区带宽限制）。
