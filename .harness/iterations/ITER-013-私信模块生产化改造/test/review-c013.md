---
iteration: ITER-013
status: confirmed
version: 1
created: 2026-10-19
updated: 2026-10-19
# 用途：C-013 深审查（清单先行 + 逐步思考）——未读一致性 × 投递可靠性 × 死物清理
---

# 深度审查: C-013（私信模块生产化改造）

## 审查清单（5 维 16 项，逐项结论）

> ✅ 通过 / 🟡 发现并修复 / 📝 记录

### A. 未读一致性（本轮核心）
- **A1 未读前移（F1）** ✅ —— InOrder 单测锁定（未读脚本→离线→路由→ACK）；幽灵窗口消除论证（READ 必然晚于推送）
- **A2 软删清零（D1）** ✅ —— SQL 注解断言防回归（`unread_count = 0`）；列表（DB）与总数（Redis）口径分裂根治
- **A3 连接重建（D2）** ✅ —— DB→Redis（空则删键）+ 30d TTL；**与并发新消息竞态**明示（窗口内可能少计 1，下次连接收敛——文档化）
- **A4 TTL 语义** ✅ —— unread 滑动 TTL（miss 收敛路径三重：重建/fallback/读写覆盖）；seq 有意无 TTL（重置破坏排序——演进方案记录）

### B. 投递可靠性
- **B1 ACK 必达（D3）** ✅ —— 后段三步全降级 + ACK 无条件（消息已持久化=DB 权威）；重发重复风险消解论证
- **B2 READ 降级（D4）** ✅ —— Redis 清零失败仍送达 READ_NOTIFY
- **B3 clientMsgId 幂等（F6）** ✅ —— 锁内 SETNX→msgId 映射；重放 ACK 原 msgId（不落库不路由）；失败删标记（家族 removeMark 契约）；不传=旧行为（向后兼容）
- **B4 at-least-once 链路回归** ✅ —— 先落离线→推→删/留守→ACK 三删（既有正确路径未动）；Subscriber 降级（仅 CHAT）

### C. 路由与连接
- **C1 register Lua（F2）** ✅ —— 单脚本（GET 旧+SET 新+TTL）；踢线依赖的旧值语义保留；{u:id} tag 单 slot
- **C2 上限豁免（F3）** ✅ —— containsKey 豁免（SSE 同型对齐）；getUserId 前移（null 先拒）
- **C3 踢线竞态三件套** ✅ —— 双参 remove + Lua 比较删除 + KICK（保留不动的正确路径，回归锁定）

### D. 工程与死物
- **D1 死物移除（F5）** ✅ —— LB（未接线）/isOnline+online 键（零消费者，全仓 grep）/isRead 死列（实体删、DDL 留注）——Lua 同步简化（unregister/renew 单键）
- **D2 注释 ×7（F4/D6）** ✅ —— ChatService/RouteMessage/ImMessageVO/ImController/MessagePersistService/ChatMessage/OnlineRouteService 全部对齐代码事实
- **D3 SQL 副本（D5）** ✅ —— 6 副本一致（conversation_id 注释 + idx_user_updated）；migration 修库不修基线的教训（C-010 同型再犯）沉淀
- **D4 限流** ✅ —— 4 端点注解（反射核验）

### E. 回归与证据
- **E1 测试集从零** ✅ —— 50 用例 / 9 类（ChatService 17 / OnlineRoute 5 / Handler 8 / Subscriber 5 / Handshake 5 / Controller 3 / Persist 4 / Mapper 3）
- **E2 十三模块复跑** ✅ —— **664 全绿**（im 50 首纳）

## 发现汇总（🟡 13 处，均已修复）

| # | 发现 | 类型 | 修复 |
|---|------|------|------|
| D1 | 软删会话僵尸未读（复活回归 + 口径分裂） | 真 bug | 软删 SQL 同置清零 |
| D2 | 未读双写无收敛（无对账 Job） | 系统性 | 连接时重建（轻对账）+ TTL |
| D3 | post-persist 无降级 → ACK 不保 | 可靠性 | 三步降级 + ACK 必达 |
| F1 | 幽灵未读竞态（+1 在推送后） | 竞态 | 未读前移 |
| F2 | registerRoute 非原子 | 竞态 | 单 Lua |
| F3 | 连接上限缺豁免 | 边界 | containsKey 豁免 |
| F4/D6 | 注释漂移 ×7 | 注释 | 全对齐 |
| D5 | SQL 副本漂移 ×2 | 基线 | 4 副本同步 |
| F5 | 死物（LB/isOnline/online 键/isRead） | 死代码 | 评估后移除 |
| D4 | handleRead Redis 失败中断通知 | 可靠性 | 降级 |
| F6 | 发送侧无幂等 | 幂等 | clientMsgId 可选 |
| §7-5 | 4 端点无限流 | 防护 | 补注解 |
| D7 | 小项（记录） | 观察 | 讲 |

## 结论

16 项全部闭环：未读一致性（前移/清零/重建/TTL）与投递可靠性（ACK 必达/幂等/降级）经单测锁定；死物与注释清零；SQL 副本统一。证据：十三模块 **664 全绿**（im 50 首纳）。
