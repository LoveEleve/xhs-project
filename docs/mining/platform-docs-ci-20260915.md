# 平台其余文档与 CI 真相（2026-09-15）

## 1. 独立文档复盘（root docs/）
- **observability-issues**：曾误判"SCG witness 盲区"，真根因是 apache-skywalking 插件在 optional-plugins 未移入 → 规则"链路缺失先查 optional-plugins"；Prometheus 自抓 target 写错机器（修后 16/16 UP）；Grafana ES 数据源只存用户名未存密码。
- **canal-issue**：Canal 1.1.7 与 JDK17 不兼容（进程在跑但不下发）→ 切 Kona JDK8；验收法=UPDATE t_note 后 10s 内 ES 同步。
- **traceid-es-issue**：LogstashEncoder 缺 `<includeMdcKeyName>` 致 ES 无 traceId 字段 → 15 模块补齐 3 个 MDC 字段。
- **skywalking-issue / oap-config**：agent 9.1.0 缺 springmvc-6.x（jakarta）插件；OAP 需把 MQ 失败从 HTTP 错误率剔除（否则 home 显示 90% 错误）；sw_segment 无 TTL 已 179 万条 → 加 recordDataTTL 3d/ILM。
- **FINAL-HANDOFF / HANDOFF**：agent 四目录加载语义 + 必移 5 插件；两条撤回误判（t_order 缺失实为分片正常；OAP 改 CST 会污染 time_bucket）；Gateway+JWT+HMAC 全量重测 147/147；SC2023.0.1 LoadBalancer hashCode NPE → 全部 Feign URL override。

## 2. review-1 / review-method（审查方法论，面试"怎么保证质量"）
- **运行态实证 5 例**：F-007 多 SKU 只回补首个、F-010 退款回调 status=0 仍返回 success、F-016 remain_count=0 仍发券、F-039 停 product 后 home 200+业务 404、F-009 退款补偿每 3 分钟重复处理 5 条。
- **三条根因模式**：回调成功语义丢失 / 衍生状态覆盖权威状态 / 测试只验接口不验收敛；6 类测试盲区（mock 副作用、只看 HTTP、不注入故障、不跑补偿、不核对 DB/Redis/MQ/ES、不测多 SKU/长窗口）。
- **口径分层**：14 确认真 bug + 8 待运行态量化 + 18 安全项不进主线；主线 22 项（P0 4/P1 10/P2 8）。
- **review-method**：无 SOP 时人工审查仅覆盖 25-50%、必须 Agent 全量扫描；"49/49 全修复"里查出 3 个假修复；5 原则（不信任"已完成"/清单驱动/可执行命令/file:line 锚点/单一权威）；假修复 7 类（编译+运行双验证、注释冒充、非原子称原子、合并只修一个实体、假测试、缺表实测…）；常见坑（`@RequiredArgsConstructor+final` 阻断注入、`update(null,wrapper)` 不触发 MetaObjectHandler、空 token 绕过、fence 不门禁=装饰性幂等、滞后 MySQL 回填覆盖 Redis 权威）。

## 3. CI 与构建真相（重要，避免面试吹错）
- **GitLab CI 大半是装饰性**：声明 JaCoCo 产物与覆盖率正则，但**全仓无 jacoco 插件**（覆盖率门禁不生效）；spotbugs `allow_failure: true`；build/deploy 仅 main 且 `when: manual`，deploy 用 `kubectl set image || echo 跳过` 吞失败。
- ai-eval-gate：真库（只读账号 myxhs_ai_ro）+ 真模型、凭据 Masked、1h 超时；无凭据自动跳过不阻塞。
- GitHub workflow：仅 xhs-ai 模块跑单测（15 个业务服务无 CI 覆盖）。
- Enforcer 只在 xhs-ai/pom.xml（Java17/重复类/依赖收敛，豁免 MCP json 包），**根 POM 无 enforcer/jacoco/failsafe**；my-xhs-bom 实际无人 import（版本管理名存实亡）。
- 结论口径：简历/面试提"CI 门禁"只对 AI 项目说（本地 gate + GH 单测）；平台侧讲"测试矩阵靠人工/脚本执行，CI 未硬门禁"。

## 4. 未引用文档补充（test-2 / test-4）
- **缓存策略矩阵**：user 双删 / product 逻辑过期 / content·counter·coupon Cache Aside / inventory TCC / counter Buffer；布隆 100 万/1% 只增不减（删除不清理）；库存 UPDATE 跳过缓存失效=回声保护。
- **故障矩阵 RTO**：MySQL 主库 5-10min、Redis 1-2min、Sentinel 30-60s、Broker 1-3min、网关崩溃 <1min 全站不可达；直说"多数服务无 Redis 重试，切换窗口直接失败"。
- **fresh-eyes 全项目 Review**：P0 四连（券核销死代码可反复减额、IM 会话 ID 非单射 440 万对碰撞、Feed 区间颠倒恒空、本地消息必双投）；横切缺口（MDC 无 userId、异步丢 traceId）；可作范本的实现（分桶+Lua+Outbox、Set 化抗乱序、时间戳 CAS、事件溯源+乐观锁、双层线程池）。
- **覆盖审计**：假测试实证（testCompile 失败被跳过、修后 common 53+user 14 全绿）；cart 34 文件全读 6 项 P0/P1（清空非原子、对账锁三坑、Consumer 先查后写等）。
