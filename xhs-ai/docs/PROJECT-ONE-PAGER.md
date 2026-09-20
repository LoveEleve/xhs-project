# xhs-ai 一页速览（面试用）· 冻结版 v1.1 · 2026-09-15

> 定位：**企业内部流程 Agent（运维诊断/工单/审批方向）+ 评测基础设施**——招聘需求最集中的两条线（评测 55.7% / Agent 51.9%，Agent 岗 68.4% 要求评测）
> 座右铭：生产型 Agent 关心的不是"能不能跑通一次"，而是"失败时会发生什么、花多少钱、能不能复现、谁批准危险操作"

## 一、核心数字（每个均有口径+原始数据）
| 指标 | 数值 | 证据 |
|------|------|------|
| 故障定位 | 10/10 案例完整，均值 1.63min，保守降幅 92.1% | `reports/mttr-benchmark-20260913.md` |
| 知识问答 | KB hit@1=100%；答案级 kb29/30+diag15/15+sec5/5（blocked=0）；引用校验拦幻觉 id | `reports/kb-eval-*.md`、`answer-eval-{kb,diag,sec}-20260920.json` |
| 工具评测 | 选择 12/12；轨迹稳定性 0.917（4 例×3） | `reports/trajectory-eval-20260915.md` |
| 成本 | 单次诊断 18,292/1,433 tokens ≈¥0.012–0.048；按用户日预算（软切/硬限 429） | `reports/cost-week-*.md` |
| 安全 | 红队 8 项全拦截；审计哈希链可校验；RBAC（JWT role） | `reports/production-gaps-20260915.md` |
| 规模 | 15 微服务/8 中间件；**20 个全自研工具（运维16+业务4）**，MCP 20 仅运维直连 | README |

## 二、七个可讲的故事（问题→根因→修复→证据）
1. **重投核验是死代码**：DLQ 属性存的是 broker 物理 ID，而代码比对客户端 uniqId，永不相等 → 双 ID 匹配（RV18）
2. **消费位点假阳性**：跨 topic 汇总位点 != 本消息已消费 → 消息队列级 `queueOffset` 核验（RV18）
3. **"注册成功 ≠ 可用"**：AgentScope 对无只读注解的 MCP 工具默认 ASK，非交互 API 永久挂起 → 权限模式修正 + **工具级 E2E 探针门禁**（RV19/23）
4. **框架不支持工具热替换** → 架构解法：**去 MCP 化**（20 工具全自研，MCP 已旁路；死进程 15.1s 快速失败/1.2s 重建）（RV27 + 2026-09-20 复核）
5. **自愈误判引发重启风暴**（NRestarts=24）→ 全进程扫描 + 三连击 + 默认只告警（RV19/23）
6. **工具参数面向 LLM 设计**：裸 DSL/PromQL → 业务级工具，P95 题 98s 答偏 → 50s 答对；A5 门禁沉淀（RV21/22/25）
7. **生产化收口**：token 预算/并发护栏/保留清理/7 条告警/RBAC/审计哈希链/请求幂等/会话摘要（RV24/27/29）

## 三、面试自测 8 问（答不来就回手册对应节）
1. 为什么用 Agent 而不是确定性工作流？（Anthropic《Building effective agents》+ 你的边界）
2. 评测怎么做的？轨迹/稳定性/judge 偏差？（§T′ + trajectory-eval 报告）
3. 危险操作为什么不会失控？（HITL 挂起 + 审计哈希链 + RBAC + 红队）
4. 工具怎么设计的？为什么自研不 MCP？（§T′/U′）
5. 上下文怎么管？（摘要/重建/截断，边界在哪）
6. 成本怎么控？（预算三段 + 计量来源 + 数字）
7. 崩溃/重启会发生什么？（F7、审批恢复/回收、幂等）
8. 你们哪里没做好？（**主动列**：Prompt 版本化/契约测试/流水线/多租户/DLP/judge kappa ——并给出 Roadmap）

## 四、剩余 Roadmap（面试话术：没做但要能讲）
Prompt 版本化 + 金标回放 → 工具/契约回归测试进 CI → Docker 镜像与发布流水线 → 多租户隔离/DLP → LLM-judge kappa 人工抽检

## 五、常用入口
- 手册：`docs/interview-defense-handbook.md`（18 主题 + 附录 A~U′）
- 简历口径：`xhs-ai/docs/resume-and-metrics.md`
- 门禁：`bash xhs-ai/scripts/gate.sh`（单测+审计+哈希链，可选评测）

## 2026-09-20 增补（最新口径，覆盖上文旧数字）
- **业务双场景**：新增 4 个业务只读工具（order_trace 跨五域+分片同哈希 / order_stats 16 分片聚合 / inventory_query / coupon_query），工具总数 **20**；业务库仅 SELECT 授权；实测抓修 2 真 Bug（LocalDateTime 序列化、无 id 列 SQL）。
- **模型网关演练**：注入无效主模型 → 重试→备用 5/5 不中断、3 败熔断 60s、半开探测、降级 4.5s；预算硬拒 agent 429/0.2s；**chat 路径预算绕过真 Bug 已修**（429/0.3s）。
- **摘要与恢复**：maxTokens 800→4096、block 60s→180s 两处真 Bug 修复；清 Redis 后 18.1s 三事实全中（session.rebuild 审计）。
- **评测三批**：kb 29/30（96.7%，1 例模型波动）+ diag 15/15（修正过时用例后）+ sec 5/5；门禁脚本 `ai-eval-gate.sh` 失败重跑一次，`ci-gate.sh` 可选接入。
- **口径作废**：旧"kill MCP 19s/39s 自愈/NRestarts=24"为去 MCP 化之前口径，不再引用。