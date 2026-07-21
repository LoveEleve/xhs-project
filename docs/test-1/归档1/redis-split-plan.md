# my-xhs Redis 拆分方案

> 日期：2026-06-02 | 状态：方案阶段，待实施

## 背景

当前所有模块共用单一 Redis 实例（21.91.124.110:16379），包括：
- 购物车数据（高频读写）
- 关注/点赞/收藏（社交数据，需持久）
- 优惠券库存（Lua 原子操作）
- 分布式锁（Redisson）
- Token 黑名单
- ID 序列号
- Counter buffer
- 缓存数据（cache/hotsearch/suggest/ratelimit）

## 拆分方案

### 双实例架构

| 实例 | 端口 | 内存 | 淘汰策略 | 用途 |
|------|------|------|------|------|
| **Cache Redis** | 16380 | 128MB | `allkeys-lru` | 纯缓存：热搜、搜索建议、商品分类树、限流计数器 |
| **Business Redis** | 16381 | 256MB | `noeviction` | 业务数据：购物车、关注/点赞/收藏、优惠券库存、分布式锁、Token 黑名单、ID 序列号 |

### Key 映射表

#### → Business Redis (16381)

| 业务 | Key 前缀 | 数据类型 | 原因 |
|------|------|------|------|
| 购物车 | `myxhs:cart:` | Hash | 不可丢失 |
| 关注关系 | `myxhs:follow:` | ZSet/Hash | 不可丢失 |
| 点赞数据 | `myxhs:like:` | Set | 不可丢失 |
| 收藏数据 | `myxhs:favorite:` | Set | 不可丢失 |
| 优惠券库存 | `myxhs:coupon:stock:` | String(Lua) | 原子操作必需 |
| 分布式锁 | `lock:*` | String(Redisson) | 不可丢失 |
| Token 黑名单 | `myxhs:user:blacklist:`, `USER_TOKEN_BLACKLIST:` | String/Set | 安全关键 |
| ID 序列号 | `order:seq:`, `myxhs:id:` | String | 不可丢失 |
| Counter buffer | `myxhs:counter:buffer:` | Hash | 不可丢失 |

#### → Cache Redis (16380)

| 场景 | Key 前缀 | 数据类型 |
|------|------|------|
| 热搜 | `myxhs:search:hot:`, `search:hot:` | ZSet |
| 搜索建议 | `myxhs:search:suggest:` | String |
| 商品分类树 | `product:category:tree` | String |
| 限流计数器 | `ratelimit:*` | ZSet(滑动窗口) |
| Feed 缓存 | `myxhs:feed:*` | ZSet |
| 推荐缓存 | `recommend:*` | ZSet/Hash |
| 通用缓存 | `myxhs:cache:*`, `note:`, `spu:` | String |

### 灰度迁移步骤（6天）

| 天 | 模块 | 操作 |
|:---:|------|------|
| 1 | 部署双实例 | docker-compose 追加 redis-cache(16380) + redis-business(16381) |
| 2 | cart | `@Qualifier("businessStringRedisTemplate")` |
| 3 | inventory + coupon | 切换 business data source |
| 4 | analytics + home + counter | 逐模块切换 |
| 5 | user + product + content | 逐模块切换 |
| 6 | order + payment | 最后一批 |

### 验证脚本

每步切换后运行：
```bash
# 确认 Key 分布
redis-cli -p 16380 -a 'Xhs@2026#Redis' DBSIZE
redis-cli -p 16381 -a 'Xhs@2026#Redis' DBSIZE

# 业务可用性
curl -s http://localhost:19008/api/cart/list -H 'X-User-Id: 1' | jq '.code'
```
