# 挖掘问题修复台账（RV30，2026-09-15）

> 口径：挖掘发现分三类处理——**缺陷→修复**、**误报（已被旧文档误导/已修）→标注**、**取舍→补论证或改进**

## 第一批（已修复并部署，5 服务）
| # | 问题 | 处理 | 验证 |
|---|------|------|------|
| 1 | 优惠券退券回补 stock/claimed → `remain+已发>total` 超发敞口、可"领→用→退→再领"绕限领 | **修复**：退券只恢复用户券为未使用，不回补模板/领取次数（`CouponService.returnCoupon`） | 编译+服务 UP；语义变更已在日志留痕 |
| 2 | 购物车清空非原子（DEL+marker 两步，并发加购可覆盖） | **修复**：新增 `cart_clear.lua` 原子完成三结构删除+标记写入 | 服务 UP |
| 3 | 购物车对账锁无 token、无条件 DEL（可误删他人锁） | **修复**：随机 token + Lua 比对删除 | 服务 UP |
| 4 | HMAC 先占 nonce 再验签（错误签名可抢占合法 nonce） | **修复**：验签通过后再消费 nonce | 构建+服务 UP |
| 5 | JWT `sub` 为空仍注入空 X-User-Id | **修复**：sub 空直接 401 | 构建+服务 UP |
| 6 | refresh-only 注销静默成功（会话未撤销） | **修复**：access/refresh 任一存在即执行注销；TokenService 容忍 null access | 构建+服务 UP |
| 7 | 公开读笔记只校验 status 不校验 auditStatus | **修复**：发布+审核通过双校验 | 实测：未审核笔记返回"笔记不存在"，恢复后 200 |

## 误报澄清（挖掘来自旧文档，代码已修）
- 购物车 `updateQuantity` 已用 Lua（HEXISTS 防并发复活）——test-2 旧文档描述过时
- `t_product_behavior` 建表语句存在于 `sql/init-all.sql` 与部署包 SQL
- 读写分离：`ReadWriteRoutingDataSource` 已实现"手动指定 / @Transactional(readOnly) / 默认主库"三策略；SQL 前缀分析仅保留工具方法（不再作为默认路由）——不是"未实现"，但**只读路由依赖 readOnly 注解覆盖**，待核查热点读路径覆盖率（列入第二批）

## 第二批（进行中）
| # | 问题 | 状态 |
|---|------|------|
| 1 | product：Canal `t_sku` 变更不进 ES | ✅ 已修（按 spu_id 拉详情重索引父 SPU，RV32），已部署 |
| 2 | product：SPU 幂等键=名称+类目 | ✅ 已修（键加 userId，`ProductController:57`），已部署 |
| 3 | gateway：WebFlux 内同步 Redis 阻塞 EventLoop | ⚠️ 决策：安全过滤器内阻塞点（黑名单/密钥/nonce）改为 `Mono.fromCallable().subscribeOn(boundedElastic)` 需配合压测与红队回归；列入下一批（含超时与脱敏日志） |
| 6 | inventory：SKU 不存在静默 ACK（单成未扣且不可见） | ✅ 已修（写异常集合 + 抛错入 DLQ 可见），已部署 |
| 10 | user：验证码 Redis 写失败仍 200 | ✅ 误报（CaptchaService 用 StringRedisTemplate 直写，失败会抛错） |
| 7 | cart：Product 降级 fail-open | ✅ 取舍保留（可用性优先，列表层 valid 标记兜底；代码注释已说明） |
| 12/13 | order 广播截断 / payment P-2~P-5 | ✅ 取舍保留（已有论证） |
| 5 | common：readOnly 覆盖为 0（读写分离未真正生效） | ⚠️ 决策：暂不启用读从库（读己之写延迟风险），保留三策略骨架并在台账说明开启条件 |
| 8 | coupon：Outbox 表无限增长/用户券列表无分页 | ✅ 已修（每小时清理 7 天前已发送 + 列表硬上限 200），已部署 |
| 4 | content：审核状态机流转不真实 | ⚠️ 产品决策：当前为"DFA 自动审核直发"简化版（拒绝即失败不落库）；真实审核队列（AUDITING→人工复核→APPROVED/REJECTED）属产品功能，列入 Roadmap |
| 9 | im：实例崩溃后 90s 路由窗口内消息丢失 | ⚠️ 已论证取舍（IM 允许少量丢消息）；改进方向=优雅停机主动清理路由 + Redis Stream 持久订阅（Roadmap） |
| 11 | notification：免打扰 | Roadmap（产品功能） |

4. content：审核状态机流转不真实（AUDITING/REJECTED 无流转）→ 补真实流转
5. common：核查/补齐热点读路径 `@Transactional(readOnly=true)` 覆盖率
6. inventory：SKU 不存在时消费者直接确认（可能"单成未扣"）
7. cart：Product 降级 fallback 异常路径 fail-open（幽灵 SKU）
8. coupon：Outbox 表无限增长/用户券列表无分页/afterCommit 同步阻塞提交线程
9. im：实例崩溃后路由 TTL 90s 窗口内消息丢失 → 缩短窗口或改离线兜底
10. user：验证码 Redis 写失败仍返回 200（RedisOperator 吞异常）
11. notification：免打扰未实现（feature，列入 Roadmap）
12. order：无 user_id 全分片广播 LIMIT 截断批序失真（已论证低频兜底，保持）
13. payment：P-2~P-5 已论证"可接受"（保持，方案见 service-deep 文档）
