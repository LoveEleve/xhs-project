# RV16：全维度深度 Review（M3/M4 后）

> 日期：2026-09-13 ｜ 方法：4 路并行（文档与证据 / 代码第三轮 / SRE 运行态 / 跨项目与简历）
> 结论：**发现并修复 8 个 P1**（含会话 IDOR、网关熔断/降级缺陷）；简历口径完成回写；M4 剩余证据（答案级评测/成本周/压测/FMEA）挂账明确。

---

## 1. 代码（本轮修复）

| # | 级别 | 问题 | 修复 |
|---|------|------|------|
| 1 | P1 安全 | **会话 IDOR**：sessionId 客户端可指定且与 userId 无绑定，Redis AgentState 可跨用户续聊 | 归属校验（`ai_session` 反查，越权 400）+ Redis 状态键改 `userId:sessionId` |
| 2 | P1 正确性 | 熔断成功后不归零、无半开：阈值仅首轮生效 | 主通道成功即清零；冷却结束半开放行探测 |
| 3 | P1 正确性 | 已出流后 failover → SSE 重复增量 | `PartialStreamException` 不再降级重放，直接报错（测试固化） |
| 4 | P1 正确性 | MCP 工具名缓存永不过期 + 空集缓存 → 工具增删后永久误判 | TTL 10min + 未命中失效重取一次 + 空集不缓存 |
| 5 | P1 正确性 | 知识重建只 upsert 不清理 → 下线卡片残留 | `reindex` 前 `_delete_by_query` 清空 |
| 6 | P1 安全 | `reindex/eval` 任意 JWT 可触发（资源滥用） | 仅 `X-Admin-Call`/`X-Internal-Call`；JWT 实测 403 |
| 7 | P1 安全 | `code_locate` 跟随符号链接（可读仓库外文件）、无截断标记 | 禁用符号链接 + `truncated` 标记 + query 截断 + relativize 路径 |
| 8 | P2 可观测 | 指标 model 标签恒为主模型；无 breaker 指标 | 按实际通道模型打标 + `ai_model_breaker_open_total` |

验证：**18/18 单测**（新增熔断归零用例）；运行态：IDOR 400、JWT→reindex 403、admin→200、code_locate `truncated` 标记生效。

## 2. 运行态（SRE 巡检）

| 项 | 结论 |
|---|------|
| 24h 自动重启 31 次 | 三根因：Redis Sentinel 瞬断（已有 6×5s 重试）、Nacos 瞬断、**多 @Primary Bean crash loop（已修）**；建议调大 `StartLimitIntervalSec` 并接入重启告警 |
| `%DLQ%*` 全量 | 零（历史三组已清账） |
| 会话/审计 | 54 会话 / 89 消息；审批 18 单全 approved；核验 17 条 |
| `/actuator/prometheus` | 对 RFC1918 免鉴权（19020 绑 0.0.0.0）——**挂账**：改 `management.server.address=127.0.0.1` 或防火墙白名单（Prometheus target 同步改 127.0.0.1） |
| MCP 进程 | ES 存在 2 组实例（旧实例未回收，P2 挂账）；prometheus 双实例已改随机端口无冲突 |
| 熔断指标/延迟 max=0 | 本轮已补 breaker 指标；latency max 失真为 P2 挂账 |

## 3. 文档与简历回写（已完成）

- MTTR：简历与 README 回填 **9/10 案例、Agent 1.58min、保守降幅 92.3%**，并注明人工侧为重构口径；原始数据指向 `mttr-raw-20260913/`；
- 工具口径统一：**4 域 28**（7 自研 + ES 4 + Prom 17 白名单）；删除"（LSP）"表述；
- 模型网关由"M2.x 计划"改为"已交付（重试/熔断/降级/指标）"；
- A 级卡片 54 张入库 + **KB 检索 hit@1=100%** 列为已达成；红队 6 类计划 → **8 项实测**；
- 成本保留"样本估算"口径，删除"降 60%+"（待成本周）；
- README/索引：版本 v0.4、eval/ 与 mttr-raw 入索引。

## 4. 挂账（M4 剩余，按优先级）

1. **答案级评测与引用校验**（解锁"准确率 90%+/引用 100%"）：补 DIAG15/SEC5 用例与 citation 校验产物；
2. **成本周**：N=100 网关计量 + 官方费率表 + 全量上下文对照（解锁"<¥0.1"与降本比例）；
3. **压测**：会话并发 N≥100，P50/P95/P99 CSV；
4. **FMEA 演练**：4-6 项（Redis failover/停 ES/kill MCP/滚动重启）落报告；
5. 红队脚本化（`scripts/red-team.sh` + 审计 SQL）；
6. 运行态杂项：prometheus 端点 loopback、MCP 实例回收、延迟 max 失真、Nacos 日志降噪。
