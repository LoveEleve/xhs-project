# 02-findings 索引（交叉复核后）

> 本轮 review 已确认问题清单。每条均含证据、影响、修复建议与残余风险。
> 严重度：Critical > High > Medium > Low。
> **交叉复核说明（2026-08-17）**：对库存确认/Outbox/索引计数三个问题做了二次核验，依据对账任务边界、事件时序与 counter 收敛机制，将 F-004/F-005/F-012 从 High 下调为 Medium。

## Critical

| 编号 | 问题 | 涉及 |
|---|---|---|
| F-001 | 撤销后的 access token 直连服务端口仍可用（GatewayAuthTrustFilter 不查黑名单） | gateway/common/全部服务 |
| F-007 | 退款回补幂等键只有 orderId，多 SKU 订单只回补一个 SKU | order/inventory |

## High

| 编号 | 问题 | 涉及 |
|---|---|---|
| F-002 | 管理端点直连服务端口只校验共享 X-Admin-Call，admin token 泄漏即可执行高危操作 | common/user/inventory/search |
| F-003 | order Feign 配置保留公开已知 internal token fallback | order |
| F-006 | 补偿消费者路由映射缺失时吞消息，不重试 | order |
| F-008 | 退券 afterCommit 的 Redis 失败无补偿路径 | coupon/order |
| F-010 | 退款成功回调无条件返回 ok，掩盖订单侧失败，补偿链断裂 | order/payment |
| F-013 | AI 会话归属与取消任务权限校验缺失 | ai-app |
| F-014 | 重启脚本健康检查假阳性，端口被旧实例占用时误报就绪 | scripts |
| F-017 | IM WebSocket 使用公开 JWT Secret fallback，可伪造任意 ws_ticket | im |
| F-018 | Redis 使用公开 host/password fallback，配置漂移时可能连接外部 Redis | common/全部服务 |
| F-019 | 生产凭据与基础设施密码直接提交在配置和测试脚本中 | 全仓配置/脚本 |
| F-021 | 推荐行为消费者吞掉写库异常，行为数据静默丢失 | search |
| F-023 | 商品索引增量补偿会重建不完整文档（价格=0、品牌/分类名丢失） | search |
| F-024 | 全量笔记索引重建会把 like/collect/comment 计数全部写成 0 | search |
| F-026 | Nacos 运行快照显示鉴权未开启，配置中心存在未授权读写风险 | nacos/配置中心 |
| F-029 | 防火墙脚本规则默认不持久化，重启后中间件保护可能消失 | 部署/防火墙 |
| F-030 | remote-upgrade.sh 可能吞掉 docker compose 失败并继续执行 | 部署脚本 |
| F-031 | MySQL 备份脚本可能在 mysqldump 失败时生成“成功”备份 | 备份脚本 |
| F-033 | Docker 全部服务使用 host 网络，服务间网络隔离完全依赖宿主机防火墙 | 部署/网络 |
| F-034 | Elasticsearch 开启认证但关闭 HTTP TLS，凭据可能明文传输 | Elasticsearch/搜索 |
| F-035 | RocketMQ ACL 使用公开静态 accessKey/secretKey | RocketMQ |
| F-042 | 商品索引消费者在 product Feign 失败时仍写入不完整 ES 文档 | search/product |
| F-043 | 点赞/收藏在目标校验异常时降级放行，会对不存在内容落 Redis/MQ | analytics/content |

## Medium

| 编号 | 问题 | 涉及 |
|---|---|---|
| F-004 | 库存确认先删 Redis 预扣再发 MQ，失败后 confirm 事件无重试凭据；locked_stock 虚高残留 | order/inventory |
| F-005 | 库存 Outbox 唯一键不含 action；顺序 happy path 可工作，异常时序有覆盖风险 | inventory |
| F-009 | 退款通知补偿去重键从未写入，反复通知并产生误报 | payment |
| F-011 | 支付金额未对订单真实金额二次校验，getOrderPayAmount 语义与注释不符 | payment/order |
| F-012 | 笔记索引同步读 counter 计数源，滞后值短暂覆盖权威点赞计数（counter 对账可收敛） | search |
| F-015 | 订单事务消息缺少关键字段时直接确认消费，订单已提交但库存预扣被跳过 | order/inventory |
| F-016 | 领券消费者忽略模板库存 UPDATE 影响行数，可能提交无库存用户券 | coupon |
| F-020 | Gateway 请求日志完整落 query string，敏感参数会直接入日志 | gateway |
| F-022 | ES 增量补偿用 distinctRandomMembers + remove，失败集合不是原子弹出 | search |
| F-025 | 推荐特征提取仍读 t_counter 作为互动特征源，可能固化滞后计数 | search |
| F-027 | 全链路测试脚本把“直连服务端口 + 伪造 X-User-Id”固化为默认用法 | 测试脚本 |
| F-028 | 部署文档把不安全默认值产品化，容易把临时环境习惯带入长期运行态 | 部署文档/示例 |
| F-032 | RocketMQ 指标脚本在采集命令失败时仍写入 up=1 | 监控脚本 |
| F-036 | 订单映射补偿只扫描最近 1 小时，持续故障后历史缺失映射永久遗漏 | order |
| F-037 | 库存补偿重试固定使用 bucket 0，无法恢复实际预扣桶 | inventory |
| F-038 | 优惠券对账只扫描启用且未过期模板，历史库存漂移无法修复 | coupon |
| F-039 | Home BFF 将下游不可用伪装成“对象不存在”或“空购物车” | home/BFF |
| F-040 | FeedCleanupJob 的节流计数器每次迭代重置，节流逻辑永远不生效 | home |
| F-041 | 推荐离线任务在内部失败时仍向 XXL-Job 报成功 | search/recommend |

## 分布

- 鉴权与身份：F-001、F-002、F-003
- 交易一致性：F-004、F-005、F-006、F-007、F-008、F-009、F-010、F-011、F-016
- 异步消息：F-015
- 内容分发与检索：F-012
- AI 接入：F-013
- 运维与脚本：F-014

## 待审专题

- 07-async-and-mq 横切链路（部分已覆盖于交易/内容 findings）
- 09-infra-and-ops 其余脚本/配置/运行态
- 06-content-and-distribution 其余链路（home feed 聚合、评论多级嵌套删除）
