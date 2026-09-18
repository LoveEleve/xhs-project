# 第51题 | 组件深度拷打：SkyWalking（APM）

> 难度：★★★★☆｜频率：★★★★☆｜区分度：高
> 关键词：自动埋点、跨进程 span、插件机制、APM vs 业务 traceId、Agent 接入边界

## 问题
链路追踪怎么做的？SkyWalking 和你们的 traceId 什么关系？Agent 怎么接入？踩过什么坑？

## 面试可讲版（五段式）

**① 原理层**
- **架构**：Agent（字节码增强，无侵入）→ gRPC 上报 → OAP（接收/分析/存储）→ UI（查询）；存储可接 ES（本项目专用 ES，与业务 ES 隔离）；
- **数据模型**：Trace（一次请求的 span 树）+ **refs**（CROSS_PROCESS 跨进程 / CROSS_THREAD 跨线程引用）——"能不能把异步和跨服务串起来"是 APM 的价值分水岭；
- **插件机制**：按框架自动增强（SpringMVC/Feign/WebFlux/Gateway/JDBC/Redis…），插件版本必须与框架大版本匹配；可选插件从 `optional-plugins` 移入；
- **采样与开销**：默认采样、异步上报；Agent 有 CPU/内存开销（字节码增强 + 队列），生产要定采样率与日志目录。

**② 项目用法（栈与实证）**
- **栈**：OAP **9.7.0**（REST 8080 / **gRPC 11800**）+ UI 9.7.0 + **专用 ES 8.12.2（19201）**——观测存储与业务索引物理隔离，避免 APM 数据量冲击业务搜索；
- **Agent 9.6.0**（`-javaagent` 接入，`-Dskywalking.agent.service_name={模块}`、`-Dskywalking.logging.dir=/tmp/sw-logs/{模块}`）；
- **实证**（FINAL-HANDOFF）：跨服务 **22 span** 链路，refs 完整——`home SpringMVC → Feign Exit ×3 → user/counter/content 各自 Entry + DB/Redis`，即"网关/服务/跨线程"三段都能串上；
- **与业务 traceId 的分工（答辩关键）**：SkyWalking 是 **APM traceId**（span 树、性能归因）；业务 `X-Trace-Id` 是**日志/MQ/审计的关联键**（16 题）。两套 ID 各管一摊，**不要混用**。

**③ Agent 接入的坑（实测修复记录）**
1. **jdk-http 插件兜底依赖挂载目录**：`SW_MOUNT_FOLDERS=plugins,activations,bootstrap-plugins`，不配则部分链路断；
2. **插件与框架大版本冲突**：把 `apm-springmvc-annotation-3/4/5.x` **移出** plugins（与 6.x 共存冲突）、移入 `apm-springmvc-annotation-6.x` + `apm-spring-webflux-6.x`（含 webclient）；网关链路补 `apm-spring-cloud-gateway-4.x`（从 optional-plugins 移入）——**Spring Boot 3 + WebFlux 场景不换插件就没有网关 span**；
3. **日志目录隔离**：每个服务 `logging.dir` 分开，否则 agent 日志互相覆盖；
4. **性能与存储**：APM 数据量与采样强相关，存储独占 ES 才敢全链路开。

**④ 边界（主动披露，很值钱）**
- **接入演进（真实故事）**：曾核查发现——OAP/UI/ES 在运行，但 `release-service.sh` 参数不含 `-javaagent`、agent 包缺失、`restart-service.sh` 还指向云 collector，即『组件在跑但没数据』；**2026-09-18 已修复**：agent 9.7.0 落地（TUNA 源）、插件适配（springmvc 3/4/5 移出、6.x/webflux 6.x/gateway 4.x 移入、清理 `._*`）、release/restart 脚本自动挂载 → 15/15 服务注册；
- **运维参数**：采样默认 `sample_n_per_3_secs=1`（全采样 `SW_AGENT_SAMPLE=-1`，压测注意开销）；`SW_AGENT_DISABLED=1` 可关闭；升级 = 换 `SW_AGENT_DIR` 目录；
- **两套追踪的取舍**：全自动 APM（跨 MQ/自定义异步/长连接仍需手动增强）vs 业务 traceId（可控、无 agent 依赖、可进审计）；本项目**以业务 traceId 为主、APM 作为增强**。

**⑤ 拷打追问**
1. **"为什么还要业务 traceId，不直接用 SkyWalking？"** 业务 traceId 无 agent 依赖、能进消息/审计/DB 字段，业务排查与合规都用它；APM traceId 出不了 APM 存储。二者互补。
2. **"Agent 怎么做到无侵入？"** 字节码增强插件在类加载时插桩，不改业务代码；代价是版本敏感（插件与框架大版本强绑定，见坑 2）。
3. **"跨 MQ/线程池能串吗？"** 框架插件覆盖常见场景，自定义线程池需要手动增强或透传（我们业务侧用 traceId 兜住这部分）。
4. **"采样怎么定？"** 按流量与存储预算：头部采样/按比例采样；错误请求优先保留（部分配置支持）。
5. **"OAP 挂了会怎样？"** Agent 异步上报+本地缓存，业务不受影响，只是 trace 丢失；OAP 无 HA 时是观测盲区。
6. **"为什么 APM 存储用独立 ES？"** 隔离数据量与索引生命周期，避免 APM 写入影响业务搜索集群（业务 ES 19200 / APM ES 19201）。

**⑥ 话术**
> "SkyWalking 我们部署 OAP 9.7 加 UI 加独立 ES 19201，Agent 9.7 通过 javaagent 接入，release 脚本自动挂载。最能打的是真实链路实证：gateway 到 home 的 /api/home/feed 一条 trace 29 个 span，CROSS_PROCESS 和 CROSS_THREAD 都在，Redis 的 Lettuce 异步调用也串上了；15 个服务全部注册，连 MySQL/Redis/ES 依赖 peer 都有数据。接入时踩过插件版本坑：Spring Boot 3 要把 springmvc 3/4/5 插件移出、换 6.x，WebFlux 和网关插件从 optional 移进来，还要清掉 macOS 的 ._ 元数据文件，否则插件加载报错。这段经历也典型——组件在跑不等于有数据，我们做过一次从假装在用到真正接入的修复。它和业务 traceId 是互补关系，不是替代。"

## 发散追问地图（横向）
- APM 原理：字节码增强、span 模型、上下文传播（W3C traceparent）。
- 生态对比：SkyWalking vs Zipkin/Jaeger vs OTel + Collector vs 商业 APM。
- 存储：OAP 存储选型（ES/BanyanDB）、TTL、采样。
- 告警：OAP Alarm（webhook 到 Alertmanager）。
- 工程：Agent 版本升级、插件裁剪、开销评估。

## 面试官评分点
**高级开发级**：能讲 APM 架构/span/refs、与业务 traceId 的分工。
**架构师加分**：插件与框架大版本绑定的实证坑；专用 ES 隔离的容量考虑；"当前无 agent"的诚实边界与修复路径；两套追踪的取舍。
**危险信号**：APM 与业务 traceId 混为一谈；不知道插件版本冲突；宣称全自动无需人工增强。

## 本项目真实证据
- `docs/FINAL-HANDOFF.md:72-95`（OAP 9.7.0/8080/11800/ES 19201、Agent 9.6.0、22 span 实证、插件改动与 SW_MOUNT_FOLDERS、备份路径）；
- 运行态：OAP/UI/专用 ES 在跑；**2026-09-18 agent 接入完成**（报告 `skywalking-agent-enable-20260918.md`）：15 服务注册、gateway→home 29 spans（`CROSS_PROCESS`+`CROSS_THREAD`）、段量 cart 1815/home 327/search 278；`release-service.sh` 自动挂载（SW_AGENT_DIR/SW_COLLECTOR/SW_AGENT_DISABLED）。

## 版本与来源
SkyWalking 9.7 文档；本项目 FINAL-HANDOFF 排查记录与运行态。

## 真实性说明
22 span（旧环境）/插件改动/OAP 版本/存储端口为历史实证；2026-09-18 起本环境已接入并验证（15 服务注册、29 span 跨进程、段量数据），采样/关闭/升级参数一并披露。
