# my-xhs 缓存体系

> 5 种策略 | Redis 7-alpine 256MB | Sentinel 高可用 | 双连接池

---

## 一、策略总览

| 策略 | 服务 | 核心机制 | 一致性 | 可用性 |
|------|------|------|:--:|:--:|
| **延迟双删** | user | DB更新后立即删 + 500ms二次删 + MQ兜底 | 最终一致(500ms窗口) | 高(3层兜底) |
| **逻辑过期** | product | 返回旧值 + 异步重建 + Redisson RLock互斥 | 最终一致(>TTL) | 高(永不过期) |
| **Cache Aside** | content, counter, coupon | 读Miss查DB→回写→TTL | 最终一致(TTL内) | 中(击穿风险) |
| **TCC 预扣** | inventory | Try冻结→Confirm扣除→Cancel解冻 | 中间状态可见 | 高(Fence防护) |
| **Buffer 攒批** | counter | 内存Buffer→定时百条刷DB→对账 | 最终一致(刷盘间隔) | 中(Buffer丢失风险) |

---

## 二、延迟双删 (user)

**适用场景**: 用户个人信息、地址 — 写少读多，可接受短暂脏读

### 三层兜底

```
L1 (立即删): DB更新后同步调用 redisOperator.delete(key)
     ↓ 失败/未触发
L2 (500ms延迟, 可配): taskExecutor.schedule(() → redisOperator.delete(key), 500ms)
     (myxhs.cache.double-delete-delay-ms 默认500ms)
     ↓ 失败/未触发
L3 (MQ兜底):  rocketMQTemplate.syncSend(CACHE_EVICT_TOPIC, CacheEvictMessage(key))
     → CacheEvictConsumer.consume() → redisOperator.delete(key) → 抛异常重试 → DLQ
```

### 写路径

```java
// UserService.updateUser()
@Transactional
public void updateUser(Long userId, UserUpdateDTO dto) {
    userMapper.updateById(user);                    // MySQL UPDATE 走主库
    cacheHelper.delayDoubleDelete(USER_INFO_KEY + userId);  // L1 + L2 + L3
}
```

### 读路径

```
Redis GET USER_INFO:{userId} → 命中返回 → 未命中查DB → 回写Redis(30min TTL)
```

### Redis Key

| Key | 用途 | TTL |
|------|------|:--:|
| `myxhs:user:info:{userId}` | 用户信息 | 30min |
| `myxhs:user:address:default:{userId}` | 默认地址 | 5min |
| `myxhs:user:token:access:{userId}` | 登录Token | 30min |
| `myxhs:user:token:refresh:{userId}` | 刷新Token | 7day |
| `myxhs:user:token:blacklist:{jti}` | 黑名单 | 剩余有效期 |
| `myxhs:user:captcha:{key}` | 验证码(GETDEL原子) | 5min |
| `myxhs:user:block:{userId}` | 屏蔽列表(Set) | 365day |
| `myxhs:user:login:fail:{username}` | 登录失败计数 | 15min |
| `myxhs:user:address:lock:{userId}` | 地址操作锁 | 3s |

---

## 三、逻辑过期 + 布隆过滤器 (product)

**适用场景**: 商品 SPU/SKU — 读多写少，不能因缓存击穿拖垮 MySQL

### 逻辑过期（永不过期 Key）

```
读请求:
  1. Redis GET myxhs:product:spu:{spuId}
     → 命中: 检查 expireTime 字段 → 未过期: 直接返回
                                     → 已过期: 返回旧数据 + 异步重建
     → 未命中: 查DB → 回写Redis(逻辑过期时间=now+30min) → 返回
  
异步重建:
  1. Redisson RLock `myxhs:product:lock:spu:{spuId}` (tryLock(0,10s) 非阻塞互斥)
     → 获取成功: MySQL SELECT + 回写Redis(逻辑过期时间)
     → 获取失败: 放弃(其他线程正在重建)
```

### 布隆过滤器防穿透

```java
// 启动时加载全量 SPU ID
@PostConstruct
public void initBloomFilter() {
    List<Long> spuIds = spuMapper.selectAllIds();
    for (Long id : spuIds) {
        rbBloomFilter.add(BLOOM_SPU_KEY, id);  // BF.ADD
    }
}
```

| 属性 | 值 |
|------|------|
| Key | `myxhs:product:bloom:spu` |
| 预期容量 | 100万, error_rate=0.01 |
| 维护 | SPU创建时 afterCommit BF.ADD; SPU删除时不清理(可接受误判) |
| 降级 | Redis不可用 → 布隆降级放行 → 直查MySQL |

### Redis Key

| Key | 用途 | TTL |
|------|------|:--:|
| `myxhs:product:spu:{spuId}` | SPU详情 | 逻辑过期30min(Key不删) |
| `myxhs:product:sku:{skuId}` | SKU详情 | 逻辑过期30min |
| `myxhs:product:bloom:spu` | 布隆过滤器 | 永久 |
| `myxhs:product:lock:spu:{spuId}` | 重建互斥锁(Redisson RLock) | 10s租约 |

---

## 四、Cache Aside (content, counter, coupon)

**适用场景**: 笔记详情、计数查询、券模板 — 标准读多写少

### 读写路径

```
读: Redis GET → 命中返回
              → 未命中 → MySQL SELECT → 回写Redis(TTL 30min) → 返回
写: MySQL UPDATE → Redis DEL (afterCommit)
```

### 特殊: counter 的 Like 去重

点赞计数不直接 INCR，而是通过 Redis Set + SCARD 消除 MQ 乱序:

```
INCR counter + SADD like:set → SCARD like:set = 实际点赞数
  (应对 UNLIKE 先到达的乱序场景 — Set 已删除则 SCARD 自然减少)
```

### Redis Key (content/counter)

| Key | 用途 | TTL |
|------|------|:--:|
| `myxhs:note:detail:{noteId}` | 笔记详情 | 30min |
| `myxhs:comment:list:{noteId}` | 评论列表缓存 | 5min |
| `myxhs:counter:{targetType}:{targetId}:{countType}` | 实时计数 | 永久 |
| `myxhs:counter:dedup:{msgId}` | MQ去重标记 | 2h |
| `myxhs:like:set:{type}:{id}` | 点赞用户Set | 7day |
| `myxhs:coupon:{templateId}:stock` | 券库存 | 永久(逻辑过期) |
| `myxhs:coupon:{templateId}:claimed:{userId}` | 用户已领标记(Set) | 领券成功写入 |
| `myxhs:coupon:template:{templateId}` | 券模板缓存 | 30min |

---

## 五、TCC 预扣 (inventory)

**适用场景**: 库存 — 强一致性需求，不能超卖也不能少卖

### Try-Confirm-Cancel 三阶段

```
Try (下单时):
  1. tccFenceService.tryFence(bizId) → 幂等检查 (Redis版本号+MySQL Fence表)
  2. Lua: available_stock -= qty, freezing_stock += qty
  3. INSERT t_tcc_freeze_detail (xid, branch_id, sku_id, qty)

Confirm (支付成功):
  1. confirmFence() → 状态 1→2
  2. Lua: freezing_stock -= qty

Cancel (关单/退款):
  1. cancelFence() → 空回滚: Insert成功=SKIP / 乐观锁WHERE status=1 → 1→3
  2. Lua: freezing_stock -= qty, available_stock += qty
  3. TccTimeoutJob: @Scheduled(60s) 扫超10分钟冻结 → 逐条Cancel
```

### Fence 表 (防悬挂/空回滚)

| 表 | 字段 | 用途 |
|------|------|------|
| `t_tcc_fence` | xid, branch_id (PK), status | 1=Try/2=Confirm/3=Cancel |
| `t_tcc_freeze_detail` | xid, branch_id, sku_id (PK), qty, status | 明细 1/2/3 |

### Redis Key
| Key | 用途 | TTL |
|------|------|:--:|
| `inventory:{skuId}:total` | 总库存(String) | 永久 |
| `inventory:prededuct:{orderId}` | 预扣记录(Hash, field=skuId) | 永久(预扣30min过期) |
| `inventory:{skuId}:bucket:{n}` | 分桶库存(String) | 永久 |
| `inventory:event:version:{orderId}:{skuId}` | 库存事件版本号 | 7day |
| `inventory:bucket:count:{skuId}` | 桶数 | 永久 |
| `inventory:prededuct:index` | 预扣过期索引(ZSet) | 永久 |

### 库存缓存失效 (Canal→MQ→Redis)

```
Canal inventory_instance → INVENTORY_CACHE_TOPIC
  → InventoryCacheEvictConsumer
    INSERT: 删除Redis缓存(缓存穿透保护)
    UPDATE: 跳过 ← L2回声保护(Redis是L1权威)
    DELETE: 完全删除(SCAN inventory:{skuId}:bucket:*)
  → Canal es版本号Lua原子防乱序
```

---

## 六、Buffer 攒批 (counter)

**适用场景**: 高频计数写入 — 降低 MySQL 写压力

### 写入路径

```
MQ事件 → CounterEventConsumer
  → msgId去重(Redis SETNX + TTL 2h)
  → Redis INCR/DECR 实时计数 (查询走这里)
  → 内存Buffer.add(event) 攒批 (ConcurrentHashMap)
  → Buffer满100条 → batchUpdate MySQL t_counter
  → 每5s定时刷盘 → 凌晨对账(MySQL修正Redis)
```

### 对账机制

```
CounterReconcileJob (凌晨3:00):
  游标扫描 t_counter → 逐条对比 Redis → 不一致: Redis SET = MySQL值
```

---

## 七、布隆过滤器降级 (product)

产品搜索场景: Redis 完全不可用时，布隆过滤器降级方案:

```
正常: Redis查询布隆 → 存在 → 查缓存 → 命中返回
                                     → 未命中 → 查DB (可能穿透)
降级: Redis不可用 → 布隆降级放行 → 直查MySQL
                  → 恢复后重建布隆(全量SPU)
```

| 降级层级 | 触发条件 | 行为 |
|:--:|------|------|
| 正常 | Redis UP | 布隆过滤 → 缓存 → DB |
| 降级1 | Redis DOWN | 直查MySQL (无本地缓存, 依赖MySQL承载) |
| 降级2 | 布隆/缓存全部失效 | 直查DB |
| 恢复 | Redis UP | 重建布隆(全量SPU ID) |

---

## 八、Redis Key 命名规范

### 前缀层级

```
myxhs:{domain}:{entity}:{subtype}:{id}
│      │        │        │           └── 唯一标识
│      │        │        └── 子类型(bloom/lock/dedup/bucket)
│      │        └── 实体(spu/sku/order/user/note/counter)
│      └── 域(product/inventory/coupon/user/content/analytics/cart/counter)
└── 全局命名空间
```

### TTL 分级

| 等级 | TTL | 适用场景 |
|:--:|:--:|------|
| 短 | 3-30s | 分布式锁、互斥锁 |
| 中 | 5-30min | 验证码、用户信息、商品详情 |
| 长 | 1-7day | Token、去重、社交数据、版本号 |
| 永 | 无过期 | 计数、库存、布隆 |

### Redis 配置

```yaml
spring.data.redis:
  host: 21.130.247.89
  password: "Xhs@2026#Redis"
  port: 6379
  sentinel: { master: mymaster, nodes: 21.130.247.89:26379 }
  lettuce.pool: { max-active: 15, max-idle: 8, min-idle: 4, max-wait: 3000ms }

# 物理同一Redis，逻辑双连接池隔离
spring.data.redis.business.port: 6379   # 业务缓存
spring.data.redis.cache.port: 6379      # 临时数据
```

### Sentinel 自动切换

```
my-xhs-common RedisConfig:
  检测 sentinel.nodes 配置存在 → Lettuce RedisSentinelConnectionFactory
                              → 自动 Sentinel 模式
                              → Master故障: Sentinel选举 → 客户端自动跟随
```

---

## 九、缓存一致性保障

| 场景 | 机制 | 风险窗口 |
|------|------|:--:|
| 写后读主从延迟 | `@Transactional` 强制主库读 | 0 (事务内) |
| MQ乱序 | Lua版本号原子GET+compare+SET | 0 |
| Redis宕机 | 各服务独立降级策略 | 恢复前 |
| 缓存与DB不一致 | 对账Job(凌晨) + 延迟双删L3 | <24h |
| 缓存击穿 | SETNX互斥锁(逻辑过期) / 布隆过滤器 | <重建时间 |
| 缓存穿透 | 布隆过滤器(拦截不存在的SPU ID) | 布隆误判率1% |
| 缓存雪崩 | 随机TTL(±10%) + 热点永不过期 | 无 |
