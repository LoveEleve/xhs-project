# my-xhs-home 架构分析

## 一、BFF聚合层
每个Home端点调用2-5路Feign并行聚合，减少前端请求次数。所有调用均通过Feign+failback降级。

## 二、Feed双模式
1. **小V推送**: content发布→MQ FEED_TOPIC→FeedPushConsumer→ZADD粉丝收件箱 `myxhs:feed:inbox:{followerId}`
2. **大V拉取**: 不推送inbox→H01实时从 `myxhs:feed:outbox:{authorId}` 拉取→混合inbox排序
3. **大V判定**: Redis `myxhs:user:bigv:{uid}` 粉丝数>阈值标记

## 三、Feign调用矩阵
| 端点 | Product | Content | User | Analytics | Counter | Cart | Coupon | Inventory |
|------|:--:|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| H01 feed | | | ✅ | | | | | |
| H02 note | | ✅ | ✅ | ✅ | ✅ | | | |
| H03 product | ✅ | | | | ✅ | | | ✅ |
| H04 user | | ✅ | ✅ | ✅ | | | | |
| H05 cart | | | | | | ✅ | ✅ | ✅ |

## 四、断点续推
`myxhs:feed:push:progress:{localMsgId}` Redis记录进度, Consumer崩溃后MQ重试时从cursor恢复
