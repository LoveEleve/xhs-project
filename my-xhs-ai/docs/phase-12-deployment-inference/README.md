# Phase 12: 部署 + 推理 + 沙箱

## 前置依赖

- **Phase 5 (Agent架构)**：分布式运行时→部署方案
- **Phase 6 (MCP)**：10 个 MCP Server→部署单元
- **Phase 8 (Skills)**：Skills→标准化部署
- **Phase 11 (安全)**：沙箱是部署安全的前置

## 为什么第十二

前面 11 个 Phase 都在"能跑"，Phase 12 解决"能稳定跑"。2026 年 GKE Agent Sandbox 已 GA——隔离执行不再是可选项。

## 与 my-xhs 的关联

| 部署需求 | my-xhs 基础 | 本 Phase 产出 |
|---------|-----------|-------------|
| 服务编排 | 已有 Docker 经验 | docker-compose.yml：编排层+10 MCP Server+ES+Redis |
| 容器管理 | 已有 K8s 经验 | 12 个 Deployment/Service/HPA/ConfigMap |
| 灰度发布 | 无 | Canary+Prompt A/B+自动回滚 |
| 沙箱隔离 | 无 | Docker 沙箱：不可信工具代码隔离执行 |
| 故障恢复 | 已有 Redis | Agent 状态快照→K8s Pod 重启后<2s 恢复 |

## 学什么

| 模块 | 内容 |
|------|------|
| 本地模型 | Ollama/vLLM/Qwen3.6（1M 上下文+native MCP）|
| 模型量化 | FP16→INT8→INT4+GGUF+GPTQ/AWQ+质量损失 < 5% |
| 推理优化 | PagedAttention+前缀缓存+Continuous Batching+语义缓存（Redis）+模型分层路由 |
| Docker | 全服务 Compose+多阶段构建+健康检查 |
| K8s | Deployment/Service/HPA/ConfigMap+灰度+Canary |
| 沙箱 | Docker 隔离+快照+恢复 |

## 生产工程问题

| 问题 | 本 Phase 如何解决 | 验证方式 |
|------|-----------------|---------|
| **模型降级** | FallbackModel（Phase 5）+本 Phase K8s 多副本保证高可用 | 主模型故障→备模型接管，延迟增加 < 2s |
| **成本爆炸** | 语义缓存（Redis）+模型分层路由（小模型分类→大模型推理） | 缓存命中率 > 30%，分层后成本下降 50% |
| **向量检索退化** | ES 索引定期重建 CronJob+延迟监控 | P95 延迟 < 100ms，超阈值自动重建 |

## 文档清单（7 篇）

| # | 文档 | 内容要点 |
|---|------|---------|
| 01 | local-model.md | Ollama/vLLM/Qwen3.6 三种本地部署方案+性能对比（TTFT/吞吐/显存） |
| 02 | quantization.md | FP16→INT8→INT4 量化流程+GGUF 格式+GPTQ/AWQ 对比+质量损失测试 |
| 03 | inference-optimization.md | PagedAttention+前缀缓存+Continuous Batching+语义缓存+模型分层路由 |
| 04 | docker-compose.md | 全服务 Docker Compose 编排（编排层+10 MCP+ES+Redis）+多阶段构建+健康检查 |
| 05 | k8s-deployment.md | 12 个 Deployment/Service/HPA/ConfigMap 配置+灰度发布+Canary |
| 06 | sandbox.md | Docker 沙箱：不可信代码隔离执行+快照+恢复 |
| 07 | canary-rollout.md | 灰度发布流程：Canary+Prompt A/B+自动回滚 |

## 部署文件

```
deploy/
├── docker-compose.yml
├── k8s/
│   ├── ai-deployment.yaml
│   ├── ai-service.yaml
│   ├── ai-hpa.yaml
│   ├── mcp-order-deployment.yaml
│   ├── mcp-user-deployment.yaml
│   ├── mcp-payment-deployment.yaml
│   ├── mcp-inventory-deployment.yaml
│   ├── mcp-content-deployment.yaml
│   ├── mcp-product-deployment.yaml
│   ├── mcp-coupon-deployment.yaml
│   ├── mcp-analytics-deployment.yaml
│   ├── mcp-search-deployment.yaml
│   └── mcp-log-deployment.yaml
└── sandbox/
    └── Dockerfile.sandbox
```

## 验证

1. Docker Compose 一键启动 12 个服务
2. K8s 多副本+HPA 自动扩缩
3. 量化后评测下降 < 5%
4. 沙箱中执行不可信代码不影响宿主机
5. K8s Pod 重启→Agent 从 Redis 恢复会话（< 2s）

## 对后续的影响

- **Phase 15 (验收)**：部署完成是系统集成的物理前提
