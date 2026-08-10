# 13 可扩展性

> 复审维度 13 | 每个模块必查 | 9 透镜全覆盖。**本维度全部发现记录即可，不强制立即修改**。
>
> 你是谁：分布式系统审查员。不凭记忆，逐条执行以下指令。每个检查项标注了透镜类型，覆盖
> 业务逻辑/工程正确性/分布式正确性/生产级/可扩展性/并发/微服务/性能/盲区 全部 9 个维度。

---


**执行本维度后，必须在审查报告中输出 `[13] 13 可扩展性：发现 N 项`。缺失此标题 = 本维度未被执行。执行每条检查项时，标注来源维度编号（如 [13]）。**
## 检查项

### 13.1 Feign→Dubbo/RPC 切换成本 | 透镜：可扩展性

**必须检查**：跨服务调用是直接写死 `@FeignClient` 还是通过接口抽象。切 Dubbo/gRPC 时改动面。

**怎么查**：
```bash
grep -rn '@FeignClient\|RestTemplate\|WebClient' my-xhs-<module>/src/main/java/ | wc -l
grep -rn 'ServiceAPI\|RemoteService\|RpcService\|interface' my-xhs-<module>/src/main/java/com/myxhs/*/rpc/ 2>/dev/null | wc -l
```

**判定**：
- 全部 `@FeignClient` 声明在 Controller 层无抽象接口→切 Dubbo 需改全部 Controller
- 有统一 `RemoteService` 接口 + `@FeignClient` 实现→切换只改实现层
- **记录即可，不强制修改。**

**案例**：my-xhs 全量 `@FeignClient` 声明在 `feign/` 包内，切 Dubbo 为全局改造。

---

### 13.2 存储抽象层切换成本 | 透镜：可扩展性

**必须检查**：数据操作是否通过抽象接口（Repository/DAO Interface），还是直接依赖 Spring Data/MyBatis-Plus 具体实现。

**怎么查**：
```bash
grep -rn 'extends BaseMapper\|extends IService\|Repository\|@Repository' my-xhs-<module>/src/main/java/com/myxhs/*/mapper/ my-xhs-<module>/src/main/java/com/myxhs/*/repository/ 2>/dev/null | wc -l
grep -rn 'interface.*Mapper\|interface.*Repository\|interface.*Dao' my-xhs-<module>/src/main/java/com/myxhs/*/mapper/ 2>/dev/null | wc -l
```

**判定**：
- 全部继承 MyBatis-Plus `BaseMapper<T>`→切 JPA/jOOQ 改全部 Mapper
- 有自定义 Repository 接口→切换只改实现层
- **记录即可，不强制修改。**

**案例**：my-xhs 全量继承 MyBatis-Plus `BaseMapper`/`IService`，无独立数据访问接口抽象层。

---

### 13.3 MQ / 缓存 / 注册中心切换成本 | 透镜：可扩展性

> 各领域的具体评估见专门维度：MQ 见 04.12、缓存见 05.8、注册中心见 11.5。本维度仅做聚合快速检查。

**必须检查**：列出模块依赖的全部中间件及其耦合深度。

**判定规则**（按模块实际 grep 结果填充）：

| 中间件 | 绑定程度 | 切换对象 | 改动面评估 |
|--------|:--:|------|------|
| RocketMQ | 全量直接 API | Pulsar / Kafka | 全局改造 |
| Redis | 全量直接 API | Caffeine / Hazelcast | 全局改造 |
| Nacos | SCA 深度绑定 | Consul / Eureka | 全局改造 |

**案例**：my-xhs 全量直接绑定 SCA 技术栈（RocketMQ+Redis+Nacos），技术栈迁移为全局重构。

---

### 13.4 业务逻辑抽象与扩展点 | 透镜：可扩展性/业务

**必须检查**：核心业务规则是否硬编码还是通过策略模式/插件机制实现——新增相似业务逻辑是否需要重写核心分支而非扩展现有逻辑。

**怎么查**：
```bash
grep -rn 'if.*else if\|switch.*case\|instanceof' my-xhs-<module>/src/main/java/com/myxhs/*/service/ | wc -l
grep -rn 'Strategy\|@Component.*implements\|Plugin\|Handler\|Processor' my-xhs-<module>/src/main/java/com/myxhs/*/service/ | wc -l
```

**判定**：
- if-else 链 >5 个处理不同类型→新增类型需改核心文件→开闭原则违反
- 有 `@Component` 策略注册 + `List<Handler>` 自动注入→新增只加新类
- **记录即可，不强制修改。**

**案例**：（全特性面预置检查项——按模块实际 grep 结果填充。my-xhs 折扣计算/支付方式选择等可能有 if-else 链。）

---

### 13.5 多租户/多环境支持 | 透镜：可扩展性/工程

**必须检查**：模块是否支持多租户（tenantId 隔离）或多环境（dev/staging/prod 通过外部化配置切换）；是否硬编码了环境相关值。

**怎么查**：
```bash
grep -rn 'dev\|staging\|prod\|localhost\|127.0.0.1\|tenantId' my-xhs-<module>/src/main/java/ | grep -v 'import\|//\|test/\|resources/'
```

**判定**：
- 硬编码 `localhost:8848`→不同环境需改代码
- URL 中有 `dev`/`prod` 路径→不适合多环境
- 数据无 `tenantId`→SaaS 化需改全部表+查询
- **记录即可，不强制修改。**

**案例**：（全特性面预置检查项——my-xhs 配置已外部化到 Nacos，多环境支持通过 Nacos namespace 实现。）

---

### 13.6 架构演进成本总评 | 透镜：可扩展性

**必须检查**：综合 13.1-13.5 的评估，判断模块的架构演进成本——如果未来 2 年要重大重构，改动面有多大。

**怎么查**：汇总 13.1-13.5 的 `wc -l` 计数，除以模块总代码行数，得到耦合密度。

**判定**：

| 耦合密度 | 评估 |
|:--:|------|
| >50% | 高度耦合→重构等同于重写 |
| 20-50% | 中度耦合→重构大部分类 |
| <20% | 低度耦合→重构局部 |

**案例**：（全特性面预置检查项——my-xhs 全模块耦合密度需逐模块计算后填充。）

---

## 跨维度引用

| 相关概念 | 主维度 | 说明 |
|---------|:--:|------|
| MQ 中间件耦合 | 04.12 | RocketMQTemplate 绑定/Stream 抽象 |
| 缓存中间件耦合 | 05.8 | redisOperator/Spring Cache 抽象 |
| 注册中心耦合 | 11.5 | Nacos API vs DiscoveryClient |
| 鉴权框架耦合 | 07.8 | 手动 if-then vs SecurityFilterChain |
| 分布式事务框架耦合 | 06.6 | 手动 fence vs Seata TCC/AT |
| 验证框架耦合 | 01.14 | javax.validation → Jakarta EE 10 |

---

## 验证命令汇总

```bash
mvn compile test-compile -pl <module> -am
mvn test -pl <module>

# Feign 绑定深度
grep -rn '@FeignClient' my-xhs-<module>/src/main/java/ | wc -l

# MyBatis-Plus 绑定深度
grep -rn 'extends BaseMapper\|extends IService' my-xhs-<module>/src/main/java/ | wc -l

# if-else 链 vs 策略模式
grep -rn 'if.*else if\|switch.*case' my-xhs-<module>/src/main/java/com/myxhs/*/service/ | wc -l
grep -rn 'Strategy\|@Component.*implements\|Plugin' my-xhs-<module>/src/main/java/com/myxhs/*/service/ | wc -l

# 环境硬编码
grep -rn 'localhost\|127.0.0.1\|dev\|staging' my-xhs-<module>/src/main/java/ | grep -v 'import\|//'

# 总代码行数
find my-xhs-<module>/src/main/java -name '*.java' | xargs wc -l | tail -1
```
