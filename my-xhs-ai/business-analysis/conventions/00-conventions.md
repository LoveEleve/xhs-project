# 业务全局约定（跨支柱）

> 供 AI 项目统一理解所有业务数据/指标/接口的**口径与可信边界**。任何工具/指标设计都必须遵守本约定。

---

## 1. 通用响应与错误格式

所有后端接口统一包装（Gateway 之后）：
```json
{ "code": 200, "message": "success", "data": {...} }
```
- `code === 200` 成功；非 200 时 `message` 可直接给用户/AI 展示。
- 分页：`{ data: { records:[...], total, size, current } }`。
- ⚠️ **分页参数名不一致**：`pageNum/pageSize`（product、note 列表）vs `page/size`（多数）vs **订单列表不分页**（直接数组）。
- ⚠️ **收藏/关注/粉丝列表**返回 `{total, list:[...]}`（ID 或 VO），不是标准分页对象。

## 2. 鉴权与身份传播

| 机制 | 用途 | 说明 |
|------|------|------|
| JWT | 用户端 | 登录得 `accessToken`(30min)+`refreshToken`(7d)；单设备登录，旧 token 进黑名单 |
| X-User-Id | 用户身份 | Gateway 认证后向下游注入；下游据此归属校验 |
| X-Admin-Call | 管理端点 | `my-xhs-admin-token-2026`（空默认=fail-closed） |
| X-Internal-Call | 服务间 Feign | `my-xhs-internal-token-2026` |

> **对 AI**：管理/内部端点不应暴露给 AI 工具；AI 工具只调用只读业务接口，且必须通过固定工具（见 00 安全边界）。

## 3. 时间与时区

- 存储：MySQL `datetime` / `LocalDateTime`（Asia/Shanghai）。
- 指标口径默认 **Asia/Shanghai 时区**；所有时间窗必须显式声明 `zone` + `asOf`（数据生成时间）。
- 业务时间 vs 统计时间要区分：如笔记 `created_at`（创建）≠ 发布/审核时间（缺独立字段）。

## 4. 指标口径规范（AI 工具必须遵守）

每个指标返回必须含（对齐 PLAN 的 tool 结果契约）：
```json
{
  "status": "ok",
  "metric": "payment_success_rate",
  "definitionVersion": "payment-success-rate/v1",
  "window": {"from": "...", "to": "...", "zone": "Asia/Shanghai"},
  "asOf": "...",
  "value": 0.973,
  "dimensions": {},
  "source": "payment_daily_summary",
  "quality": {"complete": true, "warnings": []},
  "traceId": "..."
}
```
- 数字必须由**确定性工具**产生，AI 不编造、不推断具体数值。
- 无法回答"为什么"时，如实声明"只有现象，缺原因数据"。

## 5. 通用业务不变量（跨支柱可信边界）

1. **状态机单向流转、不可逆**：如订单 0→1→2→3、支付 0→1→2、笔记 草稿→发布→下架。
2. **乐观锁保证并发**：`UPDATE ... WHERE status=预期值`，affected=0 即并发冲突 → 只成功一个。
3. **幂等键**：Redis SETNX 防重复（MQ 消费/领券 claimNo/下单 requestId/pseudoOrderId）。
4. **MQ 消费幂等**：`msg:{msgId}` 24h 去重；失败 removeMark 后重试（防重试窗口归零）。
5. **计数异步**：互动计数经 MQ 异步更新，Redis 权威、MySQL 兜底、对账修正。
6. **缓存一致性**：写库后立即读需 `@Transactional` 走主库；缓存用延迟双删/逻辑过期。

## 6. 数据源与只读边界（业务 + 观测）

### 6.0 ⚠️ 权威端口表（以 application.yml 实测为准）
> 项目内多份文档端口**互相矛盾**（test-2/HANDOFF 与 business-domain 不一致）。**以下为实测权威值**，与本套文档一致；引用其他文档时以此为准。

| 服务 | 端口 | | 服务 | 端口 |
|------|:--:|-|------|:--:|
| gateway | 19000 | | cart | 19008 |
| user | 19001 | | inventory | 19009 |
| content | 19002 | | coupon | 19010 |
| analytics | 19003 | | order | 19011 |
| counter | 19004 | | payment | 19012 |
| (19005 未用) | — | | notification | 19013 |
| product | 19006 | | im | 19014 |
| (19007 未用) | — | | home | 19015 |
| | | | search | 19016 |

> ⚠️ **test-2/HANDOFF 文档里的 order=19005 / payment=19009 / inventory=19010 / coupon=19007 是错的**；`business-domain.md` 与实测一致。引用时勿照抄 test-2 端口。

### 6.1 业务数据源
| 数据源 | 内容 | 权限 |
|--------|------|:---:|
| MySQL(13306-09) | 各业务 t_* 表 | 只读（经固定工具，非 LLM 直连） |
| Redis(16380/81) | myxhs:* 业务/缓存/计数 | 只读 |
| ES(19200) | note/product 索引 | 只读 |

> ⚠️ **口径冲突**：`../../docs/reference/business-domain.md` 给了"所有 t_* 表 SELECT"直连清单，与 PLAN.md"不让 LLM 直连库"矛盾。**本套文档默认按"固定只读 MCP 工具"设计**（符合 PLAN 安全原则），详见 `../overview/plan-review.md`。

### 6.2 观测数据源（O&M 排障，权限更敏感）
| 数据源 | 内容 | 权限 |
|--------|------|:---:|
| Prometheus(19090)/VM(8428) | JVM/GC/线程/连接池/MQ 位点 | 只读 PromQL |
| SkyWalking OAP(11800/12800) | 链路/拓扑/慢端点/SQL耗时 | 只读 |
| ELK(ES 19200/Kibana 15601) | myxhs-logs-* 日志 | 只读 |
| XXL-Job(18080) | 定时任务执行状态 | 只读 |
| MySQL 诊断 | `SHOW ENGINE INNODB STATUS` / slow_query / `SHOW SLAVE STATUS` | 只读诊断 SQL |
| RocketMQ Dashboard(18081) | 消费位点/死信 | 只读 |

### 6.3 工具权限分级（O&M 必须遵守）
```
L1 业务工具（只读领域 API）—— 普通运营权限
L2 观测工具（PromQL / ES DSL / 诊断 SQL / trace）—— 排障权限，更敏感，审计更严
L3 运维动作（重启 / 重投消息 / 回滚 / 改配置）—— V1 默认拒绝，需人工审批
```
> 观测工具不是"运维动作"：能查死锁/慢查询/积压，但**不能**重启服务/重投/改配置。V1 只开放 L1+L2 只读，L3 一律走人工。

## 7. 数据就绪度判定标准

- ✅ 就绪：有持久化结构、口径清晰、可确定性查询。
- ⚠️ 部分：有数据但缺关键字段/需汇总投影/权威在易失存储（Redis）。
- ❌ 缺口：无数据支撑"为什么"类问题。
