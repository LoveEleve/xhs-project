# 第44题 | 组件深度拷打：Nacos

> 难度：★★★★★｜频率：★★★★★｜区分度：高
> 关键词：AP/CP、Distro/Raft、1.x→2.x gRPC、临时/持久实例、健康检查、配置外置真实生效

## 问题
Nacos 怎么做注册中心和配置中心？服务挂了会怎样？配置改了怎么生效？为什么选它？

## 面试可讲版（五段式）

**① 原理层（两个中心，两套一致性）**
- **注册中心（AP）**：Distro 协议（写任意节点→异步复制），保可用性；**临时实例**（默认）
  靠客户端心跳（5s/15s/30s 三级），超时剔除；**持久实例**（`ephemeral=false`）由服务端主动探测；
- **配置中心（CP）**：Raft（derby/内置）或 MySQL 持久化，保一致性；配置有**版本/MD5**；
- **1.x → 2.x 架构变化**：1.x 是 HTTP 短轮询 + UDP 推送；2.x 改为 **gRPC 长连接**（主端口+1000，
  本环境 18848→**19848**），推送更实时、连接更少；
- **健康检查三态**：`healthy`（心跳/探测）、`enabled`、`weight`——LB 侧（如 Spring Cloud LoadBalancer
  + NacosNamingService）按健康+权重选实例。

**② 本项目用法（运行态）**
- Nacos **2.3.2 standalone**（18848，容器 `my-xhs-nacos`），命名空间 `my-xhs`；
- **16 个服务注册**（15 微服务 + xhs-ai），实例 metadata 带 `zone`（ZoneLocator 自动发现，26 题）；
- **配置中心**：`my-xhs-common.yaml`（datasource/redis 密码+端口）、`my-xhs-gateway.yaml`（JWT/HMAC 密钥）、
  `my-xhs-gateway-sentinel-flow.json`（网关限流规则，Sentinel DataSource 动态读取）；
- 客户端本地快照：`~/nacos/config/fixed-my-xhs-192.168.0.142_18848_nacos`（Nacos 不可用时的 failover）。

**③ 真实事故：配置"写了但没生效"（本次修复，强烈推荐讲）**
- **现象**：网关引用 `my-xhs-common.yaml`/`my-xhs-gateway.yaml`，但 Nacos 里 404；深挖发现
  15 个服务的 `spring.cloud.nacos.config.shared-configs` **全部是死配置**——服务一直靠本地 yml fallback 运行；
- **根因**：Spring Cloud 2020+ / SCA 2021+ 默认关闭 bootstrap 上下文，**`shared-configs` 失效**，
  必须用 `spring.config.import: nacos:...`；项目 SCA 版本 **2023.0.1.2** 却沿用了旧写法；
  网关更彻底：pom 里连 nacos-config 依赖都没有；
- **修复**：15 服务改为 `optional:nacos:my-xhs-common.yaml?group=DEFAULT_GROUP&refreshEnabled=true`
  （`optional:` 保证 Nacos 挂了不阻塞启动）；补 14 个服务的 **config namespace**（此前只有 discovery 有，
  config 默认 public）；网关补依赖；移除死配置块；
- **二次踩坑**：首次接线后日志 `config[...] is empty`、Nacos 请求日志 **code=300**（命名空间错），
  补 `namespace: my-xhs` 后 **code=200**；
- **验证**：15/15 服务日志出现 `[Nacos Config] Load config[...] success` + `Listening config`；
  Nacos config-client-request.log 连续 15 次 `get tenant=my-xhs code=200`；全量重建发布 15/15 健康。

**④ 高可用与边界（诚实披露）**
- **单节点 standalone**：无集群仲裁价值；生产应 3 节点 + 外置 MySQL；
- **Nacos 挂了会怎样**：① 已注册服务间调用——客户端本地服务列表缓存，短期可用；
  ② 配置——本地快照 failover + 本地 yml fallback（`optional:` 不阻塞启动）；③ 新实例无法注册；
- **无鉴权**（`nacos.core.auth.enabled` 未开）、密码明文（P-D1）——已知安全债，靠安全组限制；
- **gRPC 启动噪音**：重启日日志集中出现 `Server check fail ... port 19848`，实为建连重试，
  非故障（报告已结论），已明确**不配告警**；优化方向："先等 Nacos healthcheck 再拉起服务"。

**⑤ 拷打追问**
1. **"AP 还是 CP？"** 注册中心 AP（Distro，可用性优先，脑裂时各节点数据可能短暂不一致）；
   配置中心 CP（Raft/MySQL，一致性优先）。**分区时注册可降级、配置不可乱读**——这是设计取舍。
2. **"临时实例和持久实例区别？"** 临时：客户端心跳，断连剔除，适合微服务；持久：服务端探测，
   保留实例（如 DNS 场景）。本项目全部默认临时实例。
3. **"配置怎么推到应用？"** 2.x 长连接推送 + 客户端监听；Spring Cloud 侧 `refreshEnabled=true`
   会让 `@RefreshScope` bean 重建（本项目未做运行时热更演练，边界说明）。
4. **"为什么不用 Eureka/ZK/Consul？"** Eureka 停止维护且无配置中心二合一；ZK 偏 CP 运维重、
   注册场景不需要强一致；Consul 能力接近但国内生态/文档弱。Nacos 一套解决注册+配置+控制台，
   和 Spring Cloud Alibaba 同栈——**生态与运维成本驱动的选择**。
5. **"保护阈值/雪崩防护？"** 健康实例比例低于阈值时全量返回（保调用方可用）；客户端侧还有
   Feign 超时+降级兜底。
6. **"namespaces/group/dataId 怎么用？"** namespace 隔离环境（本项目 my-xhs）；group 业务分组；
   dataId 配置集。隔离级别：namespace > group > dataId。
7. **"注册中心能不能停下来？"** 短期能（客户端缓存），长期不能（新实例、扩容、故障摘除失效）——
   所以生产要集群 + 监控。

**⑥ 话术**
> "Nacos 我们一套当注册中心加配置中心：注册走 Distro AP、临时实例心跳；配置走 CP。2.x 用 gRPC 长连接，主端口加 1000。最有价值的一个坑：我们发现 15 个服务的 shared-configs 在 SCA 2023 上全是死配置，一直靠本地 yml 跑；换成 spring.config.import 的 optional:nacos 写法，又踩了 config 命名空间默认 public 导致 code=300，补齐后 15 个服务全部 Load config success、Nacos 侧 200，全量发布验证。这让我对'配置中心接入没生效'这类静默失败有了完整的排查经验。"

## 发散追问地图（横向）
- 注册中心：心跳/健康检查、权重与灰度（25 题）、保护阈值、就近路由（26 题）。
- 配置中心：版本/回滚、灰度发布、加密配置、监听与 refresh 机制。
- 架构：Distro/Raft、集群部署、MySQL 外置、Prometheus 指标。
- 客户端：Spring Cloud Alibaba 集成、本地快照、故障转移、超时重试。
- 安全：鉴权、TLS、命名空间权限。

## 面试官评分点
**高级开发级**：能讲注册/配置模型与客户端缓存。
**架构师加分**：AP/CP 取舍与分区降级语义；SCA 2023 配置机制变更的完整排查链（404→死配置→code 300→200）；
gRPC 长连接与 1.x 差异；`optional:` 的启动韧性取舍。
**危险信号**：以为 shared-configs 永远生效；Nacos 单点当生产方案；把启动重试噪音当故障。

## 本项目真实证据
- 运行态：Nacos 2.3.2 standalone；16 服务注册；cart 实例 metadata `zone=defaultZone`（基线条）；
- `docs/reports/nacos-config-externalization-20260918.md`（完整修复报告与验证数据）；
- `docs/reports/nacos-grpc-noise-20260917.md`（19848 噪音结论）；
- `docs/review-1/02-findings/high/F-026-nacos-runtime-snapshot-shows-auth-not-enabled.md`（鉴权缺失）；
- 本地快照目录与 `scripts/nacos-import-configs.sh`。

## 版本与来源
Nacos 官方文档（Distro/Raft/2.x 架构）；本项目修复报告与运行态。

## 真实性说明
版本/端口/注册数/命名空间/日志验证均为运行态事实；单节点、无鉴权、未做热更演练均主动披露。
