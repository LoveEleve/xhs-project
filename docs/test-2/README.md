# my-xhs 文档地图（docs/test-2）

> 2026-08-12 整理 | 本目录为项目全部工作文档。**当前唯一入口：HANDOFF-TASK4.md**。
> 旧文档已归档至 `_archive/task2-era/`（Task2 时代交接/review/测试计划，代码已大改，仅历史参考）。

---

## 一、当前有效文档（按阅读顺序）

| 顺序 | 文档 | 内容 | 状态 |
|:--:|---|---|---|
| 1 | **HANDOFF-TASK4.md** | 当前交接（生产配置深挖+云部署改造+全部待办）| ✅ 有效（更新至 2026-08-12）|
| 2 | **HANDOFF-TASK3.md** | Task3 交接（代码审查+修复闭环背景）| ✅ 有效（背景参考）|
| 3 | **review-fresh/review-consolidated.md** | 代码 fresh review 汇总（P0/P1/P2/O1/O2 + 修复进度表）| ✅ 有效 |
| 4 | **review-fresh/review-production-config.md** | 生产配置/架构 review（P-D1~D42 + P-T1~T5 + P-B1~B4 + A1~A10 + 二十轮深挖）| ✅ 有效（最全）|
| 5 | **review-fresh/review-<module>.md** | 15 个模块逐篇 review | ✅ 有效（15 篇）|
| 6 | **FIX-PLAN-PRODUCTION-CONFIG.md** | 生产配置修复方案（分批次）| ✅ 有效（多数已执行，见 §十五/§二十）|
| 7 | **DEPLOY-CLOUD-GUIDE.md** | 中间件云主机部署操作清单 | ✅ 有效（部署包配套见 `config/deploy-cloud/DEPLOY-README.md`）|
| 8 | **TEST-REFERENCE-V2.md** | 测试参考（端点/覆盖基准）| ✅ 有效 |
| 9 | **execution/pitfalls.md** | 踩坑记录（#1~#78，含修复验证与教训）| ✅ 有效（持续追加）|
| 10 | **methodology/** | 测试方法论（TEST-METHODOLOGY + L2/L3 补充）| ✅ 有效 |
| 11 | **execution/README.md** | 测试执行索引 | ✅ 有效 |

## 二、代码理解参考（标注"可能过时"）

| 目录 | 内容 | 注意 |
|---|---|---|
| `service-analysis/` | 16 服务代码分析（120+ 文件，01-user ~ 16-gateway）| ⚠️ 已标注（目录内 README.md）：代码经多轮修复，**仅作架构理解参考，结论以 review-fresh 为准** |
| `business-docs/` | 业务设计文档（212 文件，按模块）| ⚠️ 已标注（目录内 README.md）：接口约定可能已变更，**以代码为准** |
| `engineering-docs/` | 工程实践文档（9 文件）| ⚠️ 已标注（目录内 README.md）：部署/监控/安全类已过时，按文件对照最新结论 |
| `_archive/task2-era/` | 旧交接/旧 review/旧测试计划（12 文件 + plans/）| 仅历史存档 |

## 三、部署/配置相关（代码库内，非 docs）

| 位置 | 内容 |
|---|---|
| `config/deploy-cloud/DEPLOY-README.md` | **云部署说明（上传清单/前置/EIP/部署步骤/脚本清单）** |
| `config/deploy-cloud/*.sh` `*.sql` | 部署后运维脚本（apply-ilm/mysql-backup/init-xxljob/ops-fixes/remote-upgrade）|
| `config/nacos/` | Nacos 3 配置固化文件 |
| `config/docker-compose.yml` | 25 容器编排（最终版）|
| `my-xhs-deploy-package.zip` | **最终部署包（云主机部署源）** |
| `setup-firewall.sh` | 主机防火墙脚本（已对齐当前端口）|

## 四、当前状态速览（2026-08-12）

- **中间件**：试验机 25 容器部署成功（对方修复 8 处部署包 bug 已合并回本地）
- **微服务**：15 个本机运行（新代码：P0/P1/P2 全修 + P-B4/P-D32/39/42 + loggers 关闭）
- **代码问题**：全部清零（含 P2 批量修复，测试脚本待跑回归）
- **待办**：① 全链路回归测试（脚本已修复就绪）② 云主机部署 ③ 微服务迁 Ubuntu VM
- **用户明确不搞**：Nacos 鉴权/密码随机化/告警渠道/Sentinel 口令/HA 增强
