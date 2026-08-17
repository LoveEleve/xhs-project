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
        # AI 诊断台静态资源：AI 前端在主前端 my-xhs-frontend 的 dist 内（/ai 路由），
        # 由主前端静态托管统一负责（SPA fallback 到 index.html），无需独立 ai-web 路由
        # AI 后端 API（app:19020）——前端 baseURL=/ai-api，请求形如 /ai-api/api/runs；
        # RewritePath 去 /ai-api 前缀（与 vite dev proxy 一致，2026-08-16 修正）
        - id: ai-api
          uri: http://my-xhs-ai-app:19020
          predicates:
            - Path=/ai-api/**
          filters:
            - RewritePath=/ai-api/(?<seg>.*), /${seg}
            # 网关自研 JWT 鉴权后注入 X-User-Id（AI 后端已改造读取 header 优先，2026-08-16）
            # （非 Spring OAuth2 TokenRelay——gateway 无 oauth2 依赖）
```

> 说明：前端请求 `/ai-api/api/runs` → RewritePath 去前缀 → 后端 `/api/runs`（与 vite dev proxy 的 `rewrite: path.replace(/^\/ai-api/,'')` 一致）。
> `/api/runs/{id}/stream` SSE 长连接请确认网关不缓存、不缓冲（禁用 response buffering，超时 ≥ 31min 覆盖 SSE 31min 上限）。

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

- 前端 API 基础路径 `/ai-api`（vite dev 代理→19020；**生产经网关同样走 /ai-api 前缀 + RewritePath 去前缀**——与 dev 一致，无需前端构建期改 `VITE_API_BASE`，2026-08-16 修正）
- 生产构建产物：`frontend/dist`（**AI 诊断台在主前端内**，/ai 路由同托管，SPA fallback 指向 index.html）
