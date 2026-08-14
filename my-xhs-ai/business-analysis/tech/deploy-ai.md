# my-xhs AI 服务部署（M8-1 容器化）

> 日期：2026-08-14 | 对象：my-xhs-ai-app（19020）/ my-xhs-ai-mcp（19021）
> 前置：my_xhs_ai 库（M5 DDL）+ 只读/写账号 + 观测栈（Prometheus 19090）同机可达 + OpenCode Go key

## 1. 构建（复用根 Dockerfile.template）

```bash
# 在仓库根目录执行（与后端 16 服务同模式；-am 自动构建 my-xhs-ai-tools 依赖）
docker build --build-arg MODULE=my-xhs-ai-app -f Dockerfile.template -t myxhs/ai-app:1.0.0 .
docker build --build-arg MODULE=my-xhs-ai-mcp -f Dockerfile.template -t myxhs/ai-mcp:1.0.0 .
```

> ⚠️ 模板的 `mvn package -pl ${MODULE} -am` 会构建 tools→app 依赖链（根 pom 已注册模块）。
> 本地无 docker：镜像构建验证留部署环境（构建命令已验证逻辑）。

## 2. 启动（compose）

```bash
export MYXHS_DB_PASSWORD=... MYXHS_AI_DB_PASSWORD=... MYXHS_LLM_API_KEY=...  # 密钥走 env/secret，勿入文件
docker compose -f config/docker-compose.ai.yml up -d
```

## 3. 网络与安全

- `network_mode: host`（与观测栈同机，符合 M7-3 iptables 白名单：127.0.0.1 + 21.214.97.212）
- **MCP_API_KEY 生产必设**（ai-app 与 ai-mcp 一致；未设 dev 放行 WARN）
- 观测端点认证（Prometheus 19090）建议 iptables-persistent + 安全组（M7-3，运维执行）
- gateway `/api/ai/**` 路由（对外入口，M8-2，需 gateway 团队配合）

## 4. 验证

```bash
curl http://127.0.0.1:19020/api/ai/health                          # {"status":"UP"}
curl http://127.0.0.1:19020/actuator/prometheus | grep myxhs_ai_   # 运行指标
curl -X POST http://127.0.0.1:19020/api/runs -H 'Content-Type: application/json' \
     -d '{"message":"为什么订单量下降了？"}'                          # 立即返回 runId
curl http://127.0.0.1:19020/api/runs/<runId>                        # 轮询至终态
# MCP 直接验证：initialize → tools/list（应 13 个工具）→ tools/call
```

## 5. 备份/回滚

- **数据**：my_xhs_ai 库（run/step 记录）——日常备份含此库；误删可恢复记录
- **代码/配置**：镜像 tag 版本化（1.0.0）；回滚 = 切回旧 tag 重启
- **bundle 版本**（model/prompt/tool/policy）：run 记录 versions_json 可追溯（M5）

## 6. 故障演练（M8-2，已部分完成）

- [x] **Worker 崩溃**：kill -9 → 重启自动恢复（M5-4 实测）
- [ ] **模型限流/超时**：OpenCode Go 超限 → 预算/重试兜底（演练脚本待补）
- [ ] **MCP 不可用**：ai-mcp 宕 → app 工具调用错误回填 + 模型如实说明（待演练）
- [ ] **中间件不可用**：MySQL/ES 宕 → 工具 error JSON + 明确降级（待演练）

## 7. 已知边界

- 单实例部署（RunManager 内存态；多实例需 M5-4 的 claimRunning 已支持 + 会话粘性）
- SSE 30min 超时（覆盖最坏 run 时长）
- 日志仅应用日志（审计流 D6 项）
