# Common Zone 多活架构 — 深度技术分析

> 关联源码：`ZonePreferenceFilter.java` / `ZoneContext.java` / `ZonePreferenceServiceInstanceListSupplier.java` / `DynamicDataSource.java` / `ZoneConstants.java`

---

## 业务背景

多机房（Zone）部署的动机：
- **容灾**：单机房故障时其他机房接管
- **就近访问**：用户访问同机房实例，降低延迟
- **流量隔离**：灰度发布按机房隔离

但多机房引入新问题：**跨机房调用**。如果杭州机房的请求路由到北京机房的服务实例，延迟从 1ms（同机房）飙到约 30-40ms（杭州-北京 RTT，估计值）。

Zone 多活的目标：**请求优先路由到同 Zone 实例，Zone 不可用时降级到其他 Zone**。

---

## 整体架构

```
用户请求（杭州）
    ↓
Gateway（杭州 Zone）
    ↓ LoadBalancer（ZonePreferenceServiceInstanceListSupplier）
    ↓
Home BFF（杭州 Zone）←→ 优先同 Zone
    ↓ Feign（Zone 优先）
    ↓
Content 服务（杭州 Zone ✅ 命中）
Content 服务（北京 Zone —— 仅杭州不可用时兜底）
```

**两个层面生效**：
1. 服务发现层：`ZonePreferenceServiceInstanceListSupplier` 过滤实例列表
2. 数据源层：`DynamicDataSource` 按 Zone 切换 MySQL 连接

---

## ZonePreferenceFilter 10 步决策

```java
1. 列表为空或仅 1 个 → 直接返回
2. Zone 功能未启用 → 返回全部
3. Zone 优先未启用 → 返回全部
4. 当前 Zone 是空/默认值 → 忽略优先
5. 过滤禁用 Zone 的实体 → 剩余不足时回退全部
6. 按 Zone 分组，统计同 Zone 实体
7. 上游 Zone 就绪率 < 阈值（如 50%）→ 返回全部
8. 同 Zone 实体数 < 最小可用阈值 → 返回全部
9. 同 Zone 实体数 > 0 → 返回同 Zone 实体
10. 无同 Zone 实体 → 返回全部（fallback）
```

### 为什么需要步骤 7（就绪率检查）

```
场景：杭州 Zone 实例大面积故障，只剩 1 个存活
步骤 9 会返回这 1 个杭州实例 → 流量全压给它 → 它也挂

就绪率检查：带 Zone 标记的实例数 / 总实例数 < 50% → 判定 Zone 信息不完整
→ 放弃 Zone 优先，流量分摊到所有 Zone（北京实例接管）
```

### 为什么需要步骤 8（最小可用阈值）

```
场景：杭州 Zone 只剩 1 个实例
虽然就绪率达标（如 60%），但 1 个实例扛不住全部杭州流量

最小可用阈值：同 Zone 实例数 < 2 → 放弃优先
```

---

## ZoneContext 配置

```yaml
zone:
  enabled: true                    # 总开关
  current-zone: hz                 # 当前 Zone
  preference:
    enabled: true                  # 优先路由开关
    upstream-zone-ready-percentage: 50    # 就绪率阈值
    upstream-same-zone-min-available: 2   # 最小可用实例数
    upstream-disabled-zone: ""      # 禁用的 Zone
```

配置支持 Nacos 动态推送（`ZoneContext` 监听属性变化）。

---

## 数据源层：DynamicDataSource

```
DynamicDataSource（按 Zone 切换）
├── 杭州 Zone → 杭州 MySQL（读写）
└── 北京 Zone → 北京 MySQL（读写）

Zone 切换时：
  1. 创建新 Zone 的 DataSource（热替换）
  2. 等待活跃事务完成（最多 30 秒）
  3. 关闭旧 DataSource 连接
```

**注意**：数据库多活比服务多活复杂得多（数据一致性、主备切换）。当前实现是"Zone 切换数据源"，实际生产还需配合数据库主从/双活方案。

---

## 服务发现层：ZonePreferenceServiceInstanceListSupplier

```java
// 包装默认的 ServiceInstanceListSupplier
// 在实例列表返回时执行 ZonePreferenceFilter
public Flux<List<ServiceInstance>> get() {
    return delegate.get()
        .map(instances -> zonePreferenceFilter.filter(instances));
}
```

实例的 Zone 从 Nacos metadata 读取：

```yaml
spring.cloud.nacos.discovery.metadata:
  zone: ${MYXHS_ZONE:defaultZone}
```

---

## 面试 Q&A

**Q: Zone 优先和负载均衡的关系？**
A: Zone 优先是负载均衡的前置过滤。先按 Zone 过滤出候选实例（同 Zone），再在候选内做负载均衡（轮询/最少连接）。

**Q: 同 Zone 实例全部挂了怎么办？**
A: 就绪率检查（步骤 7）会判定 Zone 不健康 → 返回全部实例 → 请求路由到其他 Zone。这是设计内的降级路径。

**Q: 配置动态修改（如禁用 Zone）需要重启吗？**
A: 不需要。`ZoneContext` 监听配置变化（Nacos 推送），`ZonePreferenceFilter` 每次调用都读取最新配置。

---

## 发散

### 数据一致性

服务多活容易，数据多活难。Zone 切换时：
- MySQL：需要主从复制 + 故障切换（如 MHA/MGR）
- Redis：各 Zone 独立部署，跨 Zone 缓存不一致
- RocketMQ：跨 Zone 消息复制

生产建议：先做服务多活（读多写少），数据层保持单中心，再逐步演进。

### 流量染色联动

Zone 可以与会话保持联动：用户首次接入的 Zone 记为 sticky zone，后续请求优先路由到该 Zone，减少跨 Zone 数据不一致。
