# my-xhs 文档

## 文档目录

```
docs/
├── architecture/     ← 架构设计、技术规格、服务依赖、BFF 设计
├── business/         ← 项目总览、16 模块详细设计、审核系统
├── dev/              ← 分阶段功能开发文档 + Code Review
├── distributed/      ← 分布式方案、对账、卖家订单查询
├── infrastructure/   ← Docker 部署、容量分析、压测基线
└── production/       ← 生产决策、踩坑指南、降级预案
```

## 核心文档速查

| 要做什么 | 看这里 |
|----------|--------|
| 了解项目全貌 | `business/01-project-overview.md` |
| 了解 16 个模块设计 | `business/02-module-detailed-design.md` |
| 了解架构决策 | `architecture/08-architecture-decision-critical-analysis.md` |
| 部署中间件 | `infrastructure/04-infrastructure-and-deployment.md` |
| 面试准备 | `production/06-production-decision-and-expression-handbook.md` |
| 全局 Code Review | `dev/GLOBAL-CODE-REVIEW.md` |

## 项目真实状态

**完成度约 70%**。架构设计、业务代码、中间件集成基本完成，但：

- ❌ 单元测试几乎为零（395 个 Java 文件，仅 4 个测试类）
- ❌ Phase 6/7 的 CODE-REVIEW 文档大量缺失
- ❌ 部分 Phase 6/7 功能仅完成基础实现，未做生产级加固
- ✅ 16 个微服务模块代码完整，可编译运行
- ✅ 全部中间件 Docker 化，一键部署
- ✅ 数据库设计完善，含分库分表、本地消息表、订单快照
