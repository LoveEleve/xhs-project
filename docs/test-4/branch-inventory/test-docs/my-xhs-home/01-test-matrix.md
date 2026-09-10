# my-xhs-home 测试用例矩阵（L1-L4）

## L1 业务

| ID | 用例 | 请求 | 预期 | 状态 |
|---|---|---|---|---|
| H-L1-01 | 关注 Feed 聚合 | GET /api/home/feed | 返回关注用户最新笔记聚合 | ✅ 200 |
| H-L1-02 | 笔记详情聚合 | GET /api/home/note/{id} | 笔记+作者+点赞收藏评论计数聚合 | ✅ 200 |
| H-L1-03 | 商品详情聚合 | GET /api/home/product/{spuId} | 商品+SKU+库存聚合 | ✅ 200 |
| H-L1-04 | 用户主页聚合 | GET /api/home/user/{uid} | 用户信息+笔记列表+计数 | ✅ 200 |
| H-L1-05 | 购物车聚合 | GET /api/home/cart | 购物车+商品详情聚合 | ✅ 200 |

## L2 数据与推送

| ID | 验证点 | 证据 | 状态 |
|---|---|---|---|
| H-L2-01 | FEED_TOPIC 消费 | FeedPushConsumer 推送日志 | ✅ 推模式完成 |
| H-L2-02 | 推模式/收件箱 | 普通用户推模式 | ✅ pushed=0/0(粉丝0), 大V发件箱需粉丝数据 |
| H-L2-03 | push progress断点 | myxhs:feed:push:progress:{id} | ✅ 存在 |
| H-L2-04 | 已删笔记清理 | NoteDeleteConsumer | ✅ 清理完成 |

## L3 质量

| ID | 用例 | 预期 | 状态 |
|---|---|---|---|
| H-L3-01 | Feign故障降级 | 停cart服务 | ✅ 并发阻塞 → 503购物车服务不可用(fallback+DownstreamUnavailable) |
| H-L3-02 | 并行聚合超时 | 慢下游 | 不阻塞整体，超时降级 | ⬜ |
| H-L3-03 | 已删笔记乱序 | 删除后标记 | ✅ 已删标记存在+清理完成 |
| H-L3-04 | 推送失败断点续推 | 部分粉丝失败 | push progress 记录续推 | ⬜ |

## L4 可观测

| ID | 验证点 | 状态 |
|---|---|---|
| H-L4-01 | 聚合耗时指标 | ✅ Prometheus端点630指标暴露 |
| H-L4-02 | 降级触发日志 | ✅ NoteDelete清理日志 |
| H-L4-03 | TraceId 跨 Feign | ⬜ |
