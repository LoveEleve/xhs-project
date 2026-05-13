# 点赞收藏

> 所属服务：my-xhs-analytics (9003) | 开发阶段：Phase-1 | 状态：⏳ 待开发

---

## 🎯 一、需求分析

### 1.1 业务场景

用户对笔记/评论点赞，对笔记收藏。幂等设计防止重复操作，Redis Set 存储点赞用户集合（O(1) 判断是否已点赞），MQ 异步落库到 MySQL。点赞/收藏事件通过 MQ 通知 Counter 服务更新计数。

### 1.2 功能边界

| 功能项 | 是否实现 | 说明 |
|--------|---------|------|
| 笔记点赞/取消 | ✅ | Redis Set + @Idempotent 幂等 |
| 评论点赞/取消 | ✅ | 复用点赞逻辑（bizType=COMMENT） |
| 笔记收藏/取消 | ✅ | Redis ZSet（score=收藏时间） |
| 点赞状态查询 | ✅ | SISMEMBER O(1) |
| 批量点赞状态 | ✅ | Pipeline SMISMEMBER（列表页批量查询） |
| 收藏列表 | ✅ | ZSet 分页查询 |
| 异步落库 | ✅ | MQ → MySQL |
| 计数联动 | ✅ | MQ → Counter 服务 |
| 点赞列表（谁赞了我） | ❌ | 后续扩展 |

### 1.3 数据量预估

| 指标 | 预估值 | 计算依据 |
|------|--------|----------|
| 点赞总量 | 50 亿 | 5000 万笔记 × 平均 100 次点赞 |
| 收藏总量 | 10 亿 | 5000 万笔记 × 平均 20 次收藏 |
| 点赞 QPS | 5000 | 高峰期集中点赞 |
| 点赞状态查询 QPS | 10000 | 每次加载笔记列表都要查 |

---

## 🏗️ 二、架构设计

### 2.1 整体架构图

```
Client → Gateway(鉴权) → my-xhs-analytics(9003) → Redis(点赞Set/收藏ZSet)
                              │
                              ├── @Idempotent: 幂等防重
                              └── RocketMQ → MySQL(t_like/t_favorite) 异步落库
                                           → Counter 服务更新点赞数/收藏数
```

### 2.2 核心流程时序图

**点赞流程：**

```
1. Client → Gateway: POST /api/social/like（鉴权通过）
2. Gateway → LikeService: 转发请求
3. LikeService → Redis: SADD social:like:note:{noteId} {userId}
   ├── 返回 1 → 新增点赞（继续）
   └── 返回 0 → 已点赞（幂等，直接返回成功）
4. LikeService → Redis: SADD social:like:user:{userId}:note {noteId}（反向索引）
5. LikeService → RocketMQ: 发送 SOCIAL_TOPIC:LIKE 消息
6. Consumer → MySQL: INSERT t_like（异步落库）
7. Counter Consumer: INCR counter:note_like:{noteId}（更新点赞数）
8. LikeService → Client: 返回点赞成功
```

**批量查询点赞状态（列表页）：**

```
1. Client: 加载笔记列表（10 条笔记）
2. Client → LikeService: GET /api/social/like/batch-status?noteIds=1,2,3,...,10
3. LikeService → Redis: Pipeline 批量执行 SISMEMBER
   - SISMEMBER social:like:note:1 {userId}
   - SISMEMBER social:like:note:2 {userId}
   - ... × 10
4. LikeService → Client: 返回 {1: true, 2: false, 3: true, ...}
```

---

## 🗄️ 三、数据库设计

### 3.1 表结构

```sql
-- 点赞表（MQ 异步落库，Redis Set 为权威数据源）
-- 实际 DDL 来自 init-databases.sql（my_xhs_analytics 库）
CREATE TABLE IF NOT EXISTS t_like (
    id         BIGINT   NOT NULL COMMENT 'ID',
    user_id    BIGINT   NOT NULL COMMENT '用户ID',
    biz_type   TINYINT  NOT NULL COMMENT '业务类型：1-笔记 2-评论',
    biz_id     BIGINT   NOT NULL COMMENT '业务ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_user_biz (user_id, biz_type, biz_id),
    INDEX idx_biz (biz_type, biz_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='点赞表';

-- 收藏表（MQ 异步落库，Redis ZSet 为权威数据源）
CREATE TABLE IF NOT EXISTS t_favorite (
    id         BIGINT   NOT NULL COMMENT 'ID',
    user_id    BIGINT   NOT NULL COMMENT '用户ID',
    note_id    BIGINT   NOT NULL COMMENT '笔记ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE INDEX uk_user_note (user_id, note_id),
    INDEX idx_note_id (note_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='收藏表';
```

> **说明**：点赞/收藏的权威数据存储在 Redis Set/ZSet 中（永久不过期），MySQL 的 t_like/t_favorite 作为异步落库的持久化兜底。t_user_behavior 表仅用于行为分析日志（浏览/搜索等），不存储点赞/收藏关系。

### 3.2 索引设计

| 索引名 | 字段 | 使用场景 |
|--------|------|----------|
| `uk_user_biz` | (user_id, biz_type, biz_id) | 点赞唯一索引，防重复点赞 + MQ 落库幂等兜底 |
| `idx_biz` | (biz_type, biz_id) | 查某笔记/评论的点赞记录 |
| `uk_user_note` | (user_id, note_id) | 收藏唯一索引，防重复收藏 |
| `idx_note_id` | note_id | 查某笔记的收藏记录 |

---

## 🔑 四、缓存设计

### 4.1 Redis Key 规范

| Key Pattern | 类型 | 过期时间 | 说明 |
|-------------|------|----------|------|
| `social:like:note:{noteId}` | Set | 永久 | 笔记点赞用户集合 |
| `social:like:user:{userId}:note` | Set | 永久 | 用户点赞的笔记集合（反向索引） |
| `social:like:comment:{commentId}` | Set | 永久 | 评论点赞用户集合 |
| `social:favorite:{userId}` | ZSet | 永久 | 用户收藏列表（score=收藏时间） |

### 4.2 为什么用 Redis Set 而不是 MySQL？

| 维度 | Redis Set（✅ 选定） | MySQL |
|------|---------------------|-------|
| 点赞判断 | SISMEMBER O(1) | SELECT COUNT 需要索引 |
| 批量判断 | Pipeline SMISMEMBER | 多次 SQL 或 IN 查询 |
| 幂等 | SADD 天然幂等（返回 0=已存在） | 需要唯一索引 + 异常处理 |
| QPS | 10 万+ | 千级 |

---

## 📡 五、接口设计

### 5.1 接口列表

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/api/social/like` | 点赞（笔记/评论） | ✅ |
| DELETE | `/api/social/like` | 取消点赞 | ✅ |
| GET | `/api/social/like/status` | 查询点赞状态 | ✅ |
| GET | `/api/social/like/batch-status` | 批量查询点赞状态 | ✅ |
| POST | `/api/social/favorite` | 收藏笔记 | ✅ |
| DELETE | `/api/social/favorite` | 取消收藏 | ✅ |
| GET | `/api/social/favorite/list` | 收藏列表 | ✅ |
| GET | `/api/social/favorite/status` | 查询收藏状态 | ✅ |

### 5.2 请求/响应示例

**点赞**

```http
POST /api/social/like
Content-Type: application/json
Authorization: Bearer {accessToken}

{
  "bizType": 1,
  "bizId": 20001
}
```

```json
{
  "code": 200,
  "msg": "点赞成功"
}
```

**批量查询点赞状态**

```http
GET /api/social/like/batch-status?bizType=1&bizIds=20001,20002,20003
Authorization: Bearer {accessToken}
```

```json
{
  "code": 200,
  "data": {
    "20001": true,
    "20002": false,
    "20003": true
  }
}
```

---

## 💻 六、核心代码实现

### 6.1 点赞服务（幂等 + 异步落库）

```java
/**
 * 点赞服务
 * 关键点：SADD 天然幂等 → MQ 异步落库 → MQ 通知计数
 */
@Service
public class LikeServiceImpl implements LikeService {

    @Override
    @Idempotent(key = "#userId + ':' + #request.bizType + ':' + #request.bizId", expireSeconds = 5)
    // 双层幂等：@Idempotent 拦截网络抖动重复提交（5秒内相同请求直接拦截），SADD 保证业务层幂等
    public void like(Long userId, LikeRequest request) {
        String likeKey = buildLikeKey(request.getBizType(), request.getBizId());

        // 1. SADD 添加点赞（返回 1=新增，0=已存在）
        Long added = redisTemplate.opsForSet().add(likeKey, String.valueOf(userId));
        if (added == null || added == 0) {
            return; // 已点赞，幂等返回
        }

        // 2. 反向索引（用户点赞了哪些笔记）
        if (request.getBizType() == BizType.NOTE.getCode()) {
            redisTemplate.opsForSet().add(
                    "social:like:user:" + userId + ":note",
                    String.valueOf(request.getBizId()));
        }

        // 3. MQ 异步落库 + 通知计数
        rocketMQTemplate.asyncSend("SOCIAL_TOPIC:LIKE",
                MessageBuilder.withPayload(new LikeEvent(
                        userId, request.getBizType(), request.getBizId(), "LIKE"))
                        .build(),
                new SendCallback() {
                    @Override
                    public void onSuccess(SendResult sendResult) {}
                    @Override
                    public void onException(Throwable e) {
                        log.error("点赞事件MQ发送失败", e);
                    }
                });
    }

    @Override
    public void unlike(Long userId, LikeRequest request) {
        String likeKey = buildLikeKey(request.getBizType(), request.getBizId());

        // 1. SREM 移除点赞
        Long removed = redisTemplate.opsForSet().remove(likeKey, String.valueOf(userId));
        if (removed == null || removed == 0) {
            return; // 未点赞，幂等返回
        }

        // 2. 移除反向索引
        if (request.getBizType() == BizType.NOTE.getCode()) {
            redisTemplate.opsForSet().remove(
                    "social:like:user:" + userId + ":note",
                    String.valueOf(request.getBizId()));
        }

        // 3. MQ 异步落库 + 通知计数
        rocketMQTemplate.asyncSend("SOCIAL_TOPIC:UNLIKE",
                MessageBuilder.withPayload(new LikeEvent(
                        userId, request.getBizType(), request.getBizId(), "UNLIKE"))
                        .build(),
                new SendCallback() {
                    @Override
                    public void onSuccess(SendResult sendResult) {}
                    @Override
                    public void onException(Throwable e) {
                        log.error("取消点赞事件MQ发送失败", e);
                    }
                });
    }

    /**
     * 批量查询点赞状态（Pipeline 优化）
     * 笔记列表页一次查 10 条笔记的点赞状态
     */
    @Override
    public Map<Long, Boolean> batchCheckLikeStatus(Long userId, int bizType, List<Long> bizIds) {
        List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (Long bizId : bizIds) {
                String key = buildLikeKey(bizType, bizId);
                connection.sIsMember(key.getBytes(), String.valueOf(userId).getBytes());
            }
            return null;
        });

        Map<Long, Boolean> statusMap = new HashMap<>();
        for (int i = 0; i < bizIds.size(); i++) {
            statusMap.put(bizIds.get(i), (Boolean) results.get(i));
        }
        return statusMap;
    }

    private String buildLikeKey(int bizType, Long bizId) {
        if (bizType == BizType.NOTE.getCode()) {
            return "social:like:note:" + bizId;
        } else {
            return "social:like:comment:" + bizId;
        }
    }
}
```

### 6.2 收藏服务

```java
/**
 * 收藏服务
 * 关键点：ZSet 存储收藏列表（score=收藏时间），天然排序+分页
 */
@Service
public class FavoriteServiceImpl implements FavoriteService {

    @Override
    public void favorite(Long userId, Long noteId) {
        String key = "social:favorite:" + userId;
        Double score = redisTemplate.opsForZSet().score(key, String.valueOf(noteId));
        if (score != null) {
            return; // 已收藏，幂等返回
        }

        // 1. ZADD 添加收藏
        redisTemplate.opsForZSet().add(key, String.valueOf(noteId),
                System.currentTimeMillis());

        // 2. MQ 异步落库 + 通知计数
        rocketMQTemplate.asyncSend("SOCIAL_TOPIC:FAVORITE",
                MessageBuilder.withPayload(new FavoriteEvent(userId, noteId, "FAVORITE"))
                        .build(), null);
    }

    @Override
    public PageResult<Long> getFavoriteList(Long userId, int page, int size) {
        String key = "social:favorite:" + userId;
        // ZSet 倒序分页（最新收藏在前）
        long start = (long) (page - 1) * size;
        long end = start + size - 1;
        Set<String> noteIds = redisTemplate.opsForZSet().reverseRange(key, start, end);
        Long total = redisTemplate.opsForZSet().zCard(key);

        List<Long> ids = noteIds != null
                ? noteIds.stream().map(Long::valueOf).collect(Collectors.toList())
                : Collections.emptyList();
        return new PageResult<>(total != null ? total : 0, ids);
    }
}
```

---

## ⚖️ 七、方案对比

### 7.1 点赞存储：Redis Set vs MySQL

| 维度 | Redis Set（✅ 选定） | MySQL |
|------|---------------------|-------|
| 点赞判断 | SISMEMBER O(1)，微秒级 | SELECT + 索引，毫秒级 |
| 幂等 | SADD 天然幂等 | 唯一索引 + 异常处理 |
| 批量查询 | Pipeline 10 次 SISMEMBER < 1ms | IN 查询 + 索引 |
| 内存 | 每个点赞约 50 字节 | 无额外内存 |
| 持久性 | Redis 持久化 + MQ 异步落库 | 强持久 |

**选择理由**：点赞是超高频操作（QPS 万级），Redis Set 性能远超 MySQL。SADD 天然幂等，不需要额外的幂等逻辑。

### 7.2 批量查询：Pipeline vs Lua vs 多次查询

| 维度 | Pipeline（✅ 选定） | Lua 脚本 | 多次查询 |
|------|-------------------|---------|---------|
| 网络往返 | 1 次 | 1 次 | N 次 |
| 复杂度 | 低 | 中 | 低 |
| 适用场景 | 批量独立操作 | 需要原子性 | 少量查询 |

**选择理由**：批量查询点赞状态不需要原子性，Pipeline 批量发送 + 批量接收，1 次网络往返完成 10 次查询。

---

## 🐛 八、踩坑记录

### 8.1 热门笔记点赞 Set 内存爆炸

- **现象**：百万点赞的热门笔记，`social:like:note:{noteId}` Set 占用数十 MB
- **原因**：Set 存储所有点赞用户 ID
- **解决**：热门笔记点赞 Set 设置上限（如 10 万），超过后只记录计数不记录用户 ID；或使用 Bloom Filter 替代 Set
- **教训**：Set 不适合存储超大集合，需要设置上限

### 8.2 取消点赞后计数变为负数

- **现象**：用户未点赞直接调用取消点赞，计数 DECR 变为 -1
- **原因**：取消点赞时未检查是否已点赞
- **解决**：SREM 返回 0 表示未点赞，直接返回不发 MQ
- **教训**：取消操作必须先检查状态

---

## 📊 九、测试验证

### 9.1 功能测试

| 测试场景 | 输入 | 预期结果 | 通过 |
|----------|------|----------|------|
| 点赞笔记 | bizType=1, bizId=20001 | 点赞成功 | ⬜ |
| 重复点赞 | 同一笔记再次点赞 | 幂等返回成功 | ⬜ |
| 取消点赞 | 已点赞的笔记 | 取消成功 | ⬜ |
| 取消未点赞 | 未点赞的笔记 | 幂等返回成功 | ⬜ |
| 查询点赞状态 | 已点赞的笔记 | 返回 true | ⬜ |
| 批量查询 | 10 个笔记 ID | 返回 Map<id, boolean> | ⬜ |
| 收藏笔记 | noteId=20001 | 收藏成功 | ⬜ |
| 收藏列表 | page=1, size=10 | 按收藏时间倒序 | ⬜ |

---

## 🎤 十、面试考察点

### Q1: 点赞怎么保证幂等？

**推荐回答思路**：

> 1. "Redis Set 的 SADD 命令天然幂等：返回 1 表示新增成功，返回 0 表示已存在"
> 2. "SADD 返回 0 时直接返回成功，不发 MQ，不更新计数——用户无感知"
> 3. "接口层还加了 @Idempotent 注解，5 秒内相同请求直接拦截，防止网络抖动重复提交"
> 4. "MySQL 层用唯一索引兜底，即使 Redis 和 MQ 都出问题，DB 也不会重复记录"

### Q2: 笔记列表页怎么批量查询点赞状态？

**推荐回答思路**：

> 1. "用 Redis Pipeline 批量查询：一次网络往返执行 10 次 SISMEMBER"
> 2. "vs 逐条查询：10 次网络往返 × 1ms = 10ms；Pipeline：1 次往返 < 1ms"
> 3. "返回 Map<noteId, Boolean>，前端根据结果渲染点赞按钮状态"

---

## 📚 参考资料

| 资料 | 相关章节 | 参考内容 |
|------|----------|----------|
| 📄 phase-1/README.md | §3.6 | 点赞收藏完整设计（Redis Key/幂等/Pipeline） |
| 📄 02-module-detailed-design.md | §5.3 | 点赞/收藏/幂等/异步落库 |
