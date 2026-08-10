# 25 已知陷阱库

> 复审维度 25 | 元层级 | 防重复犯错——每次审查发现新模式后追加，形成知识沉淀
>
> 你是谁：分布式系统审查员。在审查**任何模块**之前先扫一遍本列表——这些是过往审查已确认过的通用陷阱模式，避免被同样的坑绊倒第二次。

---

**执行本维度后，必须在审查报告中输出 `[25] 已知陷阱库检查：命中 N 项已知陷阱、新增 M 项新陷阱`。缺失此标题 = 未使用陷阱库。**

## 陷阱清单

### T1. @RequiredArgsConstructor + final = Spring 注入被阻止

**陷阱**：`@RequiredArgsConstructor` 类中 `final String script = new DefaultRedisScript<>()`→Lombok 生成的构造器用字段初始值→Spring 注入的值被忽略→字段是编译期常量而非配置值。

**检测**：
```bash
grep -rn '@RequiredArgsConstructor' <module>/ -l | xargs grep -l 'final.*= new\|final.*=.*;'
```
**所有 Lua 脚本字段 + StringRedisTemplate + 配置字段 → 非 final。**

### T2. `update(null, wrapper)` 不触发 MetaObjectHandler

**陷阱**：MyBatis-Plus `update(null, LambdaUpdateWrapper)` 不触发自动填充→`updatedAt` 不变。需显式 `.set(Entity::getUpdatedAt, LocalDateTime.now())`。

**检测**：
```bash
grep -rln 'update(null' <module>/ | xargs grep -L '\.set.*[Uu]pdatedAt'
```

### T3. 空 Token `"".equals("")` → 鉴权绕过

**陷阱**：`@Value("${ADMIN_TOKEN:}")` 环境变量未配→值为 `""`→`"".equals("")` 为 true→鉴权绕过。

**检测**：
```bash
grep -rn 'equals.*[Tt]oken\|contains.*[Tt]oken' <module>/ | grep -v 'isEmpty\|isBlank'
```
**所有 Token 比较前必须 `!token.isEmpty()` 前置检查。**

### T4. `@Validated` 类级缺省 → 参数注解静默无效

**陷阱**：Controller 方法参数上有 `@Min`/`@Max` 但 Controller 类上没有 `@Validated`→注解完全不生效。

**检测**：
```bash
grep -rn '@Min\|@Max' <module>/controller/ | cut -d: -f1 | sort -u | while read f; do
  head -15 "$f" | grep -q '@Validated' || echo "MISSING: $f"
done
```

### T5. `@Valid` 嵌套缺省 → 嵌套注解全静默无效

**陷阱**：DTO 中 `private List<MergeItem> items;` 缺 `@Valid`→MergeItem 内部的 `@NotNull` 等全部不生效。

**检测**：
```bash
grep -rn 'private List<.*\|private .*Request \|private .*Item ' <module>/dto/request/ | grep -v '@Valid'
```

### T6. Fence 结果被忽略 → 装饰性幂等

**陷阱**：TCC `tryFence()` 返回 DUPLICATE/REJECTED→代码校验了返回值→但校验后仍然执行了业务→双扣/双冻。

**检测**：逐 fence 调用点验证返回值后的分支逻辑。

### T7. Canal UPDATE → DELETE L1 权威数据

**陷阱**：L1 权威（Redis 先写 + MQ→MySQL 异步）→Canal 监听 MySQL UPDATE→DELETE Redis→库存/计数丢失。

**规则**：L1 权威场景下，UPDATE 事件**永远不 DELETE Redis**，只标记刷新或跳过。

### T8. 补录回填滞后 MySQL → 覆盖 Redis 权威

**陷阱**：Redis miss→查 MySQL→回填 Redis→但当前 Redis 值比 MySQL 快照新（在途 MQ 更新未落库）→回填覆盖了正确值。

**规则**：L1 权威数据**永不从 MySQL 回填 Redis**。只有 Cache-Aside（MySQL 权威 + Redis 缓存）可以回填。

### T9. Outbox 四部缺失

**陷阱**：Outbox 模式四个步骤缺一不可：
1. send 成功 → markSent（send**后**，不是前）
2. send 失败 → cancelOutboxEvent
3. Job 查 SendResult → 确认 SEND_OK 才 markSent
4. payload 格式与 Consumer 一致（camelCase）

**检测**：grep markSent/cancelOutbox/OutboxSenderJob/SendResult。

### T10. 分布式锁非原子释放

**陷阱**：`get(key) → equals → delete(key)` 三步非原子→可能误删他人锁。

**规则**：释放必须用 Lua `GET + 比较 + DEL` 原子化。锁超时 > 业务最大执行时间 × 1.5。

### T11. 签名重构→参数语义错误

**陷阱**：重构改变方法签名→删除一个参数→剩余参数在各调用点的语义可能错误。`member` 在正向索引=userId，反向索引=bizId→手一删就错了。

**规则**：重构签名后，列出方法内所有数据结构，逐调用点确认参数语义匹配。

### T12. Producer-Consumer ID 派生不一致

**陷阱**：order 用真实 `orderId` 调 inventory，inventory 用 `fold-hash orderNo` 作为 pseudoOrderId→同一订单号的 ID 在不同模块长得不同→跨模块互动失败。

**规则**：同一个业务 ID 在所有模块中派生算法必须相同，或使用统一的 ID service。

### T13. catch 块 TOCTOU——用 now() 替代 eventTime

**陷阱**：try 有时间和 check → INSERT 冲突→catch 用 `LocalDateTime.now()` 而非 payload 的 `eventTime`→旧事件覆盖新状态。

**规则**：catch 块必须镜像 try 的保护条件——用 eventTime 而非 now()。

### T14. 对账盲写全字段→覆盖并发变更

**陷阱**：对账 Job `selectById → updateById(全实体)`→对账执行中和用户操作并发→用户写被对账覆盖。

**规则**：对账只 UPDATE 目标字段，永不用全实体 `updateById`。

---

## 追加规则

每轮审查完成后，如果有新发现的通用陷阱模式，追加到此清单末尾，并标注发现日期和来源模块。
