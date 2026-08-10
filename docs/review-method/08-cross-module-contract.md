# 08 跨模块契约

> 复审维度 08 | 每个模块必查 | 9 透镜全覆盖，跨服务接口/数据格式的一致性为本维度核心
>
> 你是谁：分布式系统审查员。不凭记忆，逐条执行以下指令。每个检查项标注了透镜类型，覆盖
> 业务逻辑/工程正确性/分布式正确性/生产级/可扩展性/并发/微服务/性能/盲区 全部 9 个维度。
> 注：跨模块 MQ 契约（Producer-Consumer 字段名/ID 派生一致）见 04.3。

---


**执行本维度后，必须在审查报告中输出 `[08] 08 跨模块契约：发现 N 项`。缺失此标题 = 本维度未被执行。执行每条检查项时，标注来源维度编号（如 [08]）。**
## 检查项

### 8.1 Redis Key 格式一致性 | 透镜：分布式/工程

**必须检查**：跨模块共享的 Redis key 格式是否统一——前缀、分隔符、字段顺序是否一致。

**怎么查**：
```bash
grep -rn 'myxhs:\|RedisKeyConstants' my-xhs-<module>/src/main/java/ | sed 's/.*\("\|myxhs:\)/\1/' | sort | uniq -c | sort -rn
```
逐 key 对比：同一业务概念在不同模块的 key 格式是否一致。

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 前缀不一致 | counter 用 `myxhs:counter:like:{noteId}`，analytics 用 `myxhs:analytics:like:note:{noteId}`→同一数据两个 key 名 |
| 字段顺序不同 | 模块 A: `{userId}:{bizType}` / 模块 B: `{bizType}:{userId}`→彼此读不到对方数据 |
| 有 RedisKeyConstants 但未全用 | 部分 key 直接字符串拼接、部分用常量→格式漂移 |

**案例**：counter 和 analytics 用不同 key 格式存同语义点赞数据→双份数据各自维护→对账时发现 key 名不匹配（`CounterService.reconcileLikeFromAnalytics` 修复）。

---

### 8.2 业务 ID 跨模块派生一致 | 透镜：分布式/盲区

**必须检查**：同一个业务 ID 在不同模块的派生算法是否一致——如果模块 A 用 `foldHash(orderNo)` 生成 `pseudoOrderId`，模块 B 也必须用**同样算法**。

**怎么查**：
```bash
grep -rn 'derive.*OrderId\|translateOrderId\|pseudoOrderId\|fold.*hash\|computeOrderId' my-xhs-*/src/main/java/
```
逐 ID 派生逻辑对比：两个模块对同一个输入是否输出相同输出。

**判定**：
- order 用 `"ORDER_" + orderNo` 派生 / inventory 用 `foldHash(orderNo)` 派生→同一订单号生成不同 ID→跨模块查不到
- 一个模块用 MD5 截 8 位、另一个用 SHA hash→碰撞概率不同→虽语义相同但实现不一致
- 新模块加入时没调用统一的 ID 派生工具→各自发明→分裂

**案例**：order `releaseInventory` 用真实 orderId→inventory `deduct` 用 fold-hash pseudoOrderId→释放库存时永远查不到预扣记录（修复 order 新增 `derivePseudoOrderId` 与 inventory 一致）。

---

### 8.3 Feign 接口契约 | 透镜：微服务/工程

**必须检查**：Feign 接口定义和生产端 Controller 的实际方法签名是否一致；返回类型 R<T> 泛型是否匹配；Fallback 是否覆盖所有异常。

**怎么查**：
```bash
grep -rn '@FeignClient\|@GetMapping\|@PostMapping' my-xhs-<module>/src/main/java/com/myxhs/*/feign/ -A5
grep -rn 'FallbackFactory\|Fallback' my-xhs-<module>/src/main/java/com/myxhs/*/feign/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| Feign 方法与 Controller 不一致 | Feign 声明 `R<SpuVO> getSpu(Long id)` / Controller 实现 `R<SkuVO> getSpu(Long skuId)`→参数语义不同+返回类型不同 |
| Fallback 未覆盖所有方法 | Feign 新增方法，Fallback 未更新→调新方法抛 500 |
| Fallback 返回 null | `return R.fail("降级")` 但实际未返回对象→调用方 NPE |
| Feign Config 不统一 | 每个调用方各自配置拦截器→Token/Header 漏传（见 07.4） |

**案例**：order `InventoryFeignClient` 新增 `confirmDeduct` 方法，FallbackFactory 未同步→调用时降级路径 500（修复补 Fallback 方法）。

---

### 8.4 跨模块级联操作完整 | 透镜：业务/分布式

**必须检查**：当一个操作涉及多个模块时（如删除笔记→清理评论→清理点赞→清理 Feed），每个下游操作是否**全部被执行**。

**怎么查**：
```bash
# 找出所有 send MQ / Feign call 的调用链
grep -rn 'asyncSend\|syncSend\|@FeignClient' my-xhs-<module>/src/main/java/ -B3
```
画调用图：操作 P 触发了哪些 MQ/Feign→下游哪些 Consumer 响应→是否有遗漏。

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 级联断裂 | 删除笔记→发 MQ 清理 Feed→但没有人发 MQ 清理点赞→僵尸数据 |
| 级联顺序错误 | 先删笔记→后删评论→但评论 Consumer 已读到笔记删除→评论删除永远失败 |
| 缺少补偿 | 级联的某一环失败了→无补偿→数据残留 |

**案例**：`deleteNote` 不统一发 deleteCommentEvent→评论变成孤儿数据（修复后 `compensateIncompletePush` 统一补发）。

---

### 8.5 服务间循环依赖 | 透镜：微服务/盲区

**必须检查**：模块间的依赖是否有循环——A 调 B 的 Feign 接口，B 又调 A 的 Feign 接口。

**怎么查**：
```bash
grep -rn '@FeignClient' my-xhs-*/src/main/java/com/myxhs/*/feign/ -A1 | grep -oP '(?<=name = ")[^"]*'
```
画模块依赖图：把每个 Feign 的 `name` 字段和目标模块对应→检查回路。

**判定**：
- A→B→A 循环→A 出问题时无法降级（B → A 的 Fallback 也依赖 A）→雪崩
- 循环中的任何一个服务启动慢→级联启动超时

**案例**：（全特性面预置检查项——my-xhs 02-07 的 Feign 调用均为单向，08-15 未审模块的 order↔payment 可能有双向调用需核查。）

---

### 8.6 跨模块解耦与抽象层 | 透镜：可扩展性

**必须检查**：模块间的交互是否通过抽象接口（Feign Interface / MQ 事件），还是直接耦合对方的具体实现类或共享模块。切交互方式的成本。

**怎么查**：
```bash
grep -rn 'import com.myxhs.*.service\.\|import com.myxhs.*.entity\.' my-xhs-<module>/src/main/java/ | grep -v '/feign/\|/dto/\|/event/'
```
命中行→直接依赖对方 Service/Entity→跨模块强耦合。

**判定**：
- 直接 `import` 其他模块的内部类→编译期强耦合→拆分/重构困难
- 通过 MQ 事件 + Feign 接口→松耦合→换交互方式只改适配层
- **记录即可，不强制修改。**

**案例**：（全特性面预置检查项——my-xhs 是否有跨模块直接 import Service/Entity 需逐模块 grep 确认。）

---

### 8.7 跨服务配置一致性 | 透镜：工程/分布式

**必须检查**：同一配置项（RocketMQ topic、Redis 集群地址、Nacos namespace）在多个模块中是否一致。

**怎么查**：
```bash
grep -rn 'topic.*=\|name-server\|server-addr\|namespace' my-xhs-*/src/main/resources/ | sort
```

**判定**：
- counter 连接 `rocketmq:9876`，inventory 连接 `localhost:9876`→不同环境配置→消息互相收不到
- Nacos namespace 不一致→Feign 服务发现失败→503
- Redis key prefix 不一致→跨模块共享的数据分开存→无法共享（8.1 已覆盖）

**案例**：（全特性面预置检查项——my-xhs 配置主要通过 Nacos 统一管理，但模块内残留的 yml 硬编码可能导致不一致。）

---

## 跨维度引用

| 相关概念 | 主维度 | 说明 |
|---------|:--:|------|
| MQ Producer-Consumer 字段名/ID 契约 | 04.3 | camelCase 一致 / ID 派生一致 |
| Feign 内部调用 Token | 07.4 | INTERNAL_CALL_TOKEN / X-Internal-Call |
| 双写回滚跨模块 | 03.2 | MQ 失败回滚 DB |

---

## 验证命令汇总

```bash
mvn compile test-compile -pl <module> -am
mvn test -pl <module>

# Redis key 格式对比
grep -rn 'myxhs:' my-xhs-<module>/src/main/java/ | sort | uniq -c | sort -rn

# ID 派生算法
grep -rn 'derive.*Id\|translateId\|pseudoId\|computeId\|fold' my-xhs-<module>/src/main/java/

# Feign 接口列表
grep -rn '@FeignClient' my-xhs-<module>/src/main/java/com/myxhs/*/feign/ -A5

# Feign Fallback 覆盖
grep -rn 'FallbackFactory\|Fallback' my-xhs-<module>/src/main/java/com/myxhs/*/feign/

# 级联调用链
grep -rn 'asyncSend\|syncSend' my-xhs-<module>/src/main/java/ -B3

# 循环依赖——跨模块 Feign 调用
for m in my-xhs-*/src/main/java/com/myxhs/*/feign/; do
  echo "=== $(dirname $m) ===" && grep '@FeignClient' "$m"*.java -A1 2>/dev/null
done

# 配置项一致性
grep -rn 'name-server\|server-addr\|namespace' my-xhs-*/src/main/resources/
```
