# my-xhs-coupon 已知故障与陷阱

## 一、测试陷阱

### validStart 缓存30min不刷新
- **现象**: 修改券模板validStart后，前端仍显示旧开始时间
- **根因**: 模板缓存(CacheAside 30min TTL)不主动失效——putTemplateStatus只清状态，validStart变化后旧模板仍在缓存中
- **应对**: 测试时手动执行 `redis-cli DEL myxhs:coupon:template:{id}`
- **关联**: N02 修复后需 `evictTemplateCache`

### 领券库存不足返回-1
- **现象**: 库存5张，领第6张时返回失败
- **根因**: `claim_coupon.lua` 检查 stock <= 0 → 返回 -1
- **应对**: 正常行为——检查模板剩余库存

## 二、代码陷阱

### Outbox MQ超时不回滚
- **现象**: claimCoupon() Lua成功后MQ发送超时——Outbox status=PENDING但MQ未发送
- **根因**: 设计如此——Outbox记录已写入MySQL，不因MQ失败回滚Redis库存
- **应对**: CouponOutboxSenderJob 定时扫描 PENDING → 重发MQ → 标记SENT

### claimNo 唯一索引兜底
- **现象**: MQ消息重复投递导致用户重复领券
- **根因**: uk_claim_no 唯一索引 + ON DUPLICATE KEY UPDATE 幂等
- **验证**: 验证 t_user_coupon 中同 claimNo 只有一条记录

## 三、依赖故障

### INTERNAL_TOKEN 空导致403
- **现象**: 启动后用券/退券接口返回403
- **根因**: `myxhs.internal.token` 未配置或为空——isInternalCall 返回 false
- **验证**: `grep "myxhs.internal.token" /path/to/application.yml`
- **修复**: 配置与 my-xhs-order 匹配的 internalToken

### batchExpire 兼容性问题
- **现象**: 过期券未自动标记 EXPIRED
- **根因**: `UPDATE ... FOR UPDATE LIMIT 1000` 语法在 MySQL 8.0.21+ 支持，8.0.20 之前需子查询
- **验证**: `SELECT VERSION()` 检查MySQL版本
- **影响**: 过期券不会被自动标记，仍出现在可用列表中

## 四、并发陷阱

### 乐观锁markUsed冲突
- **现象**: 同一张券被多次提交用券
- **根因**: `UPDATE SET status=USED WHERE status=AVAILABLE` — 第二次执行 affected=0
- **应对**: 正常行为——抛出 CouponAlreadyUsedException

## 五、代码级缺陷（已修复+待修复）

### 6. 折扣券计算语义错误（已修复）
- **现象**: 8.5 折券实际折扣=1.5 折（资金损失）
- **根因**: case 2 返回折后价(85)而非减免金额(15)，order 侧把折后价当减免金额
- **修复**: 改为 `orderAmount - orderAmount * discountValue / 10`（CouponService.java:317）

### 7. 满减可超订单金额 0 元购（已修复）
- **现象**: 满减 50 元券用于 30 元订单 → 0 元购
- **根因**: calculateDiscount 无 min() 保护
- **修复**: 所有券类型统一 `discount = discount.min(orderAmount)`（CouponService.java:323）

### 8. Outbox 补发与 Redis 回滚冲突超发（已修复）
- **现象**: MQ 失败→回滚 Redis→Outbox Job 补发→双花
- **根因**: syncSend 失败时 Outbox 记录未删除
- **修复**: MQ 失败/异常时 deleteByClaimNo 删除 Outbox 记录（CouponService.java:517,524）

### 9. INTERNAL_TOKEN 默认值不一致（已修复）
- **现象**: 8 个服务空默认 → 内部端点全 403
- **根因**: coupon/cart/inventory 等默认 `${INTERNAL_TOKEN:}`，order 默认 `my-xhs-internal-token-2026`
- **修复**: 全部 10 个服务统一默认值

### 10. 退券 Redis 在 @Transactional 提交前执行（已修复）
- **现象**: 事务回滚时 Redis 已+1 → 不一致
- **修复**: 移至 TransactionSynchronizationManager.afterCommit()

### 11. Consumer insert+decrement 非原子（已修复）
- **现象**: duplicateKey 短路导致 remain_count 永久漏扣
- **修复**: onMessage 加 @Transactional，insert+decrement 同事务
