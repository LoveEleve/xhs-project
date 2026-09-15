# 双项目主索引（地图）· 2026-09-15

> 用途：从"简历一句话/面试一个追问"快速定位到 **代码实现 / 证据文件 / 深挖素材**
> 配套：深挖素材 `docs/mining/platform-mining-20260915.md`、`xhs-ai/docs/mining/ai-mining-20260915.md`

## A. 项目一 · 电商平台（15 微服务）

### A1 代码地图（按能力）
| 能力 | 代码入口 | 深挖素材 |
|------|---------|---------|
| 库存三级扣减/TCC | `my-xhs-inventory/.../InventoryService.java`、`job/TccTimeoutJob.java`、`lua/prededuct.lua` | 平台 §1/§2 |
| 订单分片/事务消息 | `my-xhs-order/.../OrderService.java`、`ShardingSphereDataSourceConfig.java`、`lua`? | §1/§3 |
| 优惠券/购物车一致性 | `my-xhs-coupon/lua/claim_coupon.lua`、`my-xhs-cart/consumer/CartSyncConsumer.java` | §2 |
| Feed 推拉 | `my-xhs-home/.../FeedPushConsumer.java`、`FeedService.java` | §1/§2 |
| 搜索/推荐 | `my-xhs-search/.../HotSearchService.java`、`RecommendService.java` | §1 |
| IM/SSE | `my-xhs-im/.../ImConsistentHashLoadBalancer.java`、`my-xhs-notification/sse/SseEmitterManager.java` | §1/§2 |
| 网关/限流/HMAC | `my-xhs-gateway/filter/*`（8 过滤器） | §2/§5 |
| 对账/补偿/兜底 | `*/job/*ReconcileJob.java`、`LocalMessageRetryJob.java` | §4 |
| 可观测 | `my-xhs-common/metrics/*`、`config/TraceIdConfig.java` | §5 |

### A2 关键数字 → 证据
| 数字 | 证据 |
|------|------|
| TCC 11 场景 / 20 并发不超卖 / 75s 回查只扣一次 | 手册 §D/§E；简历项目一 |
| 26 消费组可见 / DLQ 清零 | 手册 §O；RV11/RV12 |
| RPS 基线 / 限流 500·300 / 6s 慢下游降级 | 手册 §L；简历项目一 |
| Redis failover 2.3s | 手册 §N |
| 117 测试矩阵 / 21 运行态修复 | 简历项目一；平台 §3（T-* 清单） |

### A3 面试深挖推荐 6 条（新挖）
1. 伪订单号 SHA-256 统一（原 fold-hash 溢出碰撞致库存永久泄漏）——平台 §2-1
2. ZSet 过期索引替代全库 SCAN + MySQL 幂等占位探测 —— §2-2/2-3
3. 补偿动作分派（不依赖订单状态）+ pending Set 重放 —— §2-5
4. Feed 断点续推 + 一次 Pipeline 双写修复召回恒空 —— §2-9/2-10
5. HMAC/X-User-Id 防伪造 + Feign 子上下文注入坑 —— §2-12/2-13/2-14
6. 指标"预注册空标签"防系列被吞 / URI 归一化防时间序列爆炸 —— §5

## B. 项目二 · xhs-ai（AI Agent）

### B1 代码地图
| 模块 | 入口 | 深挖素材 |
|------|------|---------|
| Agent 装配/提示词/工具注册 | `agent/AgentService.java:71-182` | AI §1/§2 |
| 16 个工具 | `agent/tools/*Tool.java`（清单见素材 §2） | AI §2 |
| 审批/HITL | `approval/*`（状态机/指纹/核验/恢复/事件总线） | AI §4 |
| 评测 | `eval/*` + `scripts/tool-eval.sh`、`gate.sh` | AI §6；`reports/trajectory-eval-*` |
| 模型网关/预算 | `model/ModelGateway.java`、`TokenBudgetService.java` | AI §3/§4 |
| 会话/摘要/重建 | `session/*`、`agent/AgentService.java:275-294` | AI §4 |
| 审计链 | `audit/AuditChain.java`、`AuditService.java`、`scripts/audit-verify.sh` | AI §4 |
| 幂等/并发/权限 | `web/IdempotencyService.java`、`agent/AgentConcurrencyGuard.java`、`security/AiRoleResolver.java` | AI §3 |
| 观测 | 17 个 `ai_*` 指标（素材 §5）、`alert_rules/xhs_ai_rules.yml` | AI §5 |
| 运维脚本 | `scripts/{gate,audit-gate,audit-verify,audit-check.sql,red-team,load-test,cost-week,traffic-gen,order-flow}.sh` | AI §6 |

### B2 关键数字 → 证据（同速览 `xhs-ai/docs/PROJECT-ONE-PAGER.md`）
MTTR 10/10·1.63min·92.1% → `reports/mttr-benchmark-*`；hit@1 100% / 答案级 50/50 → `reports/kb-eval-*、answer-eval-*`；工具 12/12、轨迹 0.917 → `reports/trajectory-eval-*`；成本 18,292/1,433 → `reports/cost-week-*`；哈希链/幂等/摘要 → `reports/production-gaps-*`；去 MCP 化/预算 → `reports/live-drill-*`、`reviews/19,23,27`

### B3 面试深挖推荐 6 条（新挖）
1. 精确配置数字：预算 50 万/软 80%、并发 2/8、幂等 600s、摘要 20/12000、审批 10min/600s —— AI §3
2. 17 个 ai_* 指标与语义（工具失败率/运行时长/预算决策/MCP 健康）—— AI §5
3. 提示词硬约束（工具 ≤4 次、参数报错禁重调、必须读卡）—— AI §1
4. 模型网关只对"传输类错误"重试、已出流不降级重放 —— AI §4
5. 诚实的"未接线"清单（跨实例事件无业务订阅者、capture_mode/ai_feedback 死列）—— AI §6
6. DLQ 尾部扫描边界（200/100 条）与 jdtls 未实现 —— AI §6

## C. 学习 / 准备入口
- 简历：`docs/resume-final.md`；学习对照：`docs/resume-study-map.md`
- 手册：`docs/interview-defense-handbook.md`（18 主题 + 附录 A~U′）
- 速览：`xhs-ai/docs/PROJECT-ONE-PAGER.md`
