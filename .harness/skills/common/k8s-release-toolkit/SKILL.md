---
name: k8s-release-toolkit
stage: 组件封装
description: K8s 发布工具封装——Deployment 模板、HPA 配置、探针模板、灰度发布策略、回滚检查脚本
---

# K8s 发布工具封装（k8s-release-toolkit）

## 1. 职责

为项目生成 Kubernetes 部署清单和发布脚本，包括 Deployment 模板、HPA 配置、探针配置、灰度发布策略、回滚检查脚本。**禁止在无探针、无资源限制的情况下部署到 K8s**。

## 2. 触发方式

| 方式 | 说明 |
|------|------|
| 独立命令 | `/k8s-release-toolkit [deploy\|hpa\|probe\|rollout\|all]` |
| 自动加载 | coding-skill 阶段检测到 `k8s/` / `deployment.yaml` / `Dockerfile` 时 |

## 3. 前置条件

- 项目已确定服务名称、镜像名称、端口号
- 已确定部署环境（开发/测试/生产）对应的副本数和资源规格
- 已确定是否启用 HPA（自动扩缩容）
- 已确定探针路径（health check endpoint）
- 用户未指定 → 生成全部组件（`all`）

## 4. 工作流程

### Step 1: 确定生成目标

| 子命令 | 组件 | 检测条件 |
|--------|------|---------|
| `deploy` | Deployment + Service | 无 `deployment.yaml` |
| `hpa` | HPA 配置 | 无 `hpa.yaml` 或副本数 > 2 |
| `probe` | 探针配置 | 无 `livenessProbe` / `readinessProbe` |
| `rollout` | 灰度发布 + 回滚脚本 | 首次部署或生产环境 |
| `all`（默认） | 全部组件 | 首次引入 K8s 部署 |

### Step 2: 生成清单文件

```
k8s/
├── base/
│   ├── deployment.yaml          — Deployment 模板
│   ├── service.yaml             — Service 模板
│   ├── hpa.yaml                 — HPA 配置
│   └── kustomization.yaml       — Kustomize 入口
├── overlays/
│   ├── dev/
│   │   ├── deployment.yaml      — 开发环境副本数/资源覆盖
│   │   └── kustomization.yaml
│   ├── staging/
│   │   ├── deployment.yaml      — 测试环境覆盖
│   │   └── kustomization.yaml
│   └── prod/
│       ├── deployment.yaml      — 生产环境覆盖
│       ├── hpa.yaml             — 生产环境 HPA
│       └── kustomization.yaml
├── scripts/
│   ├── rollout-check.sh         — 回滚前检查脚本
│   └── canary-deploy.sh         — 金丝雀发布脚本
└── README.md                    — 部署说明
```

**每个文件的约束**：

- `deployment.yaml`：replicas ≥ 2（高可用），RollingUpdate（maxSurge=1, maxUnavailable=0），resources 必须有 requests+limits，探针三件套（startup/liveness/readiness），preStop 优雅关闭，podAntiAffinity，镜像标签禁止 `latest`
- `service.yaml`：ClusterIP（内部）或 LoadBalancer（外部），端口命名规范 `{protocol}-{name}`
- `hpa.yaml`：minReplicas=2, maxReplicas=10，指标 CPU 70% + 内存 80%，缩容冷却 300s，扩容冷却 60s
- 探针配置：startupProbe（慢启动保护，failureThreshold=30），livenessProbe（轻量，不依赖外部服务），readinessProbe（检查依赖就绪）
- 灰度发布脚本：金丝雀 10% 流量→监控 5 分钟→逐步增加到 100%；监控指标：错误率 + P99 延迟
- 回滚检查脚本：检查目标版本镜像是否存在、schema 兼容性、版本历史、HPA 状态

### Step 3: 验证

1. YAML 语法校验：`kubectl apply --dry-run=client -f k8s/`
2. Kustomize 构建：`kustomize build k8s/overlays/dev/`
3. 检查清单：
   - [ ] 所有镜像标签使用具体版本号（非 `latest`）
   - [ ] 所有容器有 requests + limits
   - [ ] 所有 Deployment 有 liveness + readiness + startup 探针
   - [ ] 生产环境 replica ≥ 2
   - [ ] 回滚脚本已准备
   - [ ] 灰度发布脚本有监控指标和自动回滚条件

## 5. 输出文件清单

```
k8s/
├── base/deployment.yaml
├── base/service.yaml
├── base/hpa.yaml
├── base/kustomization.yaml
├── overlays/dev/deployment.yaml
├── overlays/dev/kustomization.yaml
├── overlays/staging/deployment.yaml
├── overlays/staging/kustomization.yaml
├── overlays/prod/deployment.yaml
├── overlays/prod/hpa.yaml
├── overlays/prod/kustomization.yaml
├── scripts/rollout-check.sh
├── scripts/canary-deploy.sh
└── README.md
```

## 6. 质量门禁

- ✅ `kubectl apply --dry-run=client` 通过
- ✅ 所有镜像标签使用具体版本号
- ✅ 所有容器有 requests + limits
- ✅ 所有 Deployment 有完整探针配置
- ✅ 生产环境 replica ≥ 2
- ✅ 回滚脚本和灰度发布脚本已生成
- ❌ 禁止 `latest` 镜像标签
- ❌ 禁止无资源限制的容器
- ❌ 禁止无探针的 Deployment
- ❌ 禁止生产环境单副本

## 7. 约束

- ❌ 不生成 Ingress/证书配置（由网关团队管理）
- ❌ 不生成 ConfigMap/Secret 中的敏感数据（只生成占位符）
- ❌ 不修改已有 K8s 清单（只新增，不覆盖）
- ❌ 不擅自执行部署（脚本只生成，不自动执行）
- ❌ 不生成持久化卷配置（PV/PVC 由存储团队管理）
---

> **来源 & 作者**
> - 公众号：华仔聊技术
> - 知识星球：华仔·AI高并发全栈训练营
> - 作者：王江华@huazai
