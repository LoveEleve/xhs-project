# 购物车

> 所属服务：my-xhs-cart (9009) | 开发阶段：Phase-2 | 状态：⏳ 待开发

---

## 🎯 一、需求分析

### 1.1 业务场景

购物车是用户下单前的暂存区。采用 Redis 三结构协同方案：Hash 存商品数量、Set 存选中状态、ZSet 存排序。支持匿名购物车（未登录加购，登录后合并）。购物车数据以 Redis 为权威数据源，MQ 异步持久化到 MySQL 防止 Redis 故障丢数据。

### 1.2 功能边界

| 功能项 | 是否实现 | 说明 |
|--------|---------|------|
| 加入购物车 | ✅ | 同一 SKU 累加数量，HSETNX 防重复 |
| 修改数量 | ✅ | 单品上限 99 |
| 删除商品 | ✅ | HDEL 移除 |
| 勾选/全选 | ✅ | Redis Set 管理选中状态 |
| 购物车列表 | ✅ | 含商品详情 + 失效标记 |
| 匿名购物车 | ✅ | 设备 ID 作为 Key，登录后合并 |
| 商品失效标记 | ✅ | Feign 调 Product 查状态，下架/无库存标记失效 |
| 异步持久化 | ✅ | MQ 写入 MySQL（防 Redis 故障） |
| 购物车数量上限 | ✅ | 总数上限 50 品 |

### 1.3 数据量预估

| 指标 | 预估值 | 计算依据 |
|------|--------|----------|
| 购物车用户数 | 500 万 | 50% 用户有购物车 |
| 平均商品数 | 8 件/人 | 中型电商平均值 |
| 购物车操作 QPS | 3000 | 加购/改数量/勾选 |
| 购物车查询 QPS | 5000 | 每次打开购物车页 |

---

## 🏗️ 二、架构设计

### 2.1 整体架构图

```
Client → Gateway → my-xhs-cart(9009) → Redis(购物车三结构)
                        │
                        ├── Feign → my-xhs-product(查商品详情/状态)
                        └── RocketMQ → MySQL(t_cart_item) 异步持久化
```

### 2.2 Redis 三结构协同

```
cart:items:{userId}    → Hash   field=skuId, value=quantity  （商品+数量）
cart:checked:{userId}  → Set    member=skuId                 （选中状态）
cart:sort:{userId}     → ZSet   member=skuId, score=timestamp（加购时间排序）
```

### 2.3 核心流程

**加入购物车：**
```
1. 检查购物车总数是否超限（HLEN ≤ 50）
2. HINCRBY cart:items:{userId} {skuId} {quantity}（已存在则累加）
3. SADD cart:checked:{userId} {skuId}（默认选中）
4. ZADD cart:sort:{userId} {timestamp} {skuId}（记录加购时间）
5. MQ 异步持久化到 MySQL
```

**匿名购物车合并（登录时）：**
```
1. HGETALL cart:items:anonymous:{deviceId}（获取匿名购物车）
2. 遍历合并到 cart:items:{userId}（已存在则取较大数量）
3. DEL cart:items:anonymous:{deviceId}（清除匿名购物车）
```

---

## 🗄️ 三、数据库设计

### 3.1 表结构

```sql
-- 购物车项表（异步持久化，Redis 为权威数据源）
CREATE TABLE IF NOT EXISTS t_cart_item (
    id           BIGINT       NOT NULL COMMENT 'ID',
    user_id      BIGINT       NOT NULL COMMENT '用户ID',
    sku_id       BIGINT       NOT NULL COMMENT 'SKU ID',
    quantity     INT          NOT NULL DEFAULT 1 COMMENT '数量',
    checked      TINYINT      NOT NULL DEFAULT 1 COMMENT '是否选中：0-否 1-是',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_user_sku (user_id, sku_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='购物车项表';
```

---

## 🔑 四、缓存设计

### 4.1 Redis Key 规范

| Key Pattern | 类型 | 过期时间 | 说明 |
|-------------|------|----------|------|
| `cart:items:{userId}` | Hash | 永久 | field=skuId, value=quantity |
| `cart:checked:{userId}` | Set | 永久 | 选中的 skuId 集合 |
| `cart:sort:{userId}` | ZSet | 永久 | score=加购时间戳 |
| `cart:items:anonymous:{deviceId}` | Hash | 7d | 匿名购物车（7 天过期） |

### 4.2 为什么用三种结构而不是一个 Hash？

| 需求 | 单 Hash 方案 | 三结构方案（✅ 选定） |
|------|-------------|-------------------|
| 商品数量 | ✅ field=skuId, value=quantity | ✅ Hash |
| 选中状态 | ❌ 需要额外字段 | ✅ Set（SISMEMBER O(1)） |
| 按时间排序 | ❌ Hash 无序 | ✅ ZSet（天然排序） |
| 全选/取消全选 | ❌ 需遍历 | ✅ SADD/DEL 整个 Set |

---

## 📡 五、接口设计

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/api/cart/add` | 加入购物车 | ✅ |
| PUT | `/api/cart/quantity` | 修改数量 | ✅ |
| DELETE | `/api/cart/{skuId}` | 删除商品 | ✅ |
| PUT | `/api/cart/check` | 勾选/取消勾选 | ✅ |
| PUT | `/api/cart/check-all` | 全选/取消全选 | ✅ |
| GET | `/api/cart/list` | 购物车列表 | ✅ |
| POST | `/api/cart/merge` | 匿名购物车合并（登录时） | ✅ |

---

## 💻 六、核心代码实现

### 6.1 加入购物车

```java
@Override
public void addToCart(Long userId, Long skuId, int quantity) {
    String itemsKey = "cart:items:" + userId;

    // 1. 检查购物车总数上限
    Long size = redisTemplate.opsForHash().size(itemsKey);
    if (size != null && size >= 50) {
        throw new BizException(BizErrorCode.CART_ITEM_LIMIT_EXCEEDED);
    }

    // 2. 累加数量（已存在则 +quantity，不存在则新增）
    redisTemplate.opsForHash().increment(itemsKey, String.valueOf(skuId), quantity);

    // 3. 校验单品上限
    Object currentQty = redisTemplate.opsForHash().get(itemsKey, String.valueOf(skuId));
    if (Integer.parseInt(currentQty.toString()) > 99) {
        redisTemplate.opsForHash().put(itemsKey, String.valueOf(skuId), "99");
    }

    // 4. 默认选中
    redisTemplate.opsForSet().add("cart:checked:" + userId, String.valueOf(skuId));

    // 5. 记录加购时间（排序用）
    redisTemplate.opsForZSet().add("cart:sort:" + userId,
            String.valueOf(skuId), System.currentTimeMillis());

    // 6. MQ 异步持久化
    rocketMQTemplate.asyncSend("CART_TOPIC:SYNC",
            MessageBuilder.withPayload(new CartSyncEvent(userId, skuId, quantity, "ADD")).build(), null);
}
```

---

## ⚖️ 七、方案对比

### 7.1 购物车存储：Redis vs MySQL vs 混合

| 维度 | 纯 Redis（✅ 选定） | 纯 MySQL | 混合 |
|------|-------------------|---------|------|
| 性能 | 极高（毫秒级） | 低（磁盘 IO） | 高 |
| 持久性 | 中（Redis 持久化 + MQ 异步落库） | 强 | 强 |
| 复杂度 | 低 | 低 | 高 |

**选择理由**：购物车是高频操作（加购/改数量/勾选），Redis 性能远超 MySQL。MQ 异步落库保证 Redis 故障时数据不丢。

---

## 🐛 八、踩坑记录

### 8.1 匿名购物车合并冲突

- **现象**：登录后匿名购物车和已有购物车同一 SKU 数量叠加超过 99
- **解决**：合并时取 `Math.min(匿名数量 + 已有数量, 99)`

### 8.2 商品下架后购物车仍可下单

- **现象**：商品下架后用户仍能从购物车提交订单
- **解决**：购物车列表查询时 Feign 调 Product 服务校验状态，标记失效商品

---

## 📊 九、测试验证

| 测试场景 | 输入 | 预期结果 | 通过 |
|----------|------|----------|------|
| 加入购物车 | skuId + quantity | Hash 新增/累加 | ⬜ |
| 超过 50 品 | 第 51 个 SKU | 返回"购物车已满" | ⬜ |
| 单品超 99 | quantity=100 | 自动截断为 99 | ⬜ |
| 匿名购物车合并 | 登录触发 | 合并成功，匿名车清空 | ⬜ |
| 商品失效标记 | 下架商品 | 列表中标记"已失效" | ⬜ |

---

## 🎤 十、面试考察点

### Q1: 购物车为什么用 Redis 不用 MySQL？

> 1. "购物车是高频操作（加购/改数量/勾选），QPS 数千级"
> 2. "Redis Hash/Set/ZSet 天然支持购物车的三种需求（数量/选中/排序）"
> 3. "MQ 异步落库 MySQL 兜底，Redis 故障时从 MySQL 恢复"

### Q2: 未登录加购怎么处理？

> 1. "用设备 ID 作为 Key 存匿名购物车：`cart:items:anonymous:{deviceId}`"
> 2. "登录时触发合并：HGETALL 匿名车 → 合并到用户车 → DEL 匿名车"
> 3. "匿名购物车 7 天过期，避免无限堆积"

---

## 📚 参考资料

| 资料 | 相关章节 | 参考内容 |
|------|----------|----------|
| 📄 phase-2/README.md | §3.9 | 购物车完整设计（三结构/匿名/合并） |
