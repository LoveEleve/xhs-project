# F-043 点赞/收藏在目标校验异常时降级放行，会对不存在内容落 Redis/MQ

## 严重度

High

## 涉及文件

- `my-xhs-analytics/src/main/java/com/myxhs/analytics/service/LikeService.java:351-366`
- `my-xhs-analytics/src/main/java/com/myxhs/analytics/service/FavoriteService.java:186-197`

## 现象

点赞与收藏都会先通过 content 服务校验“笔记/评论是否存在且可见”。但如果校验请求本身异常（下游超时、网络抖动、服务故障），代码选择“降级放行”而不是失败：

- `LikeService.validateTarget()` catch 后 `return true`
- `FavoriteService.validateNote()` catch 后 `return true`

## 证据

1. `LikeService.validateTarget()` 在异常时记录 `"目标校验异常(降级放行)"` 并 `return true`：`LikeService.java:363-365`。
2. `FavoriteService.validateNote()` 在异常时记录 `"目标校验异常(降级放行)"` 并 `return true`：`FavoriteService.java:195-196`。
3. 上游业务逻辑在 validate 返回 true 后会继续写 Redis、发 MQ、更新计数和通知链路。

## 触发条件

1. content 服务超时/不可用/返回异常
2. analytics 仍收到点赞/收藏请求

## 影响

1. 已删除、未发布、草稿或根本不存在的内容可能被成功点赞/收藏。
2. Redis 关系集、计数、通知、搜索热度都会被错误更新。
3. 后续即使内容服务恢复，也需要多条补偿链才能把这些“幽灵互动”清理干净。
4. 这不是简单的可用性降级，而是把依赖失败伪装成业务成功。

## 修复建议

1. 目标存在性校验失败应 fail-closed，至少对写操作返回 503/重试，而不是继续放行。
2. 若必须容忍下游抖动，应引入异步确认/延迟写入，而不是先写互动状态。
3. 对已存在的 Redis/MQ 幽灵互动需要设计补偿清理机制。

## 是否需要补充验证

需要在 content 服务不可用时发起 like/favorite 请求，确认当前是否仍返回成功并产生 Redis/MQ 副作用。