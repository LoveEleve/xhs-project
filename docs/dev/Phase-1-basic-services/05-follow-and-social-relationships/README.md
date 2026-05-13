# 关注与社交关系

> 所属服务：my-xhs-analytics (9003) | 开发阶段：Phase-1 | 状态：⏳ 待开发

---

## 🎯 一、需求分析

### 1.1 业务场景

用户关注/取关其他用户，查看关注列表/粉丝列表/共同关注。Lua 脚本保证关注操作 + 计数更新的原子性。关注关系存储在 Redis ZSet 中（实时查询），MQ 异步落库到 MySQL（持久化）。

### 1.2 功能边界

| 功能项 | 是否实现 | 说明 |
|--------|---------|------|
| 关注/取关 | ✅ | Lua 脚本原子操作（关注+计数） |
| 关注列表 | ✅ | Redis ZSet（score=关注时间），天然排序+分页 |
| 粉丝列表 | ✅ | Redis ZSet，同上 |
| 共同关注 | ✅ | ZRANGE 取出两个关注列表 + 内存求交集（ZSet 不支持 SINTER） |
| 查询关注关系 | ✅ | SISMEMBER O(1) 判断 |
| 异步落库 | ✅ | MQ 异步写 MySQL，Redis 实时返回 |
| 防重复关注 | ✅ | ZSet 天然去重 + DB 唯一索引 |
| 大 V 粉丝优化 | ❌ | 百万粉丝 ZSet 内存过大，需分片，后续优化 |

### 1.3 数据量预估

| 指标 | 预估值 | 计算依据 |
|------|--------|----------|
| 关注关系总量 | 10 亿 | 1000 万用户 × 平均关注 100 人 |
| 日增量 | 100 万/天 | 100 万日活 × 1 次关注/天 |
| 关注/取关 QPS | 1000 | 高峰期集中操作 |
| 关注列表查询 QPS | 3000 | 用户主页必加载 |

---

## 🏗️ 二、架构设计

### 2.1 整体架构图

```
Client → Gateway(鉴权) → my-xhs-analytics(9003) → Redis(关注关系/计数)
                              │                         │
                              ├── Lua 脚本: 关注+计数原子操作
                              └── RocketMQ → MySQL(t_follow) 异步落库
                                           → Counter 服务更新粉丝数/关注数
```

### 2.2 模块交互

| 调用方 | 被调用方 | 方式 | 场景 |
|--------|---------|------|------|
| my-xhs-analytics | Redis | Lua 脚本 | 关注/取关原子操作 |
| my-xhs-analytics | RocketMQ | 异步消息 | 关注事件 → 落库 + 计数 |
| my-xhs-analytics | MySQL | MyBatis-Plus | 关注关系持久化 |
| Counter Consumer | Redis | INCR/DECR | 更新粉丝数/关注数 |

### 2.3 核心流程时序图

**关注用户流程：**

```
1. Client → Gateway: POST /api/social/follow/{targetUserId}
2. Gateway → FollowService: 转发请求
3. FollowService → Redis: 执行 Lua 脚本（原子操作）
   a. ZADD social:following:{userId} {timestamp} {targetUserId}  -- 添加关注
   b. ZADD social:follower:{targetUserId} {timestamp} {userId}   -- 添加粉丝
   c. INCR counter:user_following:{userId}                        -- 关注数+1
   d. INCR counter:user_follower:{targetUserId}                   -- 粉丝数+1
4. FollowService → RocketMQ: 发送 SOCIAL_TOPIC:FOLLOW 消息
5. Consumer → MySQL: INSERT t_follow（异步落库）
6. FollowService → Client: 返回关注成功
```

**共同关注查询：**

```
1. Client → FollowService: GET /api/social/common/{targetUserId}
2. FollowService → Redis: ZRANGE social:following:{userId} 0 -1     -- 我的关注列表
3. FollowService → Redis: ZRANGE social:following:{targetUserId} 0 -1 -- 对方关注列表
4. FollowService: 内存求交集（或 Redis SINTER）
5. FollowService → Client: 返回共同关注用户列表
```

---

## 🗄️ 三、数据库设计

### 3.1 表结构

```sql
-- 关注关系表（MQ 异步落库，Redis ZSet 为权威数据源）
-- 实际 DDL 来自 init-databases.sql（my_xhs_analytics 库）
CREATE TABLE IF NOT EXISTS t_follow (
    id              BIGINT   NOT NULL COMMENT 'ID',
    user_id         BIGINT   NOT NULL COMMENT '用户ID',
    follow_user_id  BIGINT   NOT NULL COMMENT '被关注用户ID',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '关注时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_user_follow (user_id, follow_user_id),
    INDEX idx_follow_user_id (follow_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='关注关系表';
```

> **说明**：关注关系的权威数据存储在 Redis ZSet 中（永久不过期），MySQL 的 t_follow 作为异步落库的持久化兜底。t_user_behavior 表仅用于行为分析日志（浏览/搜索等），不存储关注关系。

### 3.2 索引设计

| 索引名 | 字段 | 使用场景 |
|--------|------|----------|
| `uk_user_follow` | (user_id, follow_user_id) | 唯一索引，防重复关注 + 查询关注关系 |
| `idx_follow_user_id` | follow_user_id | 查某用户的粉丝列表 |

---

## 🔑 四、缓存设计

### 4.1 Redis Key 规范

| Key Pattern | 类型 | 过期时间 | 说明 |
|-------------|------|----------|------|
| `social:following:{userId}` | ZSet | 永久 | 关注列表（score=关注时间戳） |
| `social:follower:{userId}` | ZSet | 永久 | 粉丝列表（score=关注时间戳） |
| `counter:user_following:{userId}` | String | 永久 | 关注数 |
| `counter:user_follower:{userId}` | String | 永久 | 粉丝数 |

### 4.2 为什么关注关系用 Redis 而不是 MySQL？

| 维度 | Redis ZSet（✅ 选定） | MySQL |
|------|---------------------|-------|
| 查询性能 | O(logN) 分页，毫秒级 | 需要 ORDER BY + LIMIT |
| 共同关注 | SINTER 集合运算，毫秒级 | 需要子查询或 JOIN |
| 关注判断 | ZSCORE O(1) | 需要 SELECT COUNT |
| 持久性 | Redis 持久化 + MQ 异步落库 MySQL | 强持久 |

**选择理由**：关注关系是高频读写场景，Redis ZSet 天然支持排序+分页+去重+集合运算，性能远超 MySQL。MySQL 作为异步落库的持久化兜底。

---

## 📡 五、接口设计

### 5.1 接口列表

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/api/social/follow/{userId}` | 关注用户 | ✅ |
| DELETE | `/api/social/follow/{userId}` | 取关用户 | ✅ |
| GET | `/api/social/following/{userId}` | 关注列表 | ❌（公开） |
| GET | `/api/social/follower/{userId}` | 粉丝列表 | ❌（公开） |
| GET | `/api/social/common/{userId}` | 共同关注 | ✅ |
| GET | `/api/social/relation/{userId}` | 查询是否关注 | ✅ |

### 5.2 请求/响应示例

**关注用户**

```http
POST /api/social/follow/10087
Authorization: Bearer {accessToken}
```

```json
{
  "code": 200,
  "msg": "关注成功"
}
```

**关注列表**

```http
GET /api/social/following/10086?page=1&size=20
```

```json
{
  "code": 200,
  "data": {
    "total": 128,
    "list": [
      {
        "userId": 10087,
        "nickname": "李四",
        "avatar": "https://cdn.myxhs.com/avatar/10087.jpg",
        "followedAt": "2026-05-12 10:30:00",
        "isFollowBack": true
      }
    ]
  }
}
```

**共同关注**

```http
GET /api/social/common/10087
Authorization: Bearer {accessToken}
```

```json
{
  "code": 200,
  "data": [
    {
      "userId": 10088,
      "nickname": "王五",
      "avatar": "https://cdn.myxhs.com/avatar/10088.jpg"
    }
  ]
}
```

---

## 💻 六、核心代码实现

### 6.1 Lua 脚本：关注 + 计数原子操作

```lua
-- follow_and_count.lua
-- 关注用户 + 更新计数（原子操作，不会出现关注成功但计数未更新的情况）
local userId = KEYS[1]           -- 当前用户
local targetUserId = KEYS[2]     -- 目标用户
local currentTime = ARGV[1]      -- 关注时间戳

-- 1. 检查是否已关注（防重复）
local exists = redis.call('ZSCORE', 'social:following:' .. userId, targetUserId)
if exists then
    return 0  -- 已关注，返回 0
end

-- 2. 添加关注关系
redis.call('ZADD', 'social:following:' .. userId, currentTime, targetUserId)
-- 3. 添加粉丝关系
redis.call('ZADD', 'social:follower:' .. targetUserId, currentTime, userId)
-- 4. 更新关注数
redis.call('INCR', 'counter:user_following:' .. userId)
-- 5. 更新粉丝数
redis.call('INCR', 'counter:user_follower:' .. targetUserId)

return 1  -- 关注成功
```

```lua
-- unfollow_and_count.lua
-- 取关用户 + 更新计数（原子操作）
local userId = KEYS[1]
local targetUserId = KEYS[2]

-- 1. 检查是否已关注
local exists = redis.call('ZSCORE', 'social:following:' .. userId, targetUserId)
if not exists then
    return 0  -- 未关注，返回 0
end

-- 2. 移除关注关系
redis.call('ZREM', 'social:following:' .. userId, targetUserId)
-- 3. 移除粉丝关系
redis.call('ZREM', 'social:follower:' .. targetUserId, userId)
-- 4. 更新关注数
redis.call('DECR', 'counter:user_following:' .. userId)
-- 5. 更新粉丝数
redis.call('DECR', 'counter:user_follower:' .. targetUserId)

return 1  -- 取关成功
```

### 6.2 关注服务实现

```java
/**
 * 关注服务
 * 关键点：Lua 脚本原子操作 → MQ 异步落库 → 防重复关注
 */
@Service
public class FollowServiceImpl implements FollowService {

    @Resource
    private RedisScript<Long> followScript;    // follow_and_count.lua
    @Resource
    private RedisScript<Long> unfollowScript;  // unfollow_and_count.lua

    @Override
    public void follow(Long userId, Long targetUserId) {
        // 1. 不能关注自己
        if (userId.equals(targetUserId)) {
            throw new BizException(BizErrorCode.CANNOT_FOLLOW_SELF);
        }

        // 2. 执行 Lua 脚本（原子操作）
        Long result = redisTemplate.execute(followScript,
                List.of(String.valueOf(userId), String.valueOf(targetUserId)),
                String.valueOf(System.currentTimeMillis()));

        if (result == null || result == 0) {
            throw new BizException(BizErrorCode.ALREADY_FOLLOWED, "已关注该用户");
        }

        // 3. MQ 异步落库
        rocketMQTemplate.asyncSend("SOCIAL_TOPIC:FOLLOW",
                MessageBuilder.withPayload(new FollowEvent(userId, targetUserId, "FOLLOW"))
                        .build(),
                new SendCallback() {
                    @Override
                    public void onSuccess(SendResult sendResult) {}
                    @Override
                    public void onException(Throwable e) {
                        log.error("关注事件MQ发送失败: {} -> {}", userId, targetUserId, e);
                    }
                });
    }

    @Override
    public List<Long> getCommonFollowing(Long userId, Long targetUserId) {
        // Redis ZRANGE 获取两个用户的关注列表，内存求交集
        // 注意：限制最多取前 5000 个，防止大 V 关注列表过大导致 OOM
        Set<String> myFollowing = redisTemplate.opsForZSet()
                .reverseRange("social:following:" + userId, 0, 4999);
        Set<String> targetFollowing = redisTemplate.opsForZSet()
                .reverseRange("social:following:" + targetUserId, 0, 4999);

        if (myFollowing == null || targetFollowing == null) {
            return Collections.emptyList();
        }

        // 求交集
        myFollowing.retainAll(targetFollowing);
        return myFollowing.stream().map(Long::valueOf).collect(Collectors.toList());
    }
}
```

---

## ⚖️ 七、方案对比

### 7.1 关注原子性：Lua 脚本 vs 分布式锁 vs Pipeline

| 维度 | Lua 脚本（✅ 选定） | 分布式锁 | Pipeline |
|------|-------------------|---------|----------|
| 原子性 | ✅ Redis 单线程保证 | ✅ 锁保证 | ❌ 非原子 |
| 性能 | 高（1 次网络往返） | 低（加锁+释放锁） | 高（批量发送） |
| 复杂度 | 中（需写 Lua） | 中（锁管理） | 低 |

**选择理由**：关注操作涉及 4 个 Redis 命令（2 个 ZADD + 2 个 INCR），必须原子执行。Lua 脚本在 Redis 单线程中执行，天然原子，且只需 1 次网络往返。

### 7.2 共同关注：Redis SINTER vs 内存交集

| 维度 | 内存交集（✅ 选定） | Redis SINTER |
|------|-------------------|-------------|
| 适用场景 | ZSet 数据 | Set 数据 |
| 性能 | 需要两次 ZRANGE + 内存计算 | 一次 SINTER |
| 兼容性 | 兼容 ZSet 存储 | 需要额外维护 Set |

**选择理由**：关注列表用 ZSet 存储（需要排序），SINTER 只支持 Set。所以先 ZRANGE 取出两个列表，再在内存中求交集。

---

## 🐛 八、踩坑记录

### 8.1 Lua 脚本 KEYS 参数传递错误

- **现象**：Lua 脚本执行报错 `CROSSSLOT Keys in request don't hash to the same slot`
- **原因**：Redis Cluster 模式下，Lua 脚本的 KEYS 必须在同一个 slot
- **解决**：使用 Hash Tag `{userId}` 保证相关 Key 在同一 slot，或使用单机 Redis
- **教训**：Lua 脚本在 Redis Cluster 下有 slot 限制

### 8.2 大 V 粉丝列表 ZSet 内存爆炸

- **现象**：百万粉丝的大 V，`social:follower:{userId}` ZSet 占用数百 MB
- **原因**：ZSet 每个元素约 100 字节，100 万元素 ≈ 100MB
- **解决**：大 V 粉丝列表不存 Redis，走 MySQL 分页查询；或 ZSet 只保留最近 1 万粉丝
- **教训**：ZSet 不适合存储超大集合，需要设置上限或分片

---

## 📊 九、测试验证

### 9.1 功能测试

| 测试场景 | 输入 | 预期结果 | 通过 |
|----------|------|----------|------|
| 关注用户 | 目标用户 ID | 关注成功，双方 ZSet 更新 | ⬜ |
| 重复关注 | 已关注的用户 | 返回"已关注该用户" | ⬜ |
| 关注自己 | 自己的 ID | 返回"不能关注自己" | ⬜ |
| 取关用户 | 已关注的用户 | 取关成功，双方 ZSet 更新 | ⬜ |
| 取关未关注的用户 | 未关注的用户 | 返回"未关注该用户" | ⬜ |
| 关注列表 | 用户 ID | 按关注时间倒序返回 | ⬜ |
| 粉丝列表 | 用户 ID | 按关注时间倒序返回 | ⬜ |
| 共同关注 | 两个用户 ID | 返回共同关注列表 | ⬜ |
| 查询关注关系 | 两个用户 ID | 返回 true/false | ⬜ |

---

## 🎤 十、面试考察点

### Q1: 为什么关注操作用 Lua 脚本？

**推荐回答思路**：

> 1. "关注操作涉及 4 个 Redis 命令：ZADD 关注列表 + ZADD 粉丝列表 + INCR 关注数 + INCR 粉丝数"
> 2. "这 4 个命令必须原子执行，否则可能出现'关注成功但计数未更新'的不一致"
> 3. "Lua 脚本在 Redis 单线程中执行，天然原子性，且只需 1 次网络往返"
> 4. "vs 分布式锁：锁的开销大（加锁+释放锁），且锁粒度难控制"

### Q2: 共同关注怎么实现？

**推荐回答思路**：

> 1. "两个用户的关注列表存在 Redis ZSet 中"
> 2. "分别 ZRANGE 取出两个关注列表，在内存中求交集"
> 3. "为什么不用 SINTER？因为关注列表用 ZSet（需要排序），SINTER 只支持 Set"
> 4. "优化：如果关注列表很大，可以只取前 N 个做交集，或异步预计算"

---

## 📚 参考资料

| 资料 | 相关章节 | 参考内容 |
|------|----------|----------|
| 📄 phase-1/README.md | §3.5 | 关注与社交关系完整设计（Lua 脚本） |
| 📄 02-module-detailed-design.md | §5 | 社交服务/关注/粉丝/共同关注 |
