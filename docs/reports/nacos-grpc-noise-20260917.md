# Nacos gRPC（19848）报错排查（2026-09-17）

## 现象
xhs-ai 日志反复出现：
`Server check fail, please check server 192.168.0.142 ,port 19848 is available`
（logger: `com.alibaba.nacos.common.remote.client.grpc.GrpcClient`）

## 排查证据
1. **端口是通的**：`192.168.0.142:19848` TCP OPEN（Nacos 2.x gRPC 端口 = 主端口 18848 + 1000）；
2. **ES 按天分布**：报错集中在部署/重启日（如 09-06 752、09-08 800、09-12 1212 条），非持续故障；
3. **近 30 分钟仅 2 条**：恰为 xhs-ai 重启瞬间产生；启动日志随后出现 `Success to connect to server [192.168.0.142:18848]` 并注册成功；
4. 仓库配置无 `19848` 字面值——该端口由 Nacos 客户端按 `18848+1000` 推导。

## 结论
**启动期 gRPC 建连重试噪声，不是故障**。客户端在 Nacos 尚未就绪/短连拒绝时逐次重试并打日志，连上后恢复正常。
- 非问题项：端口/服务端配置无需修改；
- 可选优化：部署顺序上"先等 Nacos healthcheck 通过再拉起微服务"，可显著减少该类日志量；
- 监控口径：`GrpcClient` 的 "Server check fail" 不应配成告警（否则每次重启都会误报）。
