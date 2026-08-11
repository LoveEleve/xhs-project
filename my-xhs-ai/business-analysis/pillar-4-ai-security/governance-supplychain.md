# 模型治理、供应链与合规（Governance / Supply Chain）

## 风险
- 模型 Provider：数据保留、训练使用、地域、密钥管理不可控。
- 供应链：Agent SDK、MCP SDK、依赖被投毒/含漏洞。
- 合规：EU AI Act 2026-08 生效，需按部署地区/角色/风险分类评估。

## my-xhs 设计（治理基线）
1. **模型 Provider 治理**：Provider 数据保留/训练使用/地域/密钥管理写入模型 ADR；Provider 可用性与密钥走配置中心。
2. **依赖与供应链**：
   - 版本锁定 + 兼容性报告（PLAN 已强调引入前重新核验）。
   - 依赖扫描（SBOM）、密钥扫描进入 CI。
   - 不用未核验的 star 数/营销文代替 ADR 与实测。
3. **发布门禁（release bundle）**：模型 + Prompt + 工具 + 检索索引 + 策略打包为**不可分割、可回滚**的 bundle；每次运行可追溯各组件版本。
4. **版本可追溯**：code/model/prompt/dataset/tool/index/policy 全版本化，评测记录机器可读。
5. **合规评估**：EU AI Act 按部署地区、角色、风险分类评估；**不默认所有 Agent 都是高风险系统**；技术清单 ≠ 法律结论。

## 验收
- 关键契约测试 + 权限测试 + 密钥扫描全绿才允许发布。
- 新 release bundle 可一键回滚到上一版本。
- 每次运行可回溯 model/prompt/tool/retrieval/policy 版本。
