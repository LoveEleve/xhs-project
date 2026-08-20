# Q-001 服务端口暴露范围与内部令牌威胁模型

## 问题

`GatewayAuthTrustFilter` 明确按“19001+ 服务端口可直连”设计，但当前部署资料同时显示安全组建议只开放 gateway 19000，Prometheus 又直接采集 19001+。

因此需要确认：

1. 19001+ 是否只对同机/内网开放
2. 是否存在办公网、测试机、容器网络可直达业务端口
3. `ADMIN_TOKEN` / `INTERNAL_TOKEN` 的持有者范围
4. 是否把宿主机或同网段失陷纳入威胁模型

## 当前证据

- `GatewayAuthTrustFilter.java:26`：代码明确按服务端口直连场景建模。
- `config/deploy-cloud/DEPLOY-README.md:81`：部署建议只暴露必要端口，典型是 gateway 19000。
- `config/prometheus/prometheus.yml`：Prometheus 直接抓取多个 19001+ 服务端口。
- `start-all.sh:18-24`：启动时可随机生成并注入 admin/internal token。
- `my-xhs-order/.../InternalCallFeignConfig.java:18`：代码仍保留一个公开可读的 fallback 字符串，虽生产 yml 通常提供空配置覆盖它。

## 为什么不能直接定性

如果网络层严格阻断所有非 gateway 到业务端口的访问，F-001/F-002 的现实攻击面会被显著压缩；如果同网段任意应用均可访问，则两个问题都属于高风险边界漏洞。

## 需要确认

- 运维/部署负责人：真实安全组、容器网络、服务监听范围
- 测试负责人：是否允许通过业务端口执行内部接口测试
- 业务负责人：是否要求注销/删号对所有路径即时失效

## 验证方法

使用无破坏性的 GET/actuator 探活与网络策略检查，不调用管理写接口；确认业务端口从 gateway 所在主机、Prometheus 主机、普通业务网段的可达性。