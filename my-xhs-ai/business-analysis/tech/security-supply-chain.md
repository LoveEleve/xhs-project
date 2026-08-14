# M7-3 供应链 / 密钥 / 观测认证（安全收尾）

> 日期：2026-08-14 | 归属：M7 安全实证后半段 | 对照：OWASP LLM03/08、PLAN §9 安全治理

## 1. SBOM（供应链，OWASP LLM03）

- **生成**：`my-xhs-ai-app` 已接 `cyclonedx-maven-plugin`（package 阶段自动生成 `target/bom.json`，CycloneDX 标准）
- **内容**：AI 模块全依赖清单（LangChain4j/MCP SDK/Spring Boot/Jackson/micrometer…）带版本
- **使用**：CI 或交付时归档；漏洞比对（NVD）可接 `dependency-check-maven`（CI 可选 job，慢）或第三方扫描（Trivy/Snyk）
- **AI 模块依赖锁定**：根 pom BOM 管理（Boot 3.2.5 / Jackson 2.16.1 / MCP 0.18.3 / LangChain4j 1.0.0）
- **待办**：`mvn -pl my-xhs-ai-app package` 后验证 bom.json 生成；依赖漏洞扫描进 CI（模板见下）

```yaml
# CI 可选 job：依赖漏洞扫描（慢，nightly 跑）
ai-dependency-scan:
  stage: test
  image: owasp/dependency-check:latest
  script:
    - mvn -pl my-xhs-ai-app dependency:tree -DoutputType=text > /tmp/deps.txt
    - /usr/share/dependency-check/bin/dependency-check.sh --project my-xhs-ai \
        --scan /tmp/deps.txt --format JSON --out target/dc-report.json
  artifacts:
    when: always
    paths: [ my-xhs-ai-app/target/dc-report.json ]
  only: [ schedules ]
```

## 2. 密钥管理

- **唯一来源**：`.env.local`（gitignored，已确认 `git check-ignore` OK）；别处出现即违规
- **历史扫描结果（2026-08-14）**：AI 模块全部提交历史 + 工作区**零密钥泄漏**（`sk-` 模式 + 变量赋值模式）
- **OpenCode Go key**：`MYXHS_LLM_API_KEY`（用户已确认无需轮换；若未来泄露，控制台 opencode.ai/auth 重置后更新 `.env.local`）
- **MCP_API_KEY**：生产必须设置（dev 未设放行 WARN——M8 部署项）；CI 用 Masked+Protected 变量
- **原则**：密钥不进代码/配置/文档；CI 变量 `Masked` 防日志泄漏

## 3. 观测端点认证（Prometheus/SkyWalking）

**现状**：`21.130.247.89:19090`（Prometheus）/ `:12800`（SkyWalking）无认证，监听 0.0.0.0；
iptables 仅放行 127.0.0.1 + 21.214.97.212（NEW 状态 DROP）——**公网不可达，但 iptables 未持久化**。

**建议（运维侧执行，已沟通）**：
1. `iptables-persistent` 保存规则 + 腾讯云安全组双保险（重启不丢）
2. AI 服务（app/mcp）与观测栈同机/白名单 IP 部署（M8）
3. 可选：Prometheus 加 `--web.external-url` + basic auth 或网络策略（K8s NetworkPolicy）

**AI 侧依赖**：观测查询全部经 `my-xhs-ai-mcp`（服务端到 Prometheus）——AI 服务不直接暴露观测访问面。

## 4. 验收对照（M7 全量）

- [x] 红队真库实证 3/3（注入泄漏/越权/PII，red-team-report-v1.md）
- [x] 密钥零泄漏（历史+工作区扫描）
- [x] SBOM 插件接入（待验证生成）
- [x] 观测端点认证建议文档化（运维执行）
- [ ] 依赖漏洞扫描 CI（schedules 模板就绪）
- [ ] SBOM 生成验证（`mvn package` 后确认 bom.json）
