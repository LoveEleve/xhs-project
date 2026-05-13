# 注册中心与发现多活

> 跨服务专题 | 开发阶段：Phase-7 | 状态：⏳ 待开发

## 专题概要

本专题基于 Nacos 设计双机房注册中心方案：Nacos 双集群独立部署、服务双注册（带 `region` 元数据标记）、同区域优先发现、Nacos Raft/Distro 协议在跨机房场景的运用。

## 涉及服务

- Nacos Cluster A（机房A注册中心，3节点 Raft）
- Nacos Cluster B（机房B注册中心，3节点 Raft）
- 所有微服务（双注册方：同时注册到两个 Nacos 集群）
- my-xhs-gateway（路由决策方：根据区域标记路由）
- my-xhs-common（组件提供方：NacosMultiRegistry、RegionAwareDiscovery 等）

## 核心内容

| 维度 | 内容 |
|------|------|
| 部署架构 | 双 Nacos 集群独立部署（各 3 节点），不跨机房组集群 |
| 服务双注册 | 每个实例同时注册到两个 Nacos 集群，metadata 标记 region |
| 同区域优先 | 消费者优先获取同区域提供者实例，降级时获取对端实例 |
| 配置同步 | GitOps + Nacos Import 为主，应用双订阅为辅 |
| Nacos 协议 | Raft（集群内配置强一致 CP）、Distro（集群内服务发现 AP） |

## 重点覆盖

- 为什么不部署跨机房 Nacos 集群？（Raft Leader 选举跨机房延迟 + 脑裂风险）
- 服务双注册实现方案对比（配置双注册 vs 自定义 NacosMultiRegistry）
- 同区域优先发现流程与降级策略
- Nacos metadata.region vs cluster-name 选型
- 双 Nacos 健康检查与配置同步

## 面试高频问题

- Nacos 为什么要部署双集群而不是跨机房集群？
- 服务双注册会不会导致注册数据翻倍？性能影响如何？
- Nacos 的 Raft 和 Distro 协议分别用在什么场景？
- 配置中心多活怎么保证一致性？

---

## 补充一：NacosMultiRegistry 双注册实现

> 服务双注册是多活架构的基础。每个实例同时注册到两个 Nacos 集群，metadata 中标记 region，消费者根据 region 优先选择同区域实例。

### 双注册核心代码

```java
/**
 * Nacos 双集群注册器
 * 每个服务实例同时注册到两个 Nacos 集群
 * metadata 中标记 region（region-a / region-b）
 */
@Component
@Slf4j
public class NacosMultiRegistry implements ApplicationListener<WebServerInitializedEvent> {

    @Value("${nacos.cluster-a.server-addr}")
    private String clusterAAddr;  // 机房A的Nacos地址

    @Value("${nacos.cluster-b.server-addr}")
    private String clusterBAddr;  // 机房B的Nacos地址

    @Value("${spring.application.name}")
    private String serviceName;

    @Value("${region.current:region-a}")
    private String currentRegion;

    private NamingService namingServiceA;
    private NamingService namingServiceB;
    private int port; // 注册端口（从 WebServerInitializedEvent 获取，反注册时使用）

    @PostConstruct
    public void init() throws NacosException {
        // 创建两个 NamingService 实例，分别连接两个 Nacos 集群
        Properties propsA = new Properties();
        propsA.setProperty("serverAddr", clusterAAddr);
        namingServiceA = NamingFactory.createNamingService(propsA);

        Properties propsB = new Properties();
        propsB.setProperty("serverAddr", clusterBAddr);
        namingServiceB = NamingFactory.createNamingService(propsB);
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        this.port = event.getWebServer().getPort();
        String ip = getLocalIp();

        // 构建实例信息（带 region 元数据）
        Instance instance = new Instance();
        instance.setIp(ip);
        instance.setPort(port);
        instance.setWeight(1.0);
        instance.setHealthy(true);
        instance.setEnabled(true);
        instance.setMetadata(Map.of(
                "region", currentRegion,           // 区域标记
                "version", buildVersion(),          // 版本号（灰度用）
                "startTime", String.valueOf(System.currentTimeMillis())
        ));

        try {
            // 同时注册到两个 Nacos 集群
            namingServiceA.registerInstance(serviceName, instance);
            log.info("注册到 Nacos Cluster A 成功: {}:{} region={}", ip, port, currentRegion);

            namingServiceB.registerInstance(serviceName, instance);
            log.info("注册到 Nacos Cluster B 成功: {}:{} region={}", ip, port, currentRegion);
        } catch (NacosException e) {
            log.error("Nacos 双注册失败", e);
            // 单集群注册失败不影响服务启动，降级为单集群
        }
    }

    /**
     * 优雅停机时反注册
     */
    @PreDestroy
    public void deregister() {
        try {
            String ip = getLocalIp();
            namingServiceA.deregisterInstance(serviceName, ip, port);
            namingServiceB.deregisterInstance(serviceName, ip, port);
            log.info("Nacos 双集群反注册完成");
        } catch (NacosException e) {
            log.error("Nacos 反注册失败", e);
        }
    }
}
```

### 配置示例

```yaml
# bootstrap.yml — 双 Nacos 集群配置
nacos:
  cluster-a:
    server-addr: nacos-a1:8848,nacos-a2:8848,nacos-a3:8848
  cluster-b:
    server-addr: nacos-b1:8848,nacos-b2:8848,nacos-b3:8848

region:
  current: ${REGION:region-a}  # 从环境变量读取当前机房

spring:
  cloud:
    nacos:
      discovery:
        server-addr: ${nacos.cluster-a.server-addr}  # 主注册中心
        metadata:
          region: ${region.current}
```

---

## 补充二：同区域优先发现（RegionAwareDiscovery）

> 消费者获取服务实例列表时，优先返回同区域的实例。当同区域实例全部不可用时，降级返回对端区域实例。

### 同区域优先发现代码

```java
/**
 * 区域感知服务发现
 * 包装 Nacos NamingService，过滤返回同区域实例
 */
@Component
public class RegionAwareDiscovery {

    @Value("${region.current:region-a}")
    private String currentRegion;

    private final NamingService namingService;

    /**
     * 获取服务实例列表（同区域优先）
     *
     * @param serviceName 服务名
     * @param preferLocal 是否优先本地（true=同区域优先，false=返回所有）
     * @return 实例列表
     */
    public List<Instance> getInstances(String serviceName, boolean preferLocal) 
            throws NacosException {
        List<Instance> allInstances = namingService.selectInstances(serviceName, true);

        if (!preferLocal) {
            return allInstances;
        }

        // 按区域分组
        Map<String, List<Instance>> regionMap = allInstances.stream()
                .collect(Collectors.groupingBy(
                        i -> i.getMetadata().getOrDefault("region", "unknown")));

        List<Instance> localInstances = regionMap.getOrDefault(currentRegion, List.of());
        List<Instance> remoteInstances = regionMap.entrySet().stream()
                .filter(e -> !e.getKey().equals(currentRegion))
                .flatMap(e -> e.getValue().stream())
                .collect(Collectors.toList());

        if (!localInstances.isEmpty()) {
            log.debug("服务 {} 同区域实例数: {}, 使用同区域实例",
                    serviceName, localInstances.size());
            return localInstances;
        }

        // 同区域无可用实例，降级到对端
        log.warn("服务 {} 同区域 {} 无可用实例，降级到对端区域，实例数: {}",
                serviceName, currentRegion, remoteInstances.size());
        return remoteInstances;
    }

    /**
     * 订阅服务变更事件（实例上下线时触发）
     */
    public void subscribe(String serviceName, EventListener listener) throws NacosException {
        namingService.subscribe(serviceName, listener);
    }
}
```

### 与 Spring Cloud LoadBalancer 集成

```java
/**
 * 区域感知 ServiceInstanceListSupplier
 * 替换 Spring Cloud LoadBalancer 默认的实例列表提供者
 * 实现同区域优先选择
 */
@Component
public class RegionServiceInstanceListSupplier implements ServiceInstanceListSupplier {

    @Value("${region.current:region-a}")
    private String currentRegion;

    private final ServiceInstanceListSupplier delegate;

    public RegionServiceInstanceListSupplier(
            @Qualifier("discoveryClientServiceInstanceListSupplier")
            ServiceInstanceListSupplier delegate) {
        this.delegate = delegate;
    }

    @Override
    public Flux<List<ServiceInstance>> get() {
        return delegate.get().map(instances -> {
            // 按区域过滤
            List<ServiceInstance> localInstances = instances.stream()
                    .filter(i -> currentRegion.equals(
                            i.getMetadata().getOrDefault("region", "")))
                    .collect(Collectors.toList());

            if (!localInstances.isEmpty()) {
                return localInstances; // 同区域有实例，只返回同区域
            }
            return instances; // 同区域无实例，返回所有（降级）
        });
    }

    @Override
    public String getServiceId() {
        return delegate.getServiceId();
    }
}
```

---

## 补充三：Nacos Raft vs Distro 协议对比

> Nacos 内部使用两种协议处理不同类型的数据，面试高频考点。

| 维度 | Raft 协议 | Distro 协议 |
|------|----------|------------|
| 一致性模型 | CP（强一致） | AP（最终一致） |
| 使用场景 | 配置中心（持久化数据） | 服务发现（临时实例） |
| Leader 选举 | ✅ 需要 Leader | ❌ 无 Leader，对等节点 |
| 数据同步 | Leader → Follower 复制 | 各节点负责部分数据，互相同步 |
| 写入方式 | 只能写 Leader | 任意节点可写（负责的分片） |
| 跨机房影响 | Leader 在远端时写延迟高 | 无 Leader，延迟均匀 |
| 数据持久化 | ✅ 持久化到磁盘 | ❌ 仅内存（临时实例） |

### 为什么不部署跨机房 Nacos 集群？

```
跨机房 Nacos 集群的问题：

1. Raft Leader 选举：
   - 3 节点集群（A机房2个 + B机房1个）
   - Leader 大概率在 A 机房（多数派）
   - B 机房的配置写入必须经过 A 机房的 Leader → 跨机房延迟 3-10ms
   - 如果 A 机房网络隔离 → B 机房无法选出 Leader → 配置中心不可用

2. 脑裂风险：
   - 网络分区时，两个机房各自选出 Leader
   - 配置数据分裂，恢复后需要人工合并

3. 正确方案：双集群独立部署
   - 每个机房独立 3 节点 Nacos 集群
   - Raft Leader 选举在机房内完成，无跨机房延迟
   - 配置同步通过 GitOps + Import 脚本，不依赖 Raft
   - 服务双注册到两个集群，消费者同区域优先发现
```

---

## 补充四：Nacos 配置不一致的检测与修复

> GitOps + Nacos Import 方案虽然能同步配置，但存在配置漂移风险（人为在 Nacos 控制台修改、Import 脚本失败、Git 合并冲突等）。需要一套检测与修复机制。

### 配置漂移风险场景

| 场景 | 原因 | 影响 |
|------|------|------|
| 运维人员直接在 Nacos B 控制台修改配置 | 绕过 GitOps 流程 | 两集群配置不一致，A≠B |
| GitOps Import 脚本执行失败但未告警 | 网络抖动 / Nacos B 短暂不可用 | Nacos B 配置为旧版本 |
| Git 合并冲突手动解决时遗漏 | 多人协作 | Git 仓库本身不一致 |
| Nacos Raft Leader 切换时配置回滚 | 快照未及时持久化 | 配置回退到旧版本 |

### 检测方案：定时 MD5 对比

```java
/**
 * Nacos 双集群配置一致性检测器
 * 每分钟对比两集群所有配置的 MD5，不一致时告警 + 自动修复
 */
@Slf4j
@Component
public class NacosConfigConsistencyChecker {

    @Scheduled(fixedRate = 60000) // 每分钟执行一次
    public void checkConfigConsistency() {
        // 1. 获取 Nacos A 的所有配置列表
        List<ConfigItem> configsA = nacosClientA.getConfigList(group);
        // 2. 获取 Nacos B 的所有配置列表
        List<ConfigItem> configsB = nacosClientB.getConfigList(group);

        // 3. 按 dataId + group 组对，对比 MD5
        Map<String, String> md5MapA = configsA.stream()
            .collect(Collectors.toMap(
                c -> c.getDataId() + ":" + c.getGroup(), c -> c.getMd5()));
        Map<String, String> md5MapB = configsB.stream()
            .collect(Collectors.toMap(
                c -> c.getDataId() + ":" + c.getGroup(), c -> c.getMd5()));

        // 4. 找出不一致的配置
        Set<String> allKeys = new HashSet<>(md5MapA.keySet());
        allKeys.addAll(md5MapB.keySet());

        List<String> inconsistentKeys = new ArrayList<>();
        for (String key : allKeys) {
            String md5A = md5MapA.get(key);
            String md5B = md5MapB.get(key);
            if (!Objects.equals(md5A, md5B)) {
                inconsistentKeys.add(key);
                log.warn("配置不一致: key={}, md5A={}, md5B={}", key, md5A, md5B);
            }
        }

        // 5. 不一致时：告警 + 自动从 Git 重新 Import 修复
        if (!inconsistentKeys.isEmpty()) {
            alertService.sendAlert("Nacos配置不一致", inconsistentKeys);
            // 自动修复：以 Git 仓库为 Source of Truth，重新 Import
            gitOpsImportService.reimportConfigs(inconsistentKeys);
        }
    }
}
```

### 修复策略

| 修复方式 | 触发条件 | 说明 |
|---------|---------|------|
| 自动从 Git 重新 Import | MD5 不一致 + 差异配置数 < 10 | 小范围不一致，自动修复 |
| 人工确认后 Import | MD5 不一致 + 差异配置数 ≥ 10 | 大范围不一致，先告警再人工确认 |
| 配置缺失补全 | 某集群缺少某个 dataId | 自动从 Git 仓库补全 |

### 防漂移措施

1. **Nacos B 控制台只读**：通过 Nacos 的 RBAC 权限，禁止运维人员直接在 Nacos B 控制台修改配置
2. **GitOps 为唯一配置入口**：所有配置变更必须走 Git PR → Review → Merge → Import 流程
3. **Import 失败重试 + 告警**：Import 脚本失败时自动重试3次，仍失败则 P0 告警
4. **配置变更审计日志**：记录每次配置变更的来源（Git commit ID / 手动修改）

### MD5 对比窗口期说明

> 定时 MD5 对比存在天然的窗口期：如果 Nacos A 的配置刚好在对比间隙被修改，Nacos B 还未同步，此时会误报不一致。这是可接受的，因为：

1. **误报会被自动修复覆盖**：下一次对比时（最多1分钟后），配置已同步，误报自动消除
2. **修复操作幂等**：自动从 Git 重新 Import 是幂等操作，重复执行不会产生副作用
3. **大范围不一致才需关注**：只有差异配置数 ≥ 10 时才需人工确认，小范围不一致是同步过程的正常现象

---

**相关专题**：
- ⬅️ [42-多活架构概述与选型](../42-multi-active-overview-and-selection/README.md)
- ➡️ [44-网关与负载均衡多活](../44-gateway-and-loadbalancer-multi-active/README.md)
- 🔗 [45-数据层多活](../45-data-layer-multi-active/README.md)

> 📋 详细文档请在开发时基于 [_template.md](../../_template.md) 填写完整内容