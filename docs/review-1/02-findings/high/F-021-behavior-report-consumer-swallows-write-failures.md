# F-021 推荐行为消费者吞掉写库异常，行为数据静默丢失

## 严重度

High

## 涉及文件

- `my-xhs-search/src/main/java/com/myxhs/search/consumer/BehaviorReportConsumer.java:58-72`

## 现象

推荐行为消费者在写 `t_user_behavior` 失败时只记录日志，不抛异常，不触发 RocketMQ 重试，也不写补偿集合。

## 证据

1. 消费者直接 `INSERT INTO t_user_behavior`：`BehaviorReportConsumer.java:58-64`。
2. catch 块只记录日志并注释“行为数据允许少量丢失”，没有 `throw`：`BehaviorReportConsumer.java:69-72`。
3. 该消费者仍配置了 `maxReconsumeTimes = 3`，但异常被吞掉后这个配置完全失效：`BehaviorReportConsumer.java:30-34`。

## 触发条件

1. MySQL 暂时不可用
2. 主键冲突/字段异常/库表漂移
3. JDBC 瞬时失败

## 影响

1. 推荐、热门池、特征提取直接丢失行为样本，且无重试。
2. 丢失是静默发生的，后续只能看到推荐质量下降，难以定位。
3. 热门池与 Item-CF 都依赖 `t_user_behavior`，数据偏差会持续放大到离线结果。

## 修复建议

1. 区分可容忍的坏消息和基础设施故障：数据库失败应抛异常触发 MQ 重试。
2. 对无法重试的坏消息写补偿/死信集合，不应仅日志后返回。
3. 给 `t_user_behavior` 增加业务级幂等约束或事件 ID，避免重试放大数据。
4. 为行为消费失败建立独立指标和告警。

## 是否需要补充验证

需要模拟 MySQL 写失败，确认当前是否会直接确认消费且不进入重试。