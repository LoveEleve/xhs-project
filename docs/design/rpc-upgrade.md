# RPC 架构升级选型与试点（B1/B2，2026-09-17）

## 一、现状与问题（Feign）
- 全站 15 服务同步调用：Spring Cloud OpenFeign（HTTP/1.1 + HC5 连接池），全局 `NEVER_RETRY`；
- 观测到的限制：① 文本协议开销（JSON 序列化/反序列化，A2 的 JFR 热点之一）；② 每次调用要过负载均衡器 + 编解码栈；③ 无原生异步/流式；④ 接口契约靠手写 Feign 接口 + HTTP 注解，无强类型共享。
- 压测基线（A2 修复后）：product 详情 4.9k RPS（无缓存）、cart→product 为读路径。

## 二、候选对比
| 维度 | Feign（现状） | **Apache Dubbo 3.3** | gRPC |
|---|---|---|---|
| 协议 | HTTP/1.1 + JSON | Dubbo/Triple（HTTP/2 可选） | HTTP/2 + Protobuf |
| 序列化 | JSON | Hessian2 / Protobuf | Protobuf |
| 服务发现 | Nacos（SC LoadBalancer） | Nacos（原生注册） | 自建/etcd/xDS |
| 负载均衡 | SC LB（客户端） | Dubbo 内置（一致性哈希/随机/最少活跃） | 客户端 |
| 超时/重试/熔断 | 手写 + Sentinel | 原生（timeout/retries/cluster） | 需自建 |
| 契约 | 手写接口 | 共享 API 接口（强类型） | .proto |
| 异步/流式 | 有限 | 完整（CompletableFuture/Triple stream） | 完整 |
| 生态（Java） | SCA 全家桶 | Dubbo 生态成熟 | 偏多语言 |
| 迁移成本 | — | 中（共享接口 + 双协议灰度可行） | 高（IDL + 代码生成） |
| 结论 | 保留（非热路径） | **试点（读路径先行）** | 不选（多语言非需求） |

## 三、试点方案（B2，评估产物，未落地）
- **范围**：cart → product 的 SKU 查询（`getSkuDetail`/`batchGetSkuDetails`，读路径，安全）；
- **双协议**：保留 Feign 客户端；新增 Dubbo 接口 + 提供者/消费者，由 `myxhs.rpc.dubbo-enabled` 开关切换（默认 false，可回滚）；
- **注册中心**：Nacos（`nacos://192.168.0.142:18848?namespace=my-xhs`），与现有治理一致；
- **验证**：① 编译/健康（默认关闭不影响存量）；② 开关 A/B 压测同一 cart 接口（带商品的 cart list 触发 SKU 查询）；③ 记录 RPS/P99/CPU 对比（B3）。

## 四、验收与回滚
- 验收：开关关闭 = 行为与现状一致；开关打开 = SKU 查询走 Dubbo 且结果一致；压测给出 Feign vs Dubbo 数字；
- 回滚：开关置 false + 重启（或 release 脚本回退版本）；Dubbo 端口/注册项可随进程退出自然清理。

## 五、风险
- Boot 3.2 + Dubbo 3.3 兼容性（Jakarta）；Nacos Registry 参数（namespace/auth）；
- 双协议并存期的契约漂移（接口定义放 common，版本随 BOM 管理）；
- 试点不覆盖写路径（order→inventory）——如需二期再评估。

---

## 六、B3 试点实测结果（2026-09-17，评估产物，代码已回滚）
**场景**：cart `GET /api/cart/list`（购物车含 1 个 SKU，触发 product 批量 SKU 查询）；2000 个用户轮换（规避业务限流 60/min/用户）；c=32，20s；同机同数据。

| 指标 | Feign（HTTP+JSON） | Dubbo（Hessian2） | 差异 |
|---|---:|---:|---|
| RPS | 1,236 | **1,296** | **+4.9%** |
| P50 | 20.2ms | **16.4ms** | **-19%** |
| P90 | 77.5ms | 80.4ms | 持平 |
| P99 | 85.0ms | 87.8ms | 持平 |

**结论（诚实口径）**：
1. 双协议机制验证通过（开关切换、结果一致、异常自动回退 Feign、默认关闭可回滚）；
2. **该路径收益有限**——瓶颈在 Redis/DB（cart 读链路），RPC 协议开销占比小；Dubbo 的价值更多在**高频纯 RPC**（如 order→inventory 预扣）、异步/流式与强契约场景；
3. 试点发现的**关键坑**：Dubbo `application.name` 若与 `spring.application.name` 同名，Dubbo provider 实例会混入 Spring Cloud 服务列表（Feign 有概率打到 Dubbo 二进制端口 → `Invalid status(-1)`）；已修复为独立应用名 `my-xhs-product-rpc` + `dubbo.protocol.host` 绑主机网卡；
4. 生产化建议：先在高频小报文的内部调用做二期试点，并统一治理（接口版本、超时/重试、序列化兼容、双协议灰度与回滚 SOP）。
