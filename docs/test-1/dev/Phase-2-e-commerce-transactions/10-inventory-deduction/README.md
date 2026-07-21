# 库存扣减

> 所属服务：my-xhs-inventory (9008) | 开发阶段：Phase-2 | 状态：⏳ 待开发

---

## ⚠️ 技术约束（Code Review 决策，开发前必读）

> **缓存一致性方案：必须使用 Canal + Binlog 订阅，禁止使用延迟双删。**
>
> **决策来源**：`docs/dev/GLOBAL-CODE-REVIEW.md` → 11.6 跨阶段技术决策待办 #1
>
> **原因**：库存是高并发强一致性场景，延迟双删在高并发读写交叉时有 500ms 不一致窗口（并发读可能读到旧值并回填缓存），对库存扣减场景不可接受，攻击者可利用此窗口超卖。
>
> **实施方案**：
> 1. 部署 Canal Server 监听 MySQL Binlog
> 2. Canal Client 解析 `t_inventory` 表变更事件
> 3. 变更后主动删除 Redis 缓存 Key（`myxhs:inventory:stock:{skuId}`）
> 4. 配合分布式锁 + Lua 脚本保证扣减原子性
> 5. 参考：`docs/dev/Phase-5-advanced-topics/27-canal-data-synchronization/README.md`

---

## 🎯 一、需求分析

### 1.1 业务场景

库存是电商交易的核心难点。采用分桶预扣减方案：将 SKU 库存拆分到 N 个桶（热点 SKU 8 桶，普通 SKU 2 桶），扣减时按 userId 路由到具体桶，避免单 Key 热点。Lua 脚本保证原子扣减。三级扣减保证可靠性：L1 Redis 预扣 → L2 MQ 异步扣 DB → L3 对账修复。预扣 30 分钟未支付自动回退。

### 1.2 功能边界

| 功能项 | 是否实现 | 说明 |
|--------|---------|------|
| 库存初始化 | ✅ | DB → Redis 分桶（按 SKU 拆分 N 个桶） |
| 分桶预扣减 | ✅ | Lua 脚本原子操作，userId 路由到固定桶 |
| 桶间均衡 | ✅ | 路由桶库存不足时遍历其他桶尝试扣减 |
| 确认扣减 | ✅ | 支付成功后确认（删除预扣记录） |
| 释放库存 | ✅ | 取消/超时后 Lua 原子回退 |
| 三级扣减 | ✅ | Redis 预扣 → MQ 异步扣 DB → 对账修复 |
| 预扣超时回退 | ✅ | @Scheduled 每 5 分钟扫描过期预扣记录 |
| 库存流水 | ✅ | 每次变更记录 before/after |

### 1.3 数据量预估

| 指标 | 预估值 | 计算依据 |
|------|--------|----------|
| SKU 总量 | 1000 万 | 中型电商 |
| 库存扣减 QPS | 5000 | 下单峰值 |
| 秒杀场景 QPS | 10 万+ | 热点 SKU 集中扣减 |
| 预扣超时率 | 5% | 下单未支付 |

---

## 🏗️ 二、架构设计

### 2.1 整体架构图

```
Client → Gateway → my-xhs-inventory(9008)
                        │
                        ├── Redis: 分桶库存 + Lua 原子扣减
                        ├── RocketMQ: 异步扣 DB
                        └── MySQL: 持久化 + 对账基准

三级扣减链路：
L1: Redis 分桶预扣（毫秒级，用户立即得到结果）
     ↓ MQ
L2: MySQL 扣减（异步，保证持久化）
     ↓ 定时对账
L3: Redis vs DB 对比修复（兜底）
```

### 2.2 分桶预扣减 Lua 脚本流程

```
prededuct.lua 核心逻辑：

1. 输入：orderId, skuId, quantity, bucketCount, userId
2. 计算路由桶号：bucketNo = userId % bucketCount
3. 检查路由桶库存：GET inventory:bucket:{skuId}:{bucketNo}
4. if 库存充足 → 扣减: DECRBY {skuId}:{bucketNo} quantity
5. if 库存不足 → 遍历其他桶尝试扣减（桶间均衡）
6. 全部桶都不足 → 返回失败
7. 扣减成功 → 写预扣记录: HSET inventory:prededuct:{orderId} {skuId} {quantity}
8. 更新总库存: DECRBY inventory:total:{skuId} quantity
9. 返回成功
```

---

## 🗄️ 三、数据库设计

### 3.1 表结构

```sql
-- 库存表（实际 DDL 来自 init-databases.sql）
CREATE TABLE IF NOT EXISTS t_inventory (
    id              BIGINT   NOT NULL COMMENT 'ID',
    sku_id          BIGINT   NOT NULL COMMENT 'SKU ID',
    available_stock INT      NOT NULL DEFAULT 0 COMMENT '可用库存',
    locked_stock    INT      NOT NULL DEFAULT 0 COMMENT '锁定库存（预扣未确认）',
    deleted         TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_sku_id (sku_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='库存表';
```

---

## 🔑 四、缓存设计

### 4.1 Redis Key 规范

| Key Pattern | 类型 | 过期时间 | 说明 |
|-------------|------|----------|------|
| `inventory:bucket:{skuId}:{bucketNo}` | String | 永久 | 分桶库存值 |
| `inventory:total:{skuId}` | String | 永久 | SKU 总可用库存 |
| `inventory:prededuct:{orderId}` | Hash | 30min | 预扣记录（field=skuId, value=quantity） |
| `inventory:bucket:count:{skuId}` | String | 永久 | 分桶数量（热点 8，普通 2） |

---

## 📡 五、接口设计

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/api/inventory/init` | 库存初始化（入 Redis 分桶） | ✅ |
| POST | `/api/inventory/preDeduct` | 预扣减（下单） | ✅ |
| POST | `/api/inventory/confirm` | 确认扣减（支付成功） | ✅ |
| POST | `/api/inventory/release` | 释放库存（取消/超时） | ✅ |
| GET | `/api/inventory/stock/{skuId}` | 查询可用库存 | ❌ |

---

## 💻 六、核心代码实现

### 6.1 分桶预扣减 Lua 脚本

```lua
-- prededuct.lua：分桶预扣减（原子操作）
local skuId = KEYS[1]
local orderId = ARGV[1]
local quantity = tonumber(ARGV[2])
local bucketCount = tonumber(ARGV[3])
local userId = tonumber(ARGV[4])

-- 1. 计算路由桶号
local routeBucket = userId % bucketCount
local bucketKey = 'inventory:bucket:' .. skuId .. ':' .. routeBucket

-- 2. 检查路由桶库存
local stock = tonumber(redis.call('GET', bucketKey) or '0')
if stock >= quantity then
    -- 路由桶库存充足，直接扣减
    redis.call('DECRBY', bucketKey, quantity)
    redis.call('DECRBY', 'inventory:total:' .. skuId, quantity)
    redis.call('HSET', 'inventory:prededuct:' .. orderId, skuId, quantity)
    redis.call('EXPIRE', 'inventory:prededuct:' .. orderId, 1800) -- 30分钟过期
    return 1 -- 成功
end

-- 3. 路由桶不足，遍历其他桶（桶间均衡）
for i = 0, bucketCount - 1 do
    if i ~= routeBucket then
        local otherKey = 'inventory:bucket:' .. skuId .. ':' .. i
        local otherStock = tonumber(redis.call('GET', otherKey) or '0')
        if otherStock >= quantity then
            redis.call('DECRBY', otherKey, quantity)
            redis.call('DECRBY', 'inventory:total:' .. skuId, quantity)
            redis.call('HSET', 'inventory:prededuct:' .. orderId, skuId, quantity)
            redis.call('EXPIRE', 'inventory:prededuct:' .. orderId, 1800)
            return 1 -- 成功（从其他桶扣减）
        end
    end
end

return 0 -- 全部桶库存不足
```

### 6.2 预扣超时回退

```java
/**
 * 定时扫描过期预扣记录，自动回退库存
 * 防止用户下单后不支付，库存被永久锁定
 */
@Scheduled(fixedRate = 300000) // 每5分钟
public void releaseExpiredPreDeductions() {
    // Redis SCAN 扫描所有 prededuct:* Key
    // TTL 已过期的会自动删除，这里扫描即将过期的做主动回退
    Set<String> keys = redisTemplate.keys("inventory:prededuct:*");
    for (String key : keys) {
        Long ttl = redisTemplate.getExpire(key);
        if (ttl != null && ttl <= 0) {
            // 已过期，执行回退
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
            entries.forEach((skuId, quantity) -> {
                releaseStock(Long.valueOf(skuId.toString()), Integer.parseInt(quantity.toString()));
            });
            redisTemplate.delete(key);
        }
    }
}
```

---

## ⚖️ 七、方案对比

### 7.1 库存扣减 4 版本演进

| 版本 | 方案 | 优点 | 缺点 | 适用场景 |
|------|------|------|------|----------|
| V1 | DB 直接扣减 | 简单 | 性能差（行锁） | QPS < 100 |
| V2 | Redis 预扣减 | 快 | 单 Key 热点 | QPS < 1 万 |
| V3 | Redis 预扣 + MQ 异步落库 | 快+可靠 | 单 Key 热点 | QPS < 1 万 |
| **V4** | **分桶 + Redis 预扣**（✅ 选定） | **快+分散热点** | 复杂 | **QPS 10 万+** |

**选择理由**：秒杀场景单 SKU QPS 可达 10 万+，单 Key Redis 也扛不住。分桶将压力分散到 N 个 Key，每个桶独立扣减。

---

## 🐛 八、踩坑记录

### 8.1 分桶初始化不均匀

- **现象**：8 个桶中有的桶 1000 库存，有的桶 0 库存
- **解决**：初始化时均匀分配 `total / bucketCount`，余数分给第一个桶

### 8.2 桶间均衡导致热点转移

- **现象**：路由桶耗尽后所有请求涌向同一个备选桶
- **解决**：遍历顺序随机化 `(routeBucket + random) % bucketCount`

---

## 📊 九、测试验证

| 测试场景 | 输入 | 预期结果 | 通过 |
|----------|------|----------|------|
| 库存初始化 | skuId + stock=100 + bucket=4 | 4 个桶各 25 | ⬜ |
| 正常预扣减 | quantity=1 | 路由桶 -1，总库存 -1 | ⬜ |
| 桶间均衡 | 路由桶库存=0 | 从其他桶扣减成功 | ⬜ |
| 库存不足 | 所有桶库存=0 | 返回"库存不足" | ⬜ |
| 确认扣减 | 支付成功 | 删除预扣记录 | ⬜ |
| 释放库存 | 取消订单 | 库存回退 | ⬜ |
| 并发扣减 | 100 并发抢最后 1 件 | 只有 1 个成功 | ⬜ |

---

## 🎤 十、面试考察点

### Q1: 高并发下库存怎么保证不超卖？

> 1. "Redis Lua 脚本原子操作：检查库存 → 扣减 → 写预扣记录，一气呵成"
> 2. "Lua 在 Redis 单线程中执行，天然串行，不会出现并发超卖"
> 3. "分桶分散热点：10 万 QPS 分到 8 个桶，每个桶只承担 1.25 万"

### Q2: 库存分桶是什么原理？

> 1. "将 1 个 SKU 的库存拆分到 N 个 Redis Key（桶）"
> 2. "扣减时按 userId % N 路由到固定桶，分散单 Key 压力"
> 3. "路由桶不足时遍历其他桶（桶间均衡），提升成功率"
> 4. "热点 SKU 8 桶，普通 SKU 2 桶，动态调整"

### Q3: 预扣了但没下单怎么办？

> 1. "预扣记录设置 30 分钟 TTL"
> 2. "定时任务每 5 分钟扫描过期预扣记录，执行 Lua 回退"
> 3. "支付成功后删除预扣记录（确认扣减）"

---

## 📚 参考资料

| 资料 | 相关章节 | 参考内容 |
|------|----------|----------|
| 📄 phase-2/README.md | §3.10 | 库存扣减完整设计（分桶/Lua/三级扣减） |
| 📄 03-distributed-solutions.md | §5 | 库存扣减 4 版本演进 |
