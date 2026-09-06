# my-xhs-analytics 模块分析

## 1. 模块定位
社交行为域（19003）：点赞、关注、收藏。写 MySQL（t_like/t_follow/t_favorite），发 SOCIAL_TOPIC（counter 消费计数）与 NOTIFICATION_TOPIC（通知）。是内容/用户互动的行为落库层。

## 2. 代码事实
- 29 个 java：3 Controller（Like/Follow/Favorite）、4 Consumer（全消费 SOCIAL_TOPIC）、3 Service、Feign(Content)
- 启动类 scanBasePackages 含 com.myxhs.common

## 3. 核心链路（Like 为例）
```text
POST /api/social/like {bizType,bizId}
→ LikeService.like: 校验 + 发 SOCIAL_TOPIC(LIKE tag) + 发 NOTIFICATION_TOPIC
→ LikeUnlikeConsumer(like-unlike-consumer-group, selector LIKE||UNLIKE):
   actionTime版本号Lua防乱序 → handleLike/handleUnlike → DB唯一键幂等
```

## 4. 数据流转
| 项 | 内容 |
|---|---|
| MySQL | t_like(user_id+biz_type+biz_id 唯一键幂等)、t_follow、t_favorite |
| MQ | 发 SOCIAL_TOPIC(LIKE/UNLIKE/FOLLOW/UNFOLLOW/FAVORITE/UNFAVORITE)、NOTIFICATION_TOPIC |
| Redis | like 版本号 key `analytics:event:version:like:{userId}:{bizType}:{bizId}`（24h TTL） |
| 幂等 | LIKE/UNLIKE 统一 consumer group 保证组内顺序 + actionTime 版本号 Lua + DB 唯一键 |

## 5. 跨模块
- 消费：无（行为在自身 Service 发起）
- 被消费：counter（SOCIAL_TOPIC 计数）、notification（NOTIFICATION_TOPIC）
- Feign：ContentFeignClient（校验笔记存在等）

## 6. 运行态验证（实测通过）
- 点赞/取消点赞、收藏/取消收藏、关注/取关/关系查询 全部 200
- counter 点赞计数=1（SOCIAL_TOPIC→counter 链路通）

## 7. 鉴权基础
- Controller 用 X-User-Id（gateway 注入）；GET 公开接口（like/count 等）在 gateway 白名单或 JWT

## 8. 风险
- LikeUnlikeConsumer 版本号 key 按 userId 隔离（正确）；消费失败删除版本号允许重试（重复 LIKE 靠 DB 唯一键幂等）
- 消费端 `objectMapper.readValue(LikeEvent.class)`：Long 字段字符串反序列化 Jackson 自动转（安全）

## 9. 覆盖对账
- Controller/Consumer/Service 核心已读；Entity/Mapper 由链路验证；DTO 未逐行（简单 VO）
- Feign、XxlJobConfig、RedisScriptConfig 标注（装配类）
