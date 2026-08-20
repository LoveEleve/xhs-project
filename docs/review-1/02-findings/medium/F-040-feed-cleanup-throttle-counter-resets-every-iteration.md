# F-040 FeedCleanupJob 的节流计数器写在循环体内，节流逻辑永远不生效

## 严重度

Medium

## 涉及文件

- `my-xhs-home/src/main/java/com/myxhs/home/job/FeedCleanupJob.java:58-78`
- `my-xhs-home/src/main/java/com/myxhs/home/job/FeedCleanupJob.java:88-97`

## 现象

`FeedCleanupJob` 设计上每处理 100 个 key `sleep(50ms)` 节流一次，但计数变量 `count` 被声明在 `while` 循环体内，每次迭代都会重置为 0。

结果：

```java
int count = 0;
...
if (++count % 100 == 0) Thread.sleep(50);
```

这个条件永远不会成立，节流逻辑形同虚设。

## 影响

1. 当 feed inbox/outbox key 数量很大时，清理任务会持续高频扫 Redis，没有预期中的让步间隔。
2. 可能增加凌晨清理窗口对 Redis 的瞬时压力。
3. 注释与实现不一致，后续排查性能问题容易被误导。

## 修复建议

1. 将 `count` 提升到 `while` 外部，作为跨迭代累加器。
2. 对 inbox 和 outbox 两段循环都修正。
3. 视实际 key 数量考虑改为按批次 SCAN + pipeline，而不是逐 key 即时操作。

## 是否需要补充验证

不需要额外验证；代码结构已足够证明节流条件无法命中。