# my-xhs-counter 测试用例矩阵（L1-L4）

## L1 业务

| ID | 用例 | 请求/数据 | 预期 | 状态 |
|---|---|---|---|---|
| C-L1-01 | 单计数 get | GET /api/counter/get?targetType=1&targetId=X&countType=1 | 返回 Redis/DB 计数值 | ✅ |
| C-L1-02 | 批量计数 get | POST /api/counter/batch-get | 多目标多类型返回 | ✅ |
| C-L1-03 | 点赞计数+1 | analytics like → counter | count=1 | ✅ |
| C-L1-04 | 取消点赞-1 | analytics unlike → counter | count 回落 | ✅ |
| C-L1-05 | 收藏计数+1 | analytics favorite → counter countType=2 | count=1 | ✅ |
| C-L1-06 | 关注/粉丝计数 | follow → counter 双向 | following/follower 各+1 | ✅ |
| C-L1-07 | 评论计数 | content comment → counter countType=3 | count 更新 | ✅ 评论后 countType=3=2 |
| C-L1-08 | 批量查询返回结构 | batch-get 多 countType | like/collect/comment/view 正确 | ✅ {"like":"0","collect":"1","comment":"2","view":"1"} |

## L2 数据

| ID | 验证点 | 证据 | 状态 |
|---|---|---|---|
| C-L2-01 | Redis counter key 结构 | `myxhs:counter:{type}:{id}:{ct}` | ✅ |
| C-L2-02 | dedup key 生命周期 | `myxhs:counter:dedup:{msgId}` 写入/过期 | ⬜ |
| C-L2-03 | like set SCARD | `myxhs:like:set:*` | ⬜ |
| C-L2-04 | t_counter Buffer 刷盘 | MySQL count_value 与 Redis 一致 | ✅ |
| C-L2-05 | counter key 30天 TTL | TTL=30d 非 -1 | ⬜ |

## L3 质量与异常

| ID | 用例 | 预期 | 状态 |
|---|---|---|---|
| C-L3-01 | MQ 重复消息去重 | 同 msgId 二次被 dedup Lua 拦截 | ⬜ |
| C-L3-02 | 归零保护 | count=0 时 UNLIKE 不产生负数 | ⬜ |
| C-L3-03 | LIKE/UNLIKE 乱序 | UNLIKE 先到 LIKE 后到，SCARD 正确 | ⬜ |
| C-L3-04 | 懒迁移 | counter Set 空 + counter>0 从 analytics 同步 | ⬜ |
| C-L3-05 | Buffer 刷盘失败 | 重试3次 + 对账兜底 | ⬜ |
| C-L3-06 | 对账修复 | Redis=0/DB>0 恢复 Redis；DB 漂移修正 | ⬜ |

## L4 可观测性

| ID | 验证点 | 证据 | 状态 |
|---|---|---|---|
| C-L4-01 | dedup/归零/对账日志 | consumer/service 日志 | ⬜ |
| C-L4-02 | 计数指标 | Prometheus | ⬜ |
| C-L4-03 | TraceId 跨链路 | analytics→counter 同 trace | ⬜ |
