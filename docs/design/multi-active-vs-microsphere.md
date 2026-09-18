# 自研 Zone 多活 vs microsphere-multiactive 对比（2026-09-18）

## 一、对方事实（仓库实测：8 模块 / 45 个 Java 类）
| 维度 | microsphere-multiactive |
|---|---|
| 定位 | 纯 zone 感知框架（README 自述：自动发现部署 zone + 同 zone 优先路由 + 跨 zone 兜底） |
| Zone 发现 | **AWS EC2/ECS 元数据、Eureka 实例信息自动发现**（ZoneLocator 多实现） |
| 路由 | Ribbon `ZonePreferenceServerListFilter` + Spring Cloud LB `ZonePreferenceServiceInstanceListSupplier` |
| 传播 | `ZoneAttachmentHandler/Listener/PreRegistrationHandler`（zone 附着到请求/事件） |
| 指标/健康检查 | 无（全仓库 0 处 micrometer / health） |
| 数据源/Redis/JDBC/Dubbo/Gateway | 无（0 处） |
| 工程 | CI/Codecov/版本化发布/14 个测试类 |

## 二、本期自研（同源概念，独立实现）
| 维度 | 我们的实现 |
|---|---|
| 同类 | `ZoneContext`/`ZoneResolver`/`ZonePreferenceFilter`/`ZonePreferenceServiceInstanceListSupplier`；LB 子 context 正确接线（`@LoadBalancerClients`） |
| 超出对方 | 路由指标与决策原因（9 分支）；健康检查（3s + liveness）实测 **RTO 3.06s**；zone 感知数据源（读本地/降级/**30s 自动恢复**）；平台 bug 修复（从库恢复、健康阻塞发布、LC 平局、release 多实例）；演练报告+单测 |
| 已补齐（对标） | **Zone 自动发现**：env/zone 文件/网段映射 + `EnvironmentPostProcessor`（注册前生效，Nacos 元数据自动携带）；**Zone 传播**：入站 `X-Zone`（Servlet 过滤器→ThreadLocal）+ 出站 Feign 自动带头（`myxhs_zone_propagation_total` 可观测） |
| 仍不如对方 | 无云元数据 locator（AWS/ECS/Eureka，本栈不需要）；**事件/MQ 传播未做**（仅 HTTP）；无 Ribbon 支持（不需要）；无标准化 artifact 与社区维护；仅单机仿真 |

## 三、结论
- **整体不如 microsphere**（成熟度/通用性/生态与生产验证）；我们的价值在"本平台栈内的落地、实测与修 bug"。
- 若产品化：优先引入其 **ZoneLocator（云元数据发现）** 与 **ZoneAttachment（zone 传播）** 思路，而不是继续自研扩展。
- 面试口径：可讲"评估并参考业界实现（microsphere）+ 结合本平台自研落地 + 演练数据 + 与成熟框架的差距认知"，避免"自研更强"的表述。
