# Phase 6: MCP 协议 + 工具生态

## 前置依赖

- **Phase 5 (Agent架构)**：Skills 的底层执行技术是 MCP

## 为什么第六

MCP 是连接层协议——它解决 "Agent 怎么调工具"。本 Phase 把 my-xhs 的 12 个业务域变成可被 Agent 调用的独立 MCP Server。

## 与 my-xhs 的关联

| MCP Server | 端口 | 数据源 | Tool | 对应业务域 | 业务问题 |
|-----------|------|--------|------|-----------|---------|
| order-mcp | 19021 | MySQL 13308 | query_recent_orders, query_order_stats | 订单域 | "今天订单量？比昨天？" |
| user-mcp | 19022 | MySQL 13306 | query_user_info, query_user_stats | 用户域 | "今天注册多少用户？" |
| payment-mcp | 19023 | MySQL 13308 | query_payment_records, query_payment_stats | 支付域 | "支付成功率？失败渠道分布？" |
| inventory-mcp | 19024 | MySQL 13309 | query_inventory, query_low_stock | 库存域 | "哪些SKU库存不足？" |
| content-mcp | 19026 | MySQL 13307 | query_notes, query_note_stats, query_hot_topics | 内容域 | "今天发布多少笔记？热门话题？" |
| product-mcp | 19027 | MySQL 13307 | query_products, query_category_tree | 商品域 | "哪个商品卖得最好？" |
| coupon-mcp | 19028 | MySQL 13307 | query_coupon_stats, query_expiring | 优惠券域 | "今天领券/用券量？" |
| analytics-mcp | 19029 | MySQL 13306 | query_interaction_stats, query_follower_growth | 社交域 | "互动率最高笔记？粉丝增长？" |
| search-mcp | 19030 | ES 19200 + MySQL 13307 | query_hot_search, query_search_stats | 搜索域 | "热搜Top20？无结果率？" |
| log-mcp | 19025 | ES 19200 | query_error_logs, query_anomalies | 异常检测 | "最近有哪些异常？" |

## 学什么

| 模块 | 内容 |
|------|------|
| MCP 协议深度 | Tools/Resources/Prompts/Sampling 四方向、STDIO/SSE/Streamable HTTP 三传输 |
| MCP Server 开发 | Spring Boot 独立服务+Nacos 注册+工具注册+Schema 暴露+幂等性+错误返回格式 |
| 10 个 MCP Server | 每个：只读 MySQL/ES、结构化 Tool Schema（JSON Schema）、参数校验、安全约束 |
| MCP 生态 | 2000+ Server 探索（数据库/云服务/浏览器/文件系统） |

## 生产工程问题

| 问题 | 本 Phase 如何解决 | 验证方式 |
|------|-----------------|---------|
| **数据一致性** | 每个 MCP Server 时间戳标注查询时刻；Agent 汇总时标注数据窗口 | 跨 Server 查询结果的时刻差异 < 1s |
| **SQL 注入** | 参数化查询（MyBatis `#{}`）+只读 MySQL 账号 + 输入校验 | 注入攻击全部拦截 |
| **部分失败** | 单个 MCP Server 超时→返回 `{status: "unavailable", cached: true/false}` | 1/10 Server 故障→其他 9 个正常返回 |
| **向量检索退化** | search-mcp 定期监控 ES 查询延迟 | P95 延迟 < 100ms |

## 文档清单（8 篇）

| # | 文档 | 内容要点 |
|---|------|---------|
| 01 | mcp-protocol.md | 四方向+三传输协议深度+命名规范 |
| 02 | mcp-server-design.md | Java MCP Server 开发规范+工具注册+Schema+幂等+错误格式 |
| 03 | order-user-payment-mcp.md | 订单/用户/支付 3 个 MCP Server 设计文档 |
| 04 | inventory-content-product-mcp.md | 库存/内容/商品 3 个 MCP Server |
| 05 | coupon-analytics-search-mcp.md | 优惠券/社交/搜索 3 个 MCP Server |
| 06 | log-mcp.md | ES 日志+异常检测 MCP Server |
| 07 | mcp-ecosystem.md | 2000+ Server 生态+分类探索 |
| 08 | agent-mcp-integration.md | Agent→MCP 发现→调用→错误处理全流程 |

## 代码结构（10 个独立 Maven 模块）

```
my-xhs-ai-mcp-order/         # 端口 19021
my-xhs-ai-mcp-user/          # 端口 19022
my-xhs-ai-mcp-payment/       # 端口 19023
my-xhs-ai-mcp-inventory/     # 端口 19024
my-xhs-ai-mcp-log/           # 端口 19025
my-xhs-ai-mcp-content/       # 端口 19026
my-xhs-ai-mcp-product/       # 端口 19027
my-xhs-ai-mcp-coupon/        # 端口 19028
my-xhs-ai-mcp-analytics/     # 端口 19029
my-xhs-ai-mcp-search/        # 端口 19030
```

## 验证标准

1. 10 个 MCP Server 独立部署+Nacos 注册+健康检查通过
2. Agent 通过 MCP 调用 10 个 Server 全部返回结构化数据
3. Agent 能并行调用 3 个 Server 回答跨域问题（如"订单下降→查支付成功率→查错误日志"）
4. MCP Server 重启→Agent 自动重连
5. 单 Server 超时→Agent 返回部分结果（其他 Server 数据）+标注失败源
6. SQL 注入攻击全部拦截（参数化查询+只读账号兜底）

## 对后续的影响

- **Phase 7 (多Agent)**：多个 Agent 共享 10 个 MCP Server
- **Phase 8 (Skills)**：10 个 MCP Server→10 个标准 Skill
- **Phase 9 (评测)**：MCP Tool 是评测的对象
- **Phase 12 (部署)**：10 个 MCP Server 的 K8s 化
- **Phase 15 (验收)**：端到端场景调用 10 个 MCP Server
