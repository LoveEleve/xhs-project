---
iteration: ITER-013
status: confirmed
version: 1
created: 2026-10-19
updated: 2026-10-19
---

# 测试设计: 私信模块生产化改造

## 验证策略

- **单测为主**（项目规则：无运行时环境；Redis/JDBC/Redisson 全 mock；删 target/test-classes 强制全量重编）；
- **测试集从零建立**（模块当前零测试）：目标 ≥40 用例，9 类；
- MP Lambda 缓存：涉及 LambdaQueryWrapper 的测试类 @BeforeAll 手动 `TableInfoHelper.initTableInfo`（家族模式）；
- 集成/E2E：不做。

## AC → 测试方法映射

| AC | 测试方法 | 层级 | 观测方式 |
|----|---------|------|---------|
| AC-1 | `ChatServiceTest`：未读前移（InOrder 断言 increment 先于 convertAndSend）；`ChatUserRelationMapperTest`（注解内容断言 SQL 含 unread_count=0）；`refreshUnreadCacheTest`（DB→putAll+TTL/空则删）；TTL 脚本参数 | 单测 | InOrder/ArgumentCaptor/注解反射 |
| AC-2 | `ChatServiceTest`：route 异常仍 ACK；storeOffline 异常仍 ACK+推送；handleRead Redis 异常仍 READ_NOTIFY | 单测 | mock 抛异常 + captor |
| AC-3 | `OnlineRouteServiceTest`：register Lua（脚本执行 + 旧值返回）；`ImWebSocketHandlerTest`：满员老用户豁免（containsKey） | 单测 | mock script 返回 |
| AC-4 | `ChatServiceTest`：clientMsgId 重放 ACK 原 msgId（不落库）；persist 失败删标记；未传 clientMsgId 跳过 | 单测 | captor + verify |
| AC-5 | grep 核验（注释 ×7/SQL 4 副本一致）；`ChatMessage` 死列零引用（编译） | 核验 | grep |
| AC-6 | grep 核验（LB/isOnline/online 键零残留）；限流注解存在 | 核验 | grep/反射 |
| AC-7 | 十三模块 `mvn test` 全绿 + §8/材料回写 | 回归 | 构建输出 |

## 测试类清单（9 类）

1. `ChatServiceTest`：handleChat 校验/正常流/前移/降级/幂等/锁失败；handleRead/typing/ack；delete/markAllRead；未读汇总；refreshUnreadCache
2. `OnlineRouteServiceTest`：register/unregister/renew/getRoute（Lua 参数 + 返回值）
3. `ImWebSocketHandlerTest`：容量（含豁免）/连接建立（踢本地/跨实例/重建+补发）/关闭守卫/分发/心跳
4. `ImRouteSubscriberTest`：KICK/98/99/CHAT 四路径 + 推成功删副本 + 推失败降级（仅 CHAT）
5. `ImHandshakeInterceptorTest`：票据三关（类型/jti 一次性/subject 一致）+ traceId
6. `ImControllerTest`：ticket 签发（jti 登记）+ 启动自检（fail-closed）+ 限流注解
7. `ChatServiceOfflineTest`（或并入 1）：补发排序（seqNo）/DB 缺失清理/上限
8. `MessagePersistServiceTest`：事务方法落库 + upsert 三路径（insert/dup 降级/补插）
9. `ChatUserRelationMapperTest`：三条 SQL 注解内容断言（定向更新/软删含清零/清零）

## 关键边界用例

- 未读前移顺序（InOrder：HINCRBY → ZADD → convertAndSend → ACK）
- 路由异常 → 仍 ACK（发送方不重发）
- clientMsgId 重放：第二次调用不触达 persist/route，ACK 带原 msgId
- register 旧值返回（跨实例踢线依赖）
- 满员 + 老用户 → 允许；满员 + 新用户 → 拒绝
- handleRead：DB 清零成功 + Redis 异常 → 对方仍收 READ_NOTIFY
- 软删 SQL 含 unread_count=0（注解反射断言——防回归）
