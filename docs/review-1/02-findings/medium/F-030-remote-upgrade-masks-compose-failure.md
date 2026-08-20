# F-030 remote-upgrade.sh 可能吞掉 docker compose 失败并继续执行

## 严重度

Medium

## 涉及文件

- `config/deploy-cloud/remote-upgrade.sh:44-51`

## 现象

脚本使用 `set -e`，但 `docker compose up -d` 被放在管道中：

```bash
docker compose up -d 2>&1 | tail -5 || echo "⚠️ docker compose up -d 失败"
```

脚本没有启用 `set -o pipefail`，因此即使 `docker compose up -d` 失败，只要 `tail` 成功，整个管道仍返回成功，`|| echo` 不会执行，脚本继续进入“验证成功”流程。

## 影响

1. 部署脚本可能在容器未更新、compose 语法错误或镜像启动失败时输出后续完成信息。
2. 运维人员会误以为 restart 策略已生效，实际仍运行旧容器或部分服务未启动。
3. 这与 F-014 的“健康检查误判”叠加，会造成部署状态假阳性。

## 修复建议

1. 使用 `set -Eeuo pipefail`。
2. 不要用 `tail` 包裹关键命令的退出状态，或先写日志再单独读取尾部。
3. `docker compose up -d` 失败时立即退出，并检查目标容器的实际 restart policy 与健康状态。
4. 验证应覆盖“期望服务集合”和容器健康状态，而不是只统计正在运行的容器。

## 是否需要补充验证

需要使用错误 compose 或不可用镜像模拟 `docker compose up -d` 失败，确认脚本当前是否仍返回成功。