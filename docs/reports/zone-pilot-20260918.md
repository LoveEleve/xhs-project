# D1 双 Zone 试点报告（2026-09-18）

## 一、目标与拓扑
- 目标：验证 zone 优先路由（同 zone 优先、zone 故障自动切走）与切换 RTO。
- 拓扑（单机仿真）：product 双实例 zone-a(19006)/zone-b(19026)；cart(19008) 为消费者（zone-a）；Nacos(18848) 元数据 `zone` 打标。
- 开关：`myxhs.availability.zone.preference.enabled=true`、`myxhs.availability.zone.preference.upstream.same-zone-min-available=1`、`myxhs.current.availability.zone=zone-a`。

## 二、结果 A：同 zone 优先路由 ✅
- 开启前后基线：zone-a count=4；发起 240 次 cart list（8 用户轮转，触发 SKU 查询）。
- 结果：**240/240 全部命中 zone-a（4→244）**，zone-b（新进程）计数 0；cart 进程与 19006 建连、与 19026 无业务连接。
- 关键配置坑：默认 `same-zone-min-available=5`，单 zone 仅 1 实例时优先逻辑直接回退"全部实例"（首轮测试因此无效）。小副本试点必须显式调小。

## 三、结果 B：zone 故障切换 ✅
- 操作：kill 掉 zone-a product（pid 1425270）。
- 结果：cart 首次探测（0.5s 后）即成功且数据完整（price=199.0），**实测 RTO ≈ 0.89s**；流量落到 zone-b（19026）并计数。
- 说明：Nacos 2.x gRPC 连接断开可近实时感知实例下线并推送；本次未出现缓存窗口内失败重试。

## 四、发现与待办
1. **首轮异常（重要）**：在正确开 zone 优先且双实例均在册的情况下，首轮 240 次全部打到 zone-b（cart 本地实例列表快照疑似只含 zone-b，属 Nacos 订阅/缓存窗口，非过滤逻辑问题）；kill 19026 触发列表刷新后立即恢复 zone-a，重启订阅周期后路由恢复正常。**待办：补 zone 路由命中指标/日志（当前无观测，只能靠对端计数反推）。**
2. 操作坑：`pkill -f "server.port=19026"` 会匹配到自身 shell 命令行导致自杀，需用 pid 精确 kill。
3. 边界（不得夸大）：单机仿真、无双 zone 网络延迟/分区真实故障；RTO 为单次采样；数据面（动态数据源/Redis zone 事件）未接入；未验证跨 zone 数据一致性。

## 五、生产化建议
- 配置：按真实副本数设置 `same-zone-min-available`（如 2 zone×2 副本 → 1~2）；保留 fallback 语义（同 zone 不足自动回全局）。
- 可观测：路由命中/回退/跨 zone 指标（下一步 D1 收尾）。
- 演练：重复 kill 演练取 RTO 分布；补网络隔离（iptables DROP）场景，验证与进程宕机的差异。
