# my-xhs-analytics 文件覆盖对账

## 实际文件基线

- Java 主源码 29 个；资源配置、Mapper XML、测试文件另计。
- inventory.md 仅列顶层 src/pom/Dockerfile，实际嵌套文件以扫描结果为准。

## 已覆盖

| 范围 | 文件/内容 | 状态 |
|---|---|---|
| 启动 | AnalyticsApplication | 已核对 |
| Controller | LikeController、FavoriteController、FollowController | 已核对路由/鉴权/参数 |
| Service | LikeService、FavoriteService、FollowService | 已深读关键分支、Lua、MQ、Feign、补偿 |
| Consumer | LikeUnlikeConsumer、FavoriteUnlikeConsumer、FollowConsumer、UnfollowConsumer | 已核对 topic/tag、幂等、乱序与字符串 ID |
| Feign | ContentFeignClient、UserFeignClient | 已核对目标存在性/拉黑校验 |
| Entity/Mapper | Like、Favorite、Follow 及对应 Mapper | 已按 Service/Consumer 数据流核对 |
| Config | RedisScriptConfig、XxlJobConfig | 已核对为脚本/任务装配 |
| DTO | event/request/response DTO | 已核对事件字段与 Long→String 兼容 |

## 明确未逐行展开

| 范围 | 原因 |
|---|---|
| application.yml、application-datasource.properties、logback-spring.xml | 通用部署/日志配置，运行态已确认服务 UP、Redis/MySQL/MQ 可用 |
| Dockerfile、pom.xml | 已确认构建/依赖存在，非业务逻辑 |
| 简单 getter/setter DTO、BaseMapper 接口 | 无独立业务分支，随调用链覆盖 |
| target/构建生成物 | 明确排除 |

## 运行态覆盖

- 点赞/取消点赞、收藏/取消收藏、关注/取关/关系查询：已实测
- SOCIAL_TOPIC → counter：已实测计数更新
- NOTIFICATION_TOPIC：已实测通知消费（由点赞/关注触发）
- 异常注入、Redis 故障、MQ 重试/DLQ、极端并发：尚未执行，已列测试矩阵
