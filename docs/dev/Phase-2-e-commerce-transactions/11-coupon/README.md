# 优惠券

> 所属服务：my-xhs-coupon (9010) | 开发阶段：Phase-2 | 状态：⏳ 待开发

---

## 🎯 一、需求分析

### 1.1 业务场景

优惠券服务包含券模板管理和用户领券/用券。券模板支持满减、折扣、无门槛三种类型。领券使用 Lua 原子操作（扣库存 + 记录领取 + 限领校验一步到位），防止超领。责任链模式校验用券条件（门槛/品类/有效期）。用户券表使用 ShardingSphere 按 buyer_id 分 4 库，支撑 10 亿级数据。

### 1.2 功能边界

| 功能项 | 是否实现 | 说明 |
|--------|---------|------|
| 券模板 CRUD | ✅ | 创建/修改/上线/下线 |
| Lua 原子领券 | ✅ | 扣库存 + 记录领取 + 限领校验，一步到位 |
| 责任链校验 | ✅ | 门槛 → 品类 → 有效期，链式校验 |
| 用券（下单时） | ✅ | Order 服务 Feign 调用 |
| 退券（取消订单） | ✅ | 恢复券状态 |
| 券过期自动失效 | ✅ | XXL-Job 定时扫描 |
| 批量发券 | ✅ | MQ 分片发送 |
| 分库分表 | ✅ | 用户券表按 buyer_id % 4 分库（Phase-5 实施） |

### 1.3 数据量预估

| 指标 | 预估值 | 计算依据 |
|------|--------|----------|
| 券模板总量 | 1 万 | 运营创建 |
| 用户券总量 | 10 亿 | 1000 万用户 × 平均 100 张券 |
| 领券 QPS | 5000 | 大促抢券高峰 |
| 用券 QPS | 2000 | 下单时校验 |

---

## 🏗️ 二、架构设计

### 2.1 整体架构图

```
Client → Gateway → my-xhs-coupon(9010)
                        │
                        ├── Redis: 券库存(Lua原子扣减) + 领取记录(防重复)
                        ├── MySQL: 券模板 + 用户券
                        └── RocketMQ: 批量发券任务

Order 服务 → Feign → my-xhs-coupon: 用券/退券
```

### 2.2 Lua 原子领券流程

```
claim_coupon.lua 核心逻辑：

1. 输入：templateId, userId, perUserLimit
2. 检查券库存：GET coupon:stock:{templateId}
   └── 库存 ≤ 0 → 返回"券已领完"
3. 检查用户是否已领：GET coupon:claimed:{templateId}:{userId}
   └── 已领取 → 返回"已领取过"
4. 扣减库存：DECR coupon:stock:{templateId}
5. 记录领取：SET coupon:claimed:{templateId}:{userId} 1
6. 返回成功
```

---

## 🗄️ 三、数据库设计

### 3.1 表结构

```sql
-- 优惠券模板表（实际 DDL 来自 init-databases.sql）
CREATE TABLE IF NOT EXISTS t_coupon_template (
    id              BIGINT       NOT NULL COMMENT 'ID',
    name            VARCHAR(128) NOT NULL COMMENT '优惠券名称',
    type            TINYINT      NOT NULL COMMENT '类型：1-满减 2-折扣 3-无门槛',
    discount_value  DECIMAL(10,2) NOT NULL COMMENT '优惠金额/折扣率',
    min_amount      DECIMAL(10,2) DEFAULT 0 COMMENT '最低消费金额',
    total_count     INT          NOT NULL COMMENT '发放总量',
    remain_count    INT          NOT NULL COMMENT '剩余数量',
    per_user_limit  INT          NOT NULL DEFAULT 1 COMMENT '每人限领',
    valid_start     DATETIME     NOT NULL COMMENT '有效期开始',
    valid_end       DATETIME     NOT NULL COMMENT '有效期结束',
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0-禁用 1-启用',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='优惠券模板表';

-- 用户优惠券表
CREATE TABLE IF NOT EXISTS t_user_coupon (
    id              BIGINT       NOT NULL COMMENT 'ID',
    user_id         BIGINT       NOT NULL COMMENT '用户ID',
    coupon_id       BIGINT       NOT NULL COMMENT '优惠券模板ID',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0-未使用 1-已使用 2-已过期',
    used_order_id   BIGINT       DEFAULT NULL COMMENT '使用的订单ID',
    received_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '领取时间',
    used_at         DATETIME     DEFAULT NULL COMMENT '使用时间',
    PRIMARY KEY (id),
    INDEX idx_user_id (user_id),
    INDEX idx_coupon_id (coupon_id),
    UNIQUE INDEX uk_user_coupon (user_id, coupon_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户优惠券表';
```

---

## 🔑 四、缓存设计

### 4.1 Redis Key 规范

| Key Pattern | 类型 | 过期时间 | 说明 |
|-------------|------|----------|------|
| `coupon:template:{templateId}` | Hash | 30min | 券模板缓存 |
| `coupon:stock:{templateId}` | String | 永久 | 券库存（Lua 原子扣减） |
| `coupon:claimed:{templateId}:{userId}` | String | 永久 | 用户领取记录（防重复） |
| `coupon:user:available:{userId}` | List(JSON) | 5min | 用户可用券缓存 |

---

## 📡 五、接口设计

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/api/coupon/template` | 创建券模板 | ✅（管理端） |
| PUT | `/api/coupon/template/{id}/status` | 上线/下线 | ✅（管理端） |
| GET | `/api/coupon/template/{id}` | 券模板详情 | ❌ |
| POST | `/api/coupon/claim` | 领取优惠券 | ✅ |
| GET | `/api/coupon/user/list` | 我的优惠券列表 | ✅ |
| GET | `/api/coupon/user/available` | 可用优惠券（下单时） | ✅ |
| POST | `/api/coupon/use` | 使用优惠券 | ✅（Order Feign） |
| POST | `/api/coupon/return` | 退回优惠券 | ✅（Order Feign） |

---

## 💻 六、核心代码实现

### 6.1 Lua 原子领券

```lua
-- claim_coupon.lua：原子领券（扣库存+防重复+限领校验）
local templateId = KEYS[1]
local userId = ARGV[1]

-- 1. 检查券库存
local stock = tonumber(redis.call('GET', 'coupon:stock:' .. templateId) or '0')
if stock <= 0 then
    return -1 -- 券已领完
end

-- 2. 检查是否已领取
local claimed = redis.call('GET', 'coupon:claimed:' .. templateId .. ':' .. userId)
if claimed then
    return -2 -- 已领取过
end

-- 3. 扣减库存
redis.call('DECR', 'coupon:stock:' .. templateId)

-- 4. 记录领取
redis.call('SET', 'coupon:claimed:' .. templateId .. ':' .. userId, '1')

return 1 -- 领取成功
```

### 6.2 责任链校验（用券时）

```java
/**
 * 责任链模式校验用券条件
 * 链路：门槛校验 → 品类校验 → 有效期校验
 */
public interface CouponValidator {
    void validate(CouponTemplate template, UserCoupon coupon, OrderContext order);
}

@Component @Order(1)
public class AmountValidator implements CouponValidator {
    public void validate(...) {
        if (order.getTotalAmount().compareTo(template.getMinAmount()) < 0) {
            throw new BizException(BizErrorCode.COUPON_AMOUNT_NOT_REACHED);
        }
    }
}

@Component @Order(2)
public class ExpireValidator implements CouponValidator {
    public void validate(...) {
        if (LocalDateTime.now().isAfter(template.getValidEnd())) {
            throw new BizException(BizErrorCode.COUPON_EXPIRED);
        }
    }
}

// 使用：遍历所有 CouponValidator 执行校验
@Service
public class CouponUseService {
    @Autowired
    private List<CouponValidator> validators; // Spring 自动注入所有实现

    public void useCoupon(Long userId, Long couponId, OrderContext order) {
        // 责任链逐个校验
        for (CouponValidator validator : validators) {
            validator.validate(template, coupon, order);
        }
        // 全部通过 → 标记已使用
        userCouponMapper.markUsed(couponId, order.getOrderId());
    }
}
```

---

## ⚖️ 七、方案对比

### 7.1 领券防超发：Lua 原子操作 vs 分布式锁 vs DB 乐观锁

| 维度 | Lua 原子操作（✅ 选定） | 分布式锁 | DB 乐观锁 |
|------|----------------------|---------|-----------|
| 性能 | 极高（Redis 单线程） | 中（加锁开销） | 低（DB 行锁） |
| 原子性 | ✅ 天然原子 | ✅ 锁保证 | ✅ CAS |
| 复杂度 | 中（需写 Lua） | 中 | 低 |
| QPS | 10 万+ | 1 万 | 千级 |

**选择理由**：大促抢券 QPS 万级，Lua 脚本在 Redis 单线程中执行，天然原子且性能最高。

---

## 🐛 八、踩坑记录

### 8.1 Lua 脚本扣库存成功但写 DB 失败

- **现象**：Redis 库存扣了，但 MySQL 用户券表写入失败
- **解决**：Lua 扣库存后发 MQ 异步写 DB；MQ 消费失败重试 16 次 → 死信队列 → 告警 + 手动补偿

### 8.2 券过期但用户仍能使用

- **现象**：券已过期但下单时仍能使用
- **解决**：用券时责任链中 ExpireValidator 实时校验有效期，不依赖 status 字段

---

## 📊 九、测试验证

| 测试场景 | 输入 | 预期结果 | 通过 |
|----------|------|----------|------|
| 正常领券 | 有效模板 + 有库存 | 领取成功 | ⬜ |
| 券已领完 | 库存=0 | 返回"券已领完" | ⬜ |
| 重复领取 | 同一用户同一券 | 返回"已领取过" | ⬜ |
| 并发领券 | 100 并发抢最后 1 张 | 只有 1 人成功 | ⬜ |
| 用券（满足条件） | 订单金额 ≥ 门槛 | 用券成功 | ⬜ |
| 用券（不满足门槛） | 订单金额 < 门槛 | 返回"未达使用门槛" | ⬜ |
| 退券 | 取消订单 | 券状态恢复为未使用 | ⬜ |

---

## 🎤 十、面试考察点

### Q1: 高并发领券怎么防止超发？

> 1. "Redis Lua 脚本原子操作：检查库存 → 检查是否已领 → 扣库存 → 记录领取，一步到位"
> 2. "Lua 在 Redis 单线程中执行，天然串行，不会出现并发超领"
> 3. "vs 分布式锁：锁粒度难控制，性能差；vs DB 乐观锁：QPS 太低"

### Q2: 优惠券过期怎么处理？

> 1. "XXL-Job 定时任务每小时扫描 `valid_end < NOW() AND status=0` 的券，批量更新为已过期"
> 2. "用券时实时校验有效期（责任链 ExpireValidator），不依赖 status 字段"
> 3. "双重保障：定时任务批量更新 + 实时校验兜底"

---

## 📚 参考资料

| 资料 | 相关章节 | 参考内容 |
|------|----------|----------|
| 📄 phase-2/README.md | §3.11 | 优惠券完整设计（Lua/责任链/分库分表） |
| 📄 02-module-detailed-design.md | §9 | 优惠券服务/领券/用券/退券 |
