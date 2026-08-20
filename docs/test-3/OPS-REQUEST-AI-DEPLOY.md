# AI 诊断台运维需求单

> 2026-08-19 | 提交给部署团队（中间件/运维）

---

## 一、背景

AI 诊断台（`my-xhs-ai-app:19020`）需要完成一条真实 E2E 链路验证：
Agent 自动查询 RocketMQ 死信消息 → 提取消息 ID → 执行重投 → 消费成功。

当前卡在 3 个运维层面的问题，需要部署团队配合解决。

---

## 二、需求清单

### 需求 1【P0】重启 AI 诊断台服务

**原因**：修复了一个配置 bug（`mqDlqQuery` 工具未暴露给 Agent），新 jar 已打包完成，需要部署到服务器并重启。

**操作**：

1. 将 `/data/workspace/my-xhs/my-xhs-ai-app/target/my-xhs-ai-app-1.0-SNAPSHOT.jar` 拷贝到 `21.214.97.212` 的服务目录
2. 重启 `my-xhs-ai-app` 进程（端口 19020）

**当前状态**：
- 服务在 `21.214.97.212:19020` 运行中（能响应 HTTP，但 `/actuator/health` 返回 500）
- SSH 到 `21.214.97.212` 不通（端口 22 连接被拒），无法自行部署
- 新 jar 路径：`/data/workspace/my-xhs/my-xhs-ai-app/target/my-xhs-ai-app-1.0-SNAPSHOT.jar`（编译时间 2026-08-19 11:28）

**验证**：重启后访问 `http://21.214.97.212:19020/actuator/health` 应返回 `{"status":"UP"}`

---

### 需求 2【P0】确认 AI 诊断台的 DLQ 重投配置

**原因**：`dlq.redeliver` 工具需要通过 HTTP 调用 RocketMQ Dashboard 执行死信重投，依赖配置项 `myxhs.ai.hitl.dlq-redeliver.url`。

**需要确认**：

1. `my-xhs-ai-app` 的配置文件（`application.yml` 或 Nacos 配置）中是否已设置：
   ```yaml
   myxhs:
     ai:
       hitl:
         dlq-redeliver:
           url: http://21.130.247.89:18081
   ```
2. 如果未设置，需要在 Nacos 中添加这个配置（或在本地 `application.yml` 中补充）

**当前症状**：如果该配置为空，`dlq.redeliver` 和 `mq.dlq_query` 工具会返回"管理通道未配置"错误。

**验证**：配置生效后，访问 `http://21.214.97.212:19020/actuator/env` 搜索 `dlq-redeliver`，应能看到值为 `http://21.130.247.89:18081`

---

### 需求 3【P1】日志检索白名单扩容

**原因**：`logSearch` 工具当前只允许检索 `my-xhs-nacos` 的日志（白名单限制），Agent 无法查 `my-xhs-order`、`my-xhs-inventory` 等业务服务的日志。

**需要确认/修改**：

1. 找到 `logSearch` 白名单配置项（可能在 Nacos 或 `application.yml` 中，key 类似 `myxhs.ai.tools.log-search.services` 或 `myxhs.ai.tools.log-search.files`）
2. 将白名单从 `[my-xhs-nacos]` 扩展为至少包含：
   - `my-xhs-order`
   - `my-xhs-inventory`
   - `my-xhs-payment`
   - `my-xhs-gateway`

**当前症状**：Agent 调用 `logSearch` 时返回"服务不在白名单: my-xhs-order（允许: [my-xhs-nacos]）"

**验证**：白名单更新后，提交诊断任务"查一下 my-xhs-order 最近的 ERROR 日志"，Agent 应能成功调用 `logSearch` 并返回结果

---

## 三、时间要求

- 需求 1 + 2：**今天内完成**（阻塞 E2E 验证）
- 需求 3：**本周内完成**（不阻塞核心链路，但影响 Agent 诊断能力）

---

## 四、完成后的通知

部署完成后请通知，我会立即验证：
1. AI 诊断台健康检查通过
2. 提交 DLQ 诊断任务，验证 Agent 能调用 `mqDlqQuery` + `dlq.redeliver`
3. 验证 `logSearch` 能检索业务服务日志
