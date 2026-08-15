# M8-3 gateway 接入方案（交付给 gateway 团队）

> 日期：2026-08-15 | 状态：**方案就绪，待 gateway 团队实施**（外部依赖）
> 归属：M8 部署收尾 | 前置：M8-4 UI 薄壳 + M9-1 受控日志检索已完成

---

## 1. 目标

把 AI 诊断台（前端 `/ai` + 后端 app:19020）纳入统一网关（:19000），与既有 16 服务一致：
统一鉴权、角色隔离、审计入口。

## 2. 路由配置（gateway application.yml 片段，可直接落）

```yaml
spring:
  cloud:
    gateway:
      routes:
        # AI 诊断台静态资源（前端 dist，SPA fallback 到 index.html）
        - id: ai-web
          uri: http://ai-web-host:80
          predicates:
            - Path=/ai/**
          filters:
            - RewritePath=/ai/(?<seg>.*), /$ {seg}
            - TokenRelay=
        # AI 后端 API（app:19020）
        - id: ai-api
          uri: http://my-xhs-ai-app:19020
          predicates:
            - Path=/api/ai/**,/api/runs/**
          filters:
            - TokenRelay=
```

> 说明：`/api/runs/**` 是 M5 异步 Run 端点（POST 提交/GET 查询/DELETE 取消/SSE 订阅），
> SSE 长连接请确认网关不缓存、不缓冲（禁用 response buffering，超时 ≥ 31min 覆盖 SSE 30min 上限）。

## 3. 角色与权限（L1 运营 / L2 技术）

| 端点 | L1 运营 | L2 技术 | 说明 |
|------|:--:|:--:|------|
| `/api/ai/query` | ✅ | ✅ | 指标查询 + 归因调查 |
| `/api/runs` POST/GET | ✅ | ✅ | 诊断任务（只读调查） |
| `/api/runs/{id}` DELETE | ❌ | ✅ | 取消任务（L2 控制面） |
| `/api/runs/{id}/stream` | ✅ | ✅ | SSE 订阅（实时过程） |
| `/api/ai/rag/**` | ✅ | ✅ | 口径问答（引用） |
| `/actuator/**` | ❌ | ✅ | 指标/健康（或走内网白名单，不经网关） |

角色映射建议：复用现有网关角色体系（`X-User-Role` 头），L1=`OPERATOR`，L2=`TECH`。

## 4. 安全要点（与 AI 侧既有防线叠加）

1. **AI 侧无额外登录态**：app/mcp 依赖网关 TokenRelay 转发身份；直接访问 app:19020 仅限内网（iptables/安全组）
2. **MCP（19021）不暴露公网**：MCP 是 AI 内部工具通道（认证 MCP_API_KEY），只允许 app→mcp 内网调用
3. **SSE 与单消费者**：同一 run 仅一个活动订阅者（409），网关需透传状态码不吞错误
4. **CORS**：前端同源（经网关 /ai 与 /api 同域），无需跨域配置
5. **鉴权失败**：401 返回标准错误体（前端 client 拦截器已处理跳登录）

## 5. 验收清单（gateway 团队实施后自测）

- [ ] 未登录访问 /api/ai/query → 401
- [ ] L1 角色 DELETE /api/runs/{id} → 403；L2 → 200
- [ ] `curl -N /api/runs/{id}/stream` SSE 事件流正常（无缓冲/超时截断）
- [ ] 前端 /ai 页全流程可用（提交→SSE→终态）
- [ ] 生产 MCP_API_KEY 已设置（未设时 WARN 放行仅限 dev）

## 6. 前端联调说明

- 前端 API 基础路径 `/ai-api`（vite dev 代理→19020）；**经网关后改为 `/api/ai` 前缀**
  （网关 RewritePath 或前端构建时配置 `VITE_API_BASE`）
- 生产构建产物：`frontend/dist`（静态托管，SPA fallback 必须指向 index.html）
