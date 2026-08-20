# F-032 RocketMQ 指标脚本在采集命令失败时仍写入 up=1

## 严重度

Medium

## 涉及文件

- `config/deploy-cloud/rocketmq-metrics.sh:8-37`

## 现象

脚本多次执行 `docker exec ... | awk` / `docker exec ... | tail`，但没有严格检查 docker exec 或 mqadmin 的退出状态，最后无条件写入：

```text
rocketmq_up 1
```

即使 broker 不存在、namesrv 不可达或 mqadmin 失败，textfile collector 仍可能暴露“up=1”。

## 证据

1. broker、consumerProgress、topicList 均通过管道执行，未启用 `pipefail`：`:8-29`。
2. `rocketmq_up 1` 无条件写入：`:34-35`。
3. 脚本最后直接 `mv` 临时文件覆盖正式指标：`:37`。

## 影响

1. Prometheus 可能看到 RocketMQ 正常，但 broker/消费积压指标实际为空或过期。
2. DLQ/Retry 监控可能失去告警能力。
3. 运维会基于错误的健康指标判断消息链路正常。

## 修复建议

1. 使用 `set -Eeuo pipefail`，每个 mqadmin 命令单独校验退出码。
2. 只有所有必要采集成功时才写 `rocketmq_up 1`，否则写 0 或保留明确错误指标。
3. 为采集文件增加时间戳/新鲜度指标，避免旧文件被误认为当前状态。

## 是否需要补充验证

需要停止 broker 或让 `docker exec` 失败，观察 Prometheus textfile 中是否仍为 `rocketmq_up 1`。