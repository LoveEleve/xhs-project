# my-xhs-cart 已知故障与陷阱

## 一、测试陷阱

### 1. 购物车上限50种
- **现象**: 第51种商品添加返回-1
- **根因**: `cart_add.lua` 检查 HLEN ≥ 50 返回 -1
- **应对**: 删除不需要的商品后再添加

### 2. updateQuantity防"复活"
- **现象**: 并发删除skuId后，updateQuantity返回 CART_ITEM_NOT_FOUND
- **根因**: `cart_update_quantity.lua` HEXISTS 校验 → 不存在抛异常
- **应对**: 正常行为——防MQ乱序导致的已删商品"复活"

### 3. 合并取max非覆盖
- **现象**: 合并后数量不是匿名+登录的和
- **根因**: `cart_merge_item.lua` 取 max(quantity)，幂等设计
- **应对**: 符合预期——避免重复合并导致数量翻倍

## 二、代码陷阱

### 4. C-05 时间戳乱序保护
- **现象**: MQ消息迟到，旧数量覆盖新数量
- **根因**: CartSyncConsumer 检查 eventTime，`oldEventTime.isBefore(newEventTime)` 时跳过
- **应对**: 机制已生效——测试时注意MQ消息顺序

### 5. Feign查询Product降级
- **现象**: 购物车列表商品显示"商品信息获取失败"
- **根因**: ProductFeignClient 调用失败，标记 valid=false
- **应对**: 检查 my-xhs-product 服务是否在线

## 三、依赖故障

### 6. Redis不可用
- **现象**: 所有购物车操作返回500
- **根因**: CartService 完全依赖 Redis，无数据库降级
- **验证**: `redis-cli -p 6379 -a "Xhs@2026#Redis" ping`
- **修复**: 重启Redis容器

### 7. MQ消息积压
- **现象**: 购物车数据不落MySQL
- **根因**: CART_TOPIC 消费者未处理或消息积压
- **验证**: RocketMQ Dashboard 检查 CART_TOPIC 积压量

## 四、对账陷阱

### 8. 定时对账可能跳过Redis-only用户
- **现象**: 某用户购物车只在Redis中，MySQL无记录
- **根因**: `CartReconcileJob.reconcile()` 按MySQL分页游标扫描，Redis-only用户不在扫描范围内
- **应对**: 使用 `POST /api/cart/internal/reconcile/user?userId=xxx` 手动对账修复

## 五、代码级缺陷（已修复+待修复）

### 9. 同毫秒并发ADD事件被C-05跳过（待修复）
- **现象**: 并发两次addToCart同一SKU，DB数量落后Redis
- **根因**: `!isBefore` 相等也跳过，`System.currentTimeMillis()` 可能同毫秒
- **修复**: 事件时间戳改为原子递增序列（`AtomicLong`）

### 10. CLEAR事件 createdAt 过滤误删（待修复）
- **现象**: 清空后立即重新加购同SKU → 被CLEAR误删 → MySQL永久缺失
- **根因**: `clearCartItems` 用 `createdAt <= eventTime`，UPSERT不重置createdAt
- **修复**: 改为 `updated_at <= eventTime`，或UPSERT同步重置createdAt

### 11. CHECK事件遇行不存在直接丢弃（待修复）
- **现象**: ADD→CHECK→MQ乱序 → CHECK先到行不存在被丢弃 → DB勾选态与Redis不一致
- **根因**: `updateCheckedStatus` 在 existing==null 时直接 return
- **修复**: 不存在时 UPSERT 携带 checked 状态

### 12. CartReconcileJob 无分布式锁（待修复）
- **现象**: 定时对账+手动端点可并发对账互相覆盖
- **根因**: reconcile 无锁，依赖XXL-Job单实例假设
- **修复**: reconcile 入口加 Redis SET NX EX

### 13. addToCart 已存在商品强制重勾选（待修复）
- **现象**: 取消勾选A→再加购A → A被重置为勾选（与 merge 语义不一致）
- **根因**: `cart_add.lua` 对已存在 SKU 无条件 SADD
- **修复**: 与 merge 对齐——exists==1 时不 SADD

### 14. 读路径 Redis 不可用全 500（待修复）
- **现象**: 购物车列表在 Redis 故障时完全不可用
- **根因**: `getCartList` 无降级到 MySQL
- **修复**: 捕获 Redis 异常 → 降级查 MySQL 组装

### 15. MQ 消费失败进 DLQ 无处理器
- **现象**: DLQ 消息永久丢失，仅靠每日对账修复
- **根因**: `maxReconsumeTimes=3` 进 DLQ，无消费者/告警
- **修复**: 增加 DLQ 消费者或监控告警
