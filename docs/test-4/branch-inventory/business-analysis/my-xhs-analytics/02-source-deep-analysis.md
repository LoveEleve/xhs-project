# my-xhs-analytics 模块深度分析

## 1. 当前模块定位

analytics（19003）是社交行为域，负责点赞、取消点赞、收藏、取消收藏、关注、取关，以及关系/列表/计数查询。它是 content/user 与 counter/notification 之间的行为事件枢纽：Redis 保存高频关系状态，MySQL 负责异步持久化，RocketMQ 将行为传播给计数与通知模块。

## 2. 当前代码事实

- 启动入口：`AnalyticsApplication`，加载 common 公共配置。
- Controller：LikeController、FavoriteController、FollowController。
- Service：LikeService、FavoriteService、FollowService。
- 消费者：LikeUnlikeConsumer、FavoriteUnlikeConsumer、FollowConsumer、UnfollowConsumer，统一消费 `SOCIAL_TOPIC` 的不同 tag。
- 数据实体：Like、Favorite、Follow；MyBatis Mapper 持久化到 analytics 库。
- 外部依赖：ContentFeignClient 校验笔记/评论存在性，UserFeignClient 校验目标用户存在性与拉黑关系。

## 3. 关键业务链路与源码流转

### 3.1 点赞

```text
POST /api/social/like {bizType,bizId}
  → LikeService.like
  → ContentFeign 校验目标存在性
  → Redis Lua SADD 正向集合（同一 user/biz 幂等）
  → 笔记类型维护用户反向集合
  → 异步 NOTIFICATION_TOPIC（作者通知）
  → 同步 SOCIAL_TOPIC:LIKE
  → LikeUnlikeConsumer 版本号 Lua 防乱序
  → t_like INSERT（唯一键重复则幂等跳过）
  → CounterEventConsumer 更新点赞计数
```

`bizType=1` 是笔记，`bizType=2` 是评论。目标校验失败现在返回业务错误，不再静默返回成功。MQ 发送失败时点赞主关系不回滚，依靠消费者幂等和重试恢复；取消点赞则发送失败回滚 Redis。

### 3.2 收藏

```text
POST /api/social/favorite
  → ContentFeign 校验笔记
  → Redis Lua ZSCORE+ZADD（ZSet score=收藏时间）
  → SOCIAL_TOPIC:FAVORITE
  → FavoriteUnlikeConsumer → t_favorite

DELETE /api/social/favorite
  → 记录原始 score
  → Lua ZSCORE+ZREM
  → MQ失败时用原始 score 回滚 ZADD
```

收藏列表/数量直接从 Redis ZSet 查询，MySQL 由 MQ 异步落库。

### 3.3 关注

```text
POST /api/social/follow/{targetUserId}
  → 禁止关注自己
  → UserFeign 校验目标存在
  → Redis 校验目标是否拉黑当前用户
  → Lua A：当前用户 following ZSet + following count
  → Lua B：目标用户 fans ZSet + follower count
  → 同步 INSERT t_follow
  → SOCIAL_TOPIC:FOLLOW 更新 counter 双向计数
  → NOTIFICATION_TOPIC 通知目标用户
```

A/B 拆成两个 Lua 是 Redis Cluster 兼容取舍（两个用户 key 不同 slot）。B 失败不回滚 A，依靠对账任务修复粉丝侧；MySQL 失败不影响 Redis 权威关系，同样靠对账修复。取关执行相反方向，未关注时幂等返回。

## 4. 数据流转与中间件参与点

| 中间件 | Key/表/topic | 一致性边界 |
|---|---|---|
| Redis | `myxhs:like:note:{id}`、`like:comment:{id}`、`like:user:{uid}:note` | 点赞关系权威；MySQL 异步从属 |
| Redis | `myxhs:favorite:{uid}` ZSet | 收藏关系/排序权威；MySQL 异步从属 |
| Redis | `myxhs:follow:list:{uid}`、`follow:fans:{uid}`、counter keys | 关注关系权威；t_follow 对账修复 |
| Redis | `analytics:event:version:like:{uid}:{bizType}:{bizId}` | LIKE/UNLIKE 乱序版本窗口 24h |
| MySQL | t_like、t_favorite、t_follow | 持久化查询/对账从属 |
| RocketMQ | SOCIAL_TOPIC tags LIKE/UNLIKE/FAVORITE/UNFAVORITE/FOLLOW/UNFOLLOW | counter/analytics consumer 最终一致 |
| RocketMQ | NOTIFICATION_TOPIC | notification 消费生成通知 |

## 5. 跨模块与分布式行为

- ContentFeign 失败时目标校验 fail-closed，避免对不存在内容写入社交关系。
- UserFeign 失败时关注操作 fail-closed；拉黑查询失败则放行（可用性优先，存在边界风险）。
- LIKE/UNLIKE 使用统一 consumer group + actionTime Lua 版本号，避免不同 consumer group 的跨组乱序。
- 点赞/收藏/关注的 Redis 写入与 MQ/DB 不是分布式事务，分别通过幂等、回滚、对账补偿收敛。
- Jackson 全局 Long→String 后，消费者 DTO/ObjectMapper 能兼容字符串 ID；analytics 事件发送与 counter 消费已实测。

## 6. 性能与工程质量

- 点赞/收藏/关注关键写入使用 Lua 原子脚本，避免 Redis Cluster 跨 slot。
- 列表状态查询使用 Pipeline，避免 N+1 Redis 往返。
- 关注列表/粉丝列表 pageSize 限制 50，共同关注限制 5000，防止大 V 数据导致 OOM。
- MQ 同步发送会增加接口延迟，但行为主数据在 Redis 已落地；取消操作失败时回滚。
- 对账方法以 Redis ZSet 为权威修复 MySQL 关系/计数，需依赖 XXL-Job 配置。

## 7. 鉴权基础检查

- 写接口通过 gateway JWT 注入 X-User-Id；读取接口按公开/登录语义配置。
- 关注/点赞/收藏均使用当前用户 ID，不接受请求体中的 userId，避免水平越权。
- 目标用户/目标内容由 Feign 校验。

## 8. 当前分支/近期改动点

- 目标存在性校验失败由静默成功改为明确业务失败（Like T-103、Favorite T-116）。
- Like/Unlike 统一消费者组并增加 actionTime 版本 Lua，解决跨组乱序。
- Follow Lua 拆分为同 slot 的两步，解决 Redis Cluster 路由约束。
- MQ 消费/事件 ID 使用字符串兼容全局 Long→String 序列化。
- 运行态已验证 SOCIAL_TOPIC→counter、NOTIFICATION_TOPIC 链路。

## 9. 风险与测试重点

- 代码：目标 Feign 查询成功但后续 Redis/MQ失败时的回滚/重试边界。
- 业务：自赞、自关、重复点赞、重复收藏、重复关注、拉黑后关注。
- 分布式：LIKE/UNLIKE 乱序、关注双 ZSet 半成功、Redis 与 MySQL 对账。
- 微服务：Content/User Feign 超时与 5xx 的 fail-closed 行为。
- 性能：大 V 关注列表/共同关注、批量点赞状态 Pipeline、MQ 同步发送延迟。
- 可观测：SOCIAL_TOPIC 消费失败/DLQ、Redis 对账修复、通知发送失败指标。

## 10. 覆盖对账

见 `03-file-review-matrix.md`。核心 Service/Controller/Consumer/Feign/实体/配置已覆盖；简单 DTO/Mapper 按调用关系核对，未逐行展开。
