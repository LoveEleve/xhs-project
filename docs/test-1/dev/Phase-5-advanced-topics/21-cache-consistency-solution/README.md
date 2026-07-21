# 缓存一致性方案

> 跨服务专题 | 开发阶段：Phase-5 | 状态：⏳ 待开发

---

## 🎯 一、问题场景

### 1.1 为什么需要缓存一致性？

```
用户改了昵称，但首页还显示旧昵称——因为缓存没更新。
商品下架了，但搜索还能搜到——因为 ES 没同步。
```

缓存与数据库的数据不一致是分布式系统的经典问题。my-xhs 采用三重保障方案：

| 层级 | 策略 | 适用场景 | 一致性级别 |
|------|------|----------|-----------|
| L1 | Cache Aside | 通用读多写少（用户信息/笔记详情） | 最终一致（毫秒级窗口） |
| L2 | 延迟双删 | 强一致需求（商品上下架/笔记审核） | 强一致（双删覆盖并发窗口） |
| L3 | Canal + Binlog | 异步兜底（所有场景） | 最终一致（秒级延迟） |

### 1.2 涉及服务

| 服务 | 缓存场景 | 策略 |
|------|----------|------|
| my-xhs-user | 用户信息缓存 | Cache Aside |
| my-xhs-product | 多级缓存（Caffeine→Redis→MySQL） | Cache Aside + 延迟双删 |
| my-xhs-content | 笔记详情缓存 | Cache Aside + 延迟双删 |
| my-xhs-common | CacheHelper/DelayDeleteHelper 封装 | 公共组件 |

---

## 🏗️ 二、三重保障架构

### 2.1 Cache Aside（先更新 DB → 再删缓存）

```
写操作：
1. UPDATE MySQL SET name='新昵称' WHERE id=1
2. DEL Redis user:info:1
3. 下次读 → Cache Miss → 查 DB → 回填缓存

为什么不是"先删缓存再更新DB"？
→ 并发场景：线程A删缓存 → 线程B读Miss查到旧值回填 → 线程A更新DB
→ 结果：缓存是旧值，DB是新值，永久不一致！
```

### 2.2 延迟双删（强一致场景）

```
1. DEL Redis product:spu:123        ← 第一次删
2. UPDATE MySQL SET status='下架'    ← 更新DB
3. sleep(500ms)                      ← 等待主从同步+并发读完成
4. DEL Redis product:spu:123        ← 第二次删（覆盖并发读回填的旧值）

延迟时间 500ms 怎么确定？
→ 主从同步延迟（通常 < 200ms）+ 业务读耗时（通常 < 100ms）+ 安全余量
```

### 2.3 Canal 异步兜底

```
MySQL Binlog → Canal → RocketMQ → Consumer → DEL Redis / 更新 ES

兜底场景：
- Cache Aside 删缓存失败（Redis 网络抖动）
- 延迟双删第二次删失败
- 直接操作 DB（运维修数据）绕过应用层
```

---

## 💻 三、核心代码实现

### 3.1 CacheHelper（Cache Aside 封装）

```java
/**
 * Cache Aside 模板方法
 * 读：先缓存→Miss→查DB→回填
 * 写：先更新DB→再删缓存→失败重试3次→Canal兜底
 */
@Component
public class CacheHelper {

    public <T> T getWithCacheAside(String key, Class<T> type,
                                    Function<String, T> dbFallback,
                                    long ttl, TimeUnit unit) {
        // 1. 查缓存
        String json = redisOperator.get(key);
        if (json != null) {
            // 空值缓存（防穿透）返回 null
            if (json.isEmpty()) return null;
            return JSON.parseObject(json, type);
        }

        // 2. 查 DB
        T data = dbFallback.apply(key);
        if (data == null) {
            // 空值缓存防穿透
            redisOperator.set(key, "", 60, TimeUnit.SECONDS);
            return null;
        }

        // 3. 回填缓存（随机 TTL 防雪崩）
        long randomTtl = ttl + ThreadLocalRandom.current().nextLong(0, 300);
        redisOperator.set(key, JSON.toJSONString(data), randomTtl, unit);
        return data;
    }

    public void deleteAfterUpdate(String... keys) {
        for (String key : keys) {
            for (int i = 0; i < 3; i++) { // 重试3次
                try {
                    redisOperator.delete(key);
                    break;
                } catch (Exception e) {
                    if (i == 2) log.error("删缓存失败，等待Canal兜底: key={}", key, e);
                }
            }
        }
    }
}
```

### 3.2 DelayDeleteHelper（延迟双删）

```java
/**
 * 延迟双删封装
 * 第一次删 → 更新DB → 延迟500ms → 第二次删
 */
@Component
public class DelayDeleteHelper {

    private final ScheduledExecutorService scheduler =
        Executors.newScheduledThreadPool(4);

    public void doubleDelete(String key, Runnable dbUpdate) {
        // 1. 第一次删
        redisOperator.delete(key);

        // 2. 更新 DB
        dbUpdate.run();

        // 3. 延迟 500ms 第二次删
        scheduler.schedule(() -> {
            try {
                redisOperator.delete(key);
            } catch (Exception e) {
                log.error("延迟双删第二次失败，等待Canal兜底: key={}", key, e);
            }
        }, 500, TimeUnit.MILLISECONDS);
    }
}
```

---

## ⚖️ 四、方案对比

| 维度 | Cache Aside | 延迟双删 | Canal 兜底 |
|------|------------|---------|-----------|
| 一致性 | 最终一致（毫秒级） | 强一致 | 最终一致（秒级） |
| 性能 | 高 | 中（多一次删除） | 高（异步） |
| 复杂度 | 低 | 中 | 高（需部署 Canal） |
| 适用场景 | 通用 | 上下架/审核 | 兜底 |

---

## 🐛 五、踩坑记录

### 5.1 延迟双删延时太短

- **现象**：双删后缓存仍是旧值
- **原因**：主从同步延迟 > 500ms（大事务场景）
- **解决**：延迟时间改为 1 秒，或根据主从延迟监控动态调整

### 5.2 Canal 消费乱序

- **现象**：Canal 先收到 DELETE 再收到 INSERT，导致缓存被错误删除
- **解决**：Canal 消费时检查数据版本（updated_at），旧版本事件丢弃

---

## 🎤 六、面试考察点

### Q1: 为什么是"先更新DB再删缓存"而不是反过来？

> 1. "先删缓存再更新DB有并发问题：线程A删缓存→线程B读Miss查到旧值回填→线程A更新DB→缓存永久是旧值"
> 2. "先更新DB再删缓存：即使删缓存失败，下次读到的也是旧缓存，Canal兜底会最终删除"
> 3. "延迟双删覆盖极端场景：第一次删→更新DB→500ms→第二次删，覆盖并发读回填的旧值"

### Q2: 延迟双删的延时时间怎么确定？

> 1. "主从同步延迟（通常 < 200ms）+ 业务读耗时（< 100ms）+ 安全余量 = 500ms"
> 2. "可以通过监控主从延迟动态调整"
> 3. "太短：第二次删时从库还没同步完，并发读仍读到旧值"
> 4. "太长：不一致窗口变大，用户体验差"

---

## 📚 参考资料

| 资料 | 参考内容 |
|------|----------|
| 📄 phase-5/README.md §3.21 | 缓存一致性完整设计 |
| 📄 03-distributed-solutions.md §1 | 缓存一致性 5 方案对比 |
