# 部署/配置 Review 方法论（三层验证法）

> 2026-08-13 沉淀 | 起因：多轮静态 review 均称"没问题"，对方真实环境实测暴露 20+ 运行态问题。
> **核心原则：验证深度必须与结论强度匹配——禁止"没问题"，只允许标注验证层级。**

## 一、三层验证法

| 层级 | 能力 | 验证内容 | 谁做 |
|---|---|---|---|
| **L0 静态自洽** | 读文件 | 文件存在/语法合法/路径挂载存在/引用一致 | 无环境时 |
| **L1 框架语义** | 读文档/源码 | 配置项**单位/解析行为/工具可用性/版本兼容**（查官方文档、反编译、registry）| 无环境时 |
| **L2 运行态实测** | 真实执行 | 拉起/配置解析生效/指标出数/告警触发/故障演练 | **必须有环境** |

**结论措辞规范**：
- 只做了 L0 → 说"**静态通过，运行态未验证**"，**禁止**"没问题/可以部署"
- 做到 L1 → 说"**L0+L1 已核（列明核过的项），L2 待实测**"
- L2 完成 → 才允许"**实测通过**"

## 二、部署包可运行性清单（L1 必查项，按类别）

### 1. 镜像类
- [ ] 镜像 tag 存在性（**查 Docker Hub registry API**，不猜）
- [ ] 镜像内工具可用性（curl/wget/shell/pgrep——**查镜像 Dockerfile 或 registry manifest**；scratch 镜像无 shell）
- [ ] 默认端口（redis-exporter 9121、alertmanager 9093 等——**查官方文档**，规划端口需显式 listen-address）

### 2. 配置解析类（最容易静默失效）
- [ ] Java Properties：**行内 `#` 属于值**——注释必须独立行（RocketMQ broker.conf 实测踩坑）
- [ ] YAML：重复键（后者覆盖）、布尔/数字类型、`${ENV:default}` 占位符
- [ ] ini/my.cnf：`#` 是注释符（密码含 # 会截断——mysqld-exporter 实测）

### 3. 框架行为类（看文档/源码，静态猜不到）
- [ ] SkyWalking `SW_TRACE_SAMPLE_RATE` 单位=**万分比**（10000=100%；10=0.1% 实测采不到）
- [ ] Grafana provisioning：`${DS_PROMETHEUS}` 占位符**不自动替换**；看板 gridPos.x 重复=面板堆叠
- [ ] Prometheus 规则/配置变更**不热加载**（需 reload/restart）
- [ ] Boot 3.x `http_server_requests_seconds` 默认**无 _bucket**（histogram 需显式开）；micrometer 1.13+ **无 tomcat.threads**
- [ ] exporter 版本兼容（rocketmq-exporter 4.9 客户端 ≠ 5.1.4 broker；es-exporter v1.7 移除 --es.cluster_settings）

### 4. 数据/语义类
- [ ] 指标名与代码埋点核对（面板/告警引用必须存在——实测 Prometheus 查 count）
- [ ] 采样/保留策略（trace 采样率、日志 TTL、ILM）

## 三、流程建议（每轮 review 的固定步骤）

1. **声明能力边界**：本轮能做什么（静态/语义/实测）——先讲清楚，别让"没问题"误导
2. **L0 全量核对**：文件存在/语法/路径/引用（脚本化）
3. **L1 逐项查证**：每个配置项按"镜像类/解析类/框架类"清单查官方来源
4. **L2 边界标注**：明确哪些项"必须实测"，交付给有环境的一方（或列出实测命令）
5. **结论分级输出**：L0 通过项、L1 已核项（附依据）、L2 待实测项——**三栏清单**

## 四、回看：本轮哪些本可 L1 避免
- broker.conf 行内注释 → 查 Java Properties 文档即可（我写了它）
- 采样率 10 → 查 SW 官方配置文档
- 镜像不存在/无 curl → 查 registry/manifest
- ${DS_PROMETHEUS} → 查 Grafana provisioning 文档
- **L0/L1 到位可避免约 60% 的实测问题**；剩余 40%（镜像拉取、协议兼容、性能）必须 L2

## 五、执行纪律
- 本文档纳入每次部署/review 前置阅读
- "没问题"三字禁用，替代：`L0+L1 通过，L2 待实测` 或 `实测通过`
- 新踩的坑：先归入本清单（镜像类/解析类/框架类），再写 pitfalls
