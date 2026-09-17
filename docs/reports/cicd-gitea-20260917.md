# CI/CD 落地实录：Gitea Actions 门禁 + 版本化发布/自动回滚（2026-09-17）

## 一、架构（全本地，不依赖外部 CI）
| 组件 | 说明 |
|---|---|
| Gitea 1.22 | Docker 容器 `my-gitea`，端口 3000/222，数据 `/data2/gitea`，SQLite |
| act_runner v0.6.1 | 宿主机二进制 + systemd（`act-runner.service`），**Docker 执行器**，标签 `local:docker://maven:3.9-eclipse-temurin-17`，挂载 `/data2/.m2` 复用依赖缓存 |
| 代码托管 | 仓库 `ci/my-xhs`（private），`origin` 与 `gitea` 双 remote |
| 流水线 | `.gitea/workflows/ci.yml`（push 触发：拉代码+编译+单测+规范扫描）、`deploy-cart.yml`（手动：蓝绿发布） |
| 门禁脚本 | `scripts/ci-gate.sh`：编译 → common 单测 → 规范扫描（禁 `printStackTrace()` / `System.out.println` / `@Disabled`） |
| CD | `scripts/release-service.sh`：`/data2/releases/<module>/<ts>/app.jar` + `current` 符号链接 + 健康校验 + **自动回滚**；按模块 Xmx；保留最近 5 版 |

## 二、验收结果（全部实测）
| 项 | 结果 | 证据 |
|---|---|---|
| 流水线绿 | push 触发 → 编译+单测+扫描通过，Gitea task status=1 | `docs/reports/（绿证据=Gitea DB action_task.status=1，成功日志系统不落盘）` |
| **门禁拦截（红）** | 提交含 `printStackTrace()` → 流水线 RED，日志 `❌ 禁止 printStackTrace()` / `== ❌ 门禁失败 ==` | `docs/reports/ci-gate-red-20260917.txt` |
| 版本化停机发布 | `release-service.sh cart` → 发布成功（current → 20260917-182949），停机窗口 15-20s | 脚本输出 |
| **自动回滚** | 注入随机字节坏 jar → 健康检查失败 → 自动切回上一版 → cart 200 | 输出 `== ↩️ 已回滚到 ...` + `current` 软链回指 + health=200 |

## 三、边界与审查修正（2026-09-17 深核）
- **术语修正**：当前实现是"停旧起新"的**有损发布**（15-20s 窗口），不是真蓝绿；真蓝绿=双实例+流量切换（网关/LB 切换），**未做**，面试按"版本化发布+自动回滚"口径讲。
- **凭据**：仓库 `.git/config` 中曾含明文密码，已移除并改用仓库外凭据文件（`/root/.git-credentials-gitea`，600）；核查 Gitea 运行日志未出现 token 明文（命令中使用变量引用）。
- 每次 push（含文档提交）都会触发构建 → 后续可加路径过滤或仅标签触发。

## 四、踩坑记录（工程细节，面试可讲）
1. **Gitea workflow 解析限制**：不支持 `on.workflow_dispatch.inputs` 嵌套语法（日志 `unknown on type`）→ 拆分极简工作流；
2. **host 执行器 + JS action 不可用**：路径解析 bug（MODULE_NOT_FOUND）→ 改用 Docker 执行器；
3. **maven 镜像无 node**：`actions/checkout@v4`（JS action）无法执行 → 用 `git clone`（shell）替代，避免 JS action 依赖；
4. **系统服务环境**：runner 用 systemd 托管（host 模式二进制），Docker 模式容器内执行，构建缓存挂载 `/data2/.m2`。

## 五、遗留/下一步
- `deploy-*.yml` 目前只能 Gitea UI 手动触发（无 inputs 参数）；如需参数化可拆多个 workflow 或用 `repository_dispatch`。
- 生产化：发布前自动 `mvn package`、发布与审批单打通、回滚通知（Webhook → 飞书/钉钉）。
- 根盘仅剩 ~10G：镜像/构建缓存均落 `/data2`；Gitea 与 runner 数据都在 `/data2`。
