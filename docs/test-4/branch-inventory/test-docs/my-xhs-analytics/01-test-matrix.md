# my-xhs-analytics 测试用例矩阵（L1-L4）

> 依据源码深度分析后编写，先文档后执行。每条执行记录必须包含：请求、预期、实际、下游证据。

## L1 业务

| ID | 用例 | 请求/数据 | 预期 | 状态 |
|---|---|---|---|---|
| A-L1-01 | 笔记点赞 | POST /api/social/like `{bizType:1,bizId:noteId}` | 200；Redis like Set 有 userId；SOCIAL_TOPIC LIKE | ✅ |
| A-L1-02 | 重复点赞 | 同请求两次 | 两次 200；Redis 仅一个 member；DB不重复 | ⬜ |
| A-L1-03 | 取消点赞 | DELETE /api/social/like 同 DTO | 200；Set移除；SOCIAL_TOPIC UNLIKE | ✅ |
| A-L1-04 | 不存在笔记点赞 | bizId=999999999 | NOT_FOUND，不写 Redis/MQ | ⬜ |
| A-L1-05 | 评论点赞 | bizType=2,bizId=commentId | 200；评论点赞关系/计数 | ⬜ |
| A-L1-06 | 收藏/取消收藏 | POST/DELETE /api/social/favorite `{noteId}` | 200；ZSet增删；MQ | ✅ |
| A-L1-07 | 关注用户 | POST /api/social/follow/{target} | 200；following/fans ZSet + 计数 + 通知 | ✅ |
| A-L1-08 | 重复关注/自关注 | 重复/target=userId | ALREADY_FOLLOWED/CANNOT_FOLLOW_SELF | ⬜ |
| A-L1-09 | 关注不存在用户 | target=999999999 | USER_NOT_FOUND，不写 Redis | ⬜ |
| A-L1-10 | 拉黑后关注 | target 拉黑当前用户后 follow | BLOCKED | ⬜ |
| A-L1-11 | 列表/关系/计数 | following/follower/relation/count | 返回 Redis ZSet 结果、互关状态、计数 | ✅ |

## L2 数据与消息

| ID | 验证点 | 证据 | 状态 |
|---|---|---|---|
| A-L2-01 | Redis like Set/反向 Set | Redis `myxhs:like:*` | ⬜ |
| A-L2-02 | Favorite ZSet score/分页 | Redis `myxhs:favorite:{uid}` | ⬜ |
| A-L2-03 | Follow ZSet/计数 | Redis follow list/fans/counter | ⬜ |
| A-L2-04 | t_like/t_favorite/t_follow 异步落库 | MySQL + consumer 日志 | ⬜ |
| A-L2-05 | SOCIAL_TOPIC 消费 | counter 日志/计数 | ✅ |
| A-L2-06 | NOTIFICATION_TOPIC 消费 | notification 落库/未读 | ✅ |

## L3 质量与异常

| ID | 用例 | 预期 | 状态 |
|---|---|---|---|
| A-L3-01 | LIKE/UNLIKE 乱序 | actionTime 旧事件跳过，最终状态正确 | ⬜ |
| A-L3-02 | MQ 重复消息 | version Lua + DB唯一键幂等 | ⬜ |
| A-L3-03 | Redis/MQ故障 | 记录降级/回滚；不产生幽灵关系 | ⬜ |
| A-L3-04 | 关注双步半成功 | 对账可修复 follower 侧 | ⬜ |
| A-L3-05 | 拉黑边界 | 拉黑后 follow 拒绝，自身关系不污染 | ⬜ |
| A-L3-06 | 批量状态上限 | >100 bizId 截断/拒绝，不产生 OOM | ⬜ |

## L4 可观测性

| ID | 验证点 | 证据 | 状态 |
|---|---|---|---|
| A-L4-01 | TraceId | analytics→SOCIAL_TOPIC→counter 日志同 trace | ⬜ |
| A-L4-02 | 消费失败/DLQ | consumer 重试与 DLQ 监控 | ⬜ |
| A-L4-03 | 业务指标 | Prometheus 点赞/关注/消费指标 | ⬜ |
| A-L4-04 | 敏感信息脱敏 | 日志不含 token/完整 body | ⬜ |

## 执行记录格式

每个用例执行后补：时间、请求、预期、实际、MySQL/Redis/MQ/日志证据、结论（通过/发现问题/阻塞）。
