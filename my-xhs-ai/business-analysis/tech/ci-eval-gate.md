# M6-4 PR 门禁 CI 接入模板

> 定位：把评测门禁接进 gitlab-ci（AI 模块专属 job）。当前 `.gitlab-ci.yml` 只有后端 16 服务构建。
> 触发方式：MR/PR 时跑 smoke 级门禁（快）；main 合并后跑完整门禁（nightly 或 release）。
> 依赖：CI runner 需能访问 MySQL（21.130.247.89:3306 只读账号）+ Prometheus（19090）+ 模型网关
> （OpenCode Go：`opencode.ai/zen/go/v1`，key 走 CI 变量 `MYXHS_LLM_API_KEY`，禁止明文）。

## job 模板

```yaml
# AI 模块：PR 门禁（smoke 级，约 20-40 分钟）
ai-eval-gate:
  stage: test
  image: maven:3.9-eclipse-temurin-17
  variables:
    MAVEN_OPTS: "-Dmaven.repo.local=$CI_PROJECT_DIR/.m2"
    MYXHS_DB_URL: "jdbc:mysql://21.130.247.89:3306/my_xhs_order_0?useSSL=false&serverTimezone=Asia/Shanghai"
    MYXHS_DB_USER: "myxhs_ai_ro"
    MYXHS_DB_PASSWORD: "$MYXHS_DB_PASSWORD"   # CI 变量
    MYXHS_AI_DB_URL: "jdbc:mysql://21.130.247.89:3306/my_xhs_ai?useSSL=false&serverTimezone=Asia/Shanghai"
    MYXHS_AI_DB_USER: "myxhs_ai_rw"
    MYXHS_AI_DB_PASSWORD: "$MYXHS_AI_DB_PASSWORD"   # CI 变量
    MYXHS_LLM_API_KEY: "$MYXHS_LLM_API_KEY"   # CI 变量（OpenCode Go，勿写明文）
    MYXHS_ES_PASS: "$MYXHS_ES_PASS"
    ARK_PLAN_API_KEY: "$ARK_PLAN_API_KEY"
  script:
    - mvn -pl my-xhs-ai-tools install -DskipTests
    - mvn test -pl my-xhs-ai-app -Peval-gate -Dtest='EvalGateRunTest'
  artifacts:
    when: always
    paths:
      - my-xhs-ai-app/target/eval-gate-report.json
  timeout: 1h
  only:
    - merge_requests
    - main
```

## 说明

1. **普通单测**（快速回归）：`mvn test -pl my-xhs-ai-tools,my-xhs-ai-app,my-xhs-ai-mcp`——
   评测测试默认排除（@Tag eval-gate），无凭据 CI 也可跑
2. **门禁**（真库+真模型）：`-Peval-gate` 只跑 EvalGateRunTest（7 条锚点集）+ EvalSmokeRunTest（2 条）
3. **无凭据降级**：`MYXHS_LLM_API_KEY` 未设时 @EnabledIf 自动跳过门禁（不阻塞构建，仅记录）
4. **阈值校准**：`myxhs.ai.eval.gate.*`（默认 10/60/40）——真库评测数据累积后收紧；
   门禁报告 artifact 供人工复核
5. **RAG 相关测试**需要 ARK_PLAN_API_KEY + ES——CI 未设时跳过（@EnabledIf 同机制）

## 安全

- 所有密钥走 CI 变量（Settings → CI/CD → Variables，Masked + Protected）
- 门禁 job 不改生产数据（只读账号）；评测写自己的 my_xhs_ai 库
- 报告 artifact 含查询文本——不落敏感信息（查询是运营问题，非用户数据）
