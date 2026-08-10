# 12 微服务治理

> 复审维度 12 | 每个模块必查 | 9 透镜全覆盖，服务架构的健康度为本维度核心
>
> 你是谁：分布式系统审查员。不凭记忆，逐条执行以下指令。每个检查项标注了透镜类型，覆盖
> 业务逻辑/工程正确性/分布式正确性/生产级/可扩展性/并发/微服务/性能/盲区 全部 9 个维度。
> 注：循环依赖的详细规则见 08.5；Feign 接口契约见 08.3。

---


**执行本维度后，必须在审查报告中输出 `[12] 12 微服务治理：发现 N 项`。缺失此标题 = 本维度未被执行。执行每条检查项时，标注来源维度编号（如 [12]）。**
## 检查项

### 12.1 服务边界清晰度 | 透镜：微服务/业务/盲区

**必须检查**：模块的职责是否清晰——是否出现了"一个模块偷偷做了另一模块的事"（如 inventory 直接操作 order 的 DB）。

**怎么查**：
```bash
grep -rn '@Transactional\|@Service\|Repository\|Mapper' my-xhs-<module>/src/main/java/com/myxhs/*/service/ | grep -oP '(?<=com/myxhs/)[^/]+' | sort | uniq -c
```
如果出现多个不同模块名的 Mapper 引用→跨模块 DB 访问。

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 跨模块直接查 DB | inventory Service 用了 `OrderMapper`→绕过 order 的 Service 层→耦合 |
| 跨模块写 DB | 直接 `INSERT/UPDATE`→无法控制并发/无审计 |
| 实体类跨模块引用 | `import com.myxhs.order.entity.Order`→编译期依赖→模块边界模糊 |
| 假"服务拆分" | 两个服务共享同一数据库→拆分只在代码层不在数据层 |

**案例**：`coupon` 模块的 `calculateCouponDiscount` 在 order 服务内被直接用→两个模块的折扣计算逻辑无单一权威方（修复 order 侧改为 Feign 调用 coupon）。

---

### 12.2 服务依赖图与启动顺序 | 透镜：微服务/分布式

**必须检查**：模块是否有明确的依赖图（A 依赖 B 的 Feign / B 依赖 C 的 MQ）——启动顺序是否考虑到依赖；启动时依赖方不可用是否有降级。

**怎么查**：
```bash
grep -rn '@FeignClient' my-xhs-<module>/src/main/java/ -A1 | grep 'name\|value'
grep -rn 'RocketMQMessageListener' my-xhs-<module>/src/main/java/ -A1 | grep 'topic\|consumerGroup'
```

**判定**：
- A 依赖 B 但 A 先启动→启动时 B 不可用→Feign 调用 500→需 `spring.cloud.loadbalancer.retry.enabled=true`
- 所有服务同时启动无顺序→可能任意依赖失败→用 `spring.main.allow-bean-definition-overriding` 蒙混过去
- 被依赖的服务下线→无 grace period→调用方需重试 + 降级

**案例**：（全特性面预置检查项——my-xhs 的服务依赖图需绘制 order→inventory/product/coupon、cart→product 的完整调用链。）

---

### 12.3 负载均衡策略 | 透镜：微服务/性能

**必须检查**：Feign/Ribbon/LoadBalancer 的负载均衡策略是否合理——是轮询（RoundRobin）还是加权随机（WeightedRandom）；是否绑定了最少连接/一致性哈希等高级策略。

**怎么查**：
```bash
grep -rn 'NFLoadBalancerRuleClassName\|@LoadBalancerClient\|loadbalancer\|RoundRobin\|RandomRule\|Weighted' my-xhs-<module>/src/main/
```

**判定**：
- 只用默认 RoundRobin→有状态服务（如 WebSocket）需 sticky session→连接断开重连到另一实例
- 无重试配置→负载均衡选到死节点→不自动 retry→请求失败
- 无跨区域路由→跨机房调用延迟大

**案例**：（全特性面预置检查项——my-xhs 当前用 Nacos Ribbon 默认轮询，WebSocket IM 服务需 sticky session 评估。）

---

### 12.4 灰度与蓝绿发布支持 | 透镜：微服务/工程/可扩展性

**必须检查**：模块是否支持灰度发布——能否通过 Header/Parameter 路由请求到特定版本的服务实例。

**怎么查**：
```bash
grep -rn 'version\|canary\|gray\|traffic.*tag\|x-version\|@RequestHeader.*version\|ZoneAvoidance\|metadata-map' my-xhs-<module>/src/main/
```

**判定**：
- 无版本标签→全量发布→无法灰度验证→上线即全量风险
- Nacos metadata 未配置 `version=v2`→无法按版本分流
- Feign 调用不传播版本标签→灰度边界不完整（Gateway 灰度但下游仍是全量）

**案例**：（全特性面预置检查项——my-xhs 当前无灰度发布基础设施，13 可扩展性维度记录即可。）

---

### 12.5 熔断与降级 | 透镜：生产级/微服务

**必须检查**：Feign 调用是否有 Sentinel/Resilience4j 熔断；FallbackFactory 是否覆盖了异常和降级两路径。

**怎么查**：
```bash
grep -rn 'Sentinel\|@SentinelResource\|Resilience4j\|@CircuitBreaker\|fallback\|blockHandler' my-xhs-<module>/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 无熔断 | Feign 调 B→B 慢→A 线程全部阻塞→A 不可用→级联雪崩 |
| Fallback 不区分异常 vs 降级 | 业务异常（参数错误）和系统降级（超时）给同一返回→调用方分不清 |
| BlockException 无 null 保护 | `BlockException.getRule()` 可能 null（SystemBlockException）→自定义 handler 500 |

**案例**：my-xhs 当前用 Sentinel 但多个模块只有 `@SentinelResource` 注解无实际规则（修复需逐模块配 Web 端点的 fallback 路径 + Feign 调用的降级策略）。

---

### 12.6 服务版本与 API 兼容 | 透镜：工程/可扩展性

**必须检查**：模块是否有 API 版本管理——Feign DTO 字段增删后是否兼容（不存在新旧版本混部时不兼容问题）；JSON 序列化是否有字段忽略策略。

**怎么查**：
```bash
grep -rn '@JsonIgnoreProperties\|@JsonProperty\|@JsonInclude\|version\|apiVersion\|/v[12]/' my-xhs-<module>/src/main/java/
```

**判定**：
- 删 Feign DTO 字段→生产端升级、调用端未升级→接收到未知字段→反序列化抛异常
- 改 Feign DTO 字段名→生产端用新名、调用端用旧名→字段永远为 null
- Jackson 没设 `FAIL_ON_UNKNOWN_PROPERTIES=false`→新增字段后调用端崩溃

**案例**：（全特性面预置检查项——my-xhs 灰度部署/DTO 字段变更的兼容性需逐模块评估。）

---

### 12.7 微服务框架耦合 | 透镜：可扩展性

**必须检查**：模块对 Spring Cloud Alibaba 的依赖深度——是否只用 `@FeignClient`/`@SentinelResource`/`NacosDiscovery` 等标准抽象，还是深度绑定 SCA 特有实现。切标准 Spring Cloud/Spring Cloud Kubernetes 的成本。

**怎么查**：
```bash
grep -rn 'com.alibaba.cloud\|com.alibaba.nacos\|com.alibaba.csp' my-xhs-<module>/src/main/java/ | wc -l
grep -rn 'org.springframework.cloud' my-xhs-<module>/src/main/java/ | wc -l
```

**判定**：前者远大于后者→深度绑定 SCA→切标准 Spring Cloud 大面积改动。**记录即可，不强制修改。**

**案例**：my-xhs 全量使用 SCA（Nacos+Sentinel+RocketMQ），无标准 Spring Cloud 抽象层。

---

## 跨维度引用

| 相关概念 | 主维度 | 说明 |
|---------|:--:|------|
| 服务循环依赖 | 08.5 | A→B→A 循环依赖识别 |
| Feign 接口契约 | 08.3 | Feign 方法签名/Schema 一致性 |
| 鉴权边界 | 07.1 | Gateway JWT + 内部 X-Internal-Call |

---

## 验证命令汇总

```bash
mvn compile test-compile -pl <module> -am
mvn test -pl <module>

# 跨模块 DB 访问
grep -rn 'Mapper\|@Repository' my-xhs-<module>/src/main/java/ | grep -v 'my-xhs-<module>'

# Feign 依赖列表
grep -rn '@FeignClient' my-xhs-<module>/src/main/java/ -A1

# 熔断/降级
grep -rn 'Sentinel\|@SentinelResource\|Resilience4j\|fallback\|blockHandler' my-xhs-<module>/src/main/java/

# API 兼容性配置
grep -rn 'JsonIgnoreProperties\|FAIL_ON_UNKNOWN\|JsonInclude\|@JsonUnwrapped' my-xhs-<module>/src/main/java/

# SCA 绑定深度
grep -rn 'com.alibaba.cloud\|com.alibaba.nacos\|com.alibaba.csp' my-xhs-<module>/src/main/java/ | wc -l
```
