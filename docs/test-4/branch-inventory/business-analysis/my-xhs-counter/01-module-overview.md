# my-xhs-counter 模块分析

## 1. 模块定位
计数域（19004）：点赞/收藏/评论/分享/浏览/粉丝/关注计数。消费 SOCIAL_TOPIC 各行为事件，Redis Set/Lua 原子更新 + 缓冲写 MySQL t_counter。是社交行为的计数聚合层。

## 2. 代码事实
- 14 个 java：CounterController（get/batch-get/reconcile）、CounterEventConsumer、CounterService、CounterBuffer、CounterReconcileJob、CounterMapper
- 启动类扫描 common

## 3. 核心链路
```text
SOCIAL_TOPIC(LIKE/UNLIKE/FAVORITE/UNFAVORITE/COMMENT/UNCOMMENT/SHARE/VIEW/FOLLOW/UNFOLLOW)
→ CounterEventConsumer: tag 分发 → CounterService:
   点赞 Set-based(SADD/SREM, SCARD=计数, 天然抗乱序) + Lua msgId dedup
   其他 increment/decrementWithDedup(Lua) → Redis buffer → 批量 flush MySQL
```

## 4. 数据流转
| 项 | 内容 |
|---|---|
| MySQL | t_counter(biz_type/target_id/count_type)，Mapper 批量 upsert |
| Redis | Set（点赞/粉丝）+ 计数 buffer + msgId dedup Lua |
| MQ | 消费 SOCIAL_TOPIC（10 种 tag） |
| 幂等 | Set-based（点赞/粉丝天然幂等）+ msgId 去重 Lua（其他计数） |

## 5. 关键决策
- 点赞计数用 Set（SCARD）替代 delta：先到 UNLIKE 是 SREM no-op，后到 LIKE 正常 → 最终一致
- 评论 UNCOMMENT 支持 count（级联删除 1+N）
- 关注双向计数：follower 关注数 + followee 粉丝数，followee 失败回滚 follower（msgId+_rollback）
- toLong/toInt 兼容字符串 id（JacksonConfig 全局 Long→String）

## 6. 运行态验证（实测通过）
- 点赞→SOCIAL_TOPIC→counter 消费→计数=1 返回正确
- 取消点赞后计数回落（Set-based 正确）

## 7. 鉴权基础
- /api/counter/get 在 gateway 白名单（公开读）；reconcile 需内部调用

## 8. 风险
- 计数 buffer 异步 flush：宕机丢 buffer（Redis 未落 MySQL）→ CounterReconcileJob（XXL-Job 每小时）对账兜底
- follow 双写部分失败回滚逻辑已内置

## 9. 覆盖对账
- Consumer/CounterService 已深读；CounterBuffer 机制从 Service 调用关系确认；CounterReconcileJob 标注（对账逻辑，注册已确认）
- Entity/Mapper/DTO 简单类未逐行
