# my-xhs-counter 业务逻辑分析

## 一、写入路径(MQ→Redis→MySQL)

1. CounterEventConsumer 消费 SOCIAL_TOPIC 10种事件(LIKE/UNLIKE/FAVORITE/UNFAVORITE/COMMENT/UNCOMMENT/SHARE/VIEW/FOLLOW/UNFOLLOW)
2. Redis INCR `myxhs:counter:{targetType}:{targetId}:{countType}` 实时计数
3. Set去重 `myxhs:counter:dedup:{msgId}` 24h防重复
4. Buffer攒批百条→batchUpdate MySQL `t_counter`
5. like额外写 Set `myxhs:like:set:{type}:{id}` 用于精确去重

## 二、查询路径(CacheAside)

1. REDIS GET → 命中返回
2. 未命中 → MySQL SELECT → 回填Redis → 返回

## 三、对账路径

游标扫描 t_counter → 逐条对比 Redis → 不一致以MySQL为准修正Redis

## 四、计数类型

| countType | 含义 | targetType |
|:--:|------|------|
| 1 | 点赞数 | 1笔记/2评论 |
| 2 | 收藏数 | 1笔记 |
| 3 | 评论数 | 1笔记 |
| 4 | 分享数 | 1笔记 |
| 5 | 浏览数 | 1笔记 |
| 6 | 粉丝数 | 2用户 |
| 7 | 关注数 | 2用户 |
