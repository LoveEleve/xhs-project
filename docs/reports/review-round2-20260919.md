# Review 第二轮（2026-09-19）：越权实弹 + 测试端点暴露 + Redis TTL 审计

> 方法：静态审计（控制器/服务归属）→ 双用户实弹（IDOR）→ Redis 键采样（3,906 键 TTL 分布）→ 修复 → 复测。

## 一、越权（IDOR）实弹：5/5 通过（无越权）

用户 B（新注册）访问用户 A（chaintest）的资源：

| 场景 | 结果 |
|---|---|
| B 查 A 订单详情 `/api/order/{A_orderId}` | `30008 订单不存在`（按 B 的 userId 路由，不可见） |
| B 按单号查 A 订单 `/api/order/by-order-no/{A_orderNo}` | `403 无权查看该订单` |
| B 查 A 支付状态（order 面）`/api/order/pay/status/{A_orderId}` | `403 无权查看该订单` |
| B 直连 payment `/api/payment/status/{A_orderId}` | `403 仅限内部服务调用`（T-061 内部鉴权） |
| B 标记 A 的通知已读 `/api/notification/read/{A_notifId}` | `通知不存在`（SQL 层 user_id 过滤） |

静态面复核：券核销/退券（内部令牌）、笔记编辑/删除（`getAndCheckOwner`）、地址（userId+id 联合查询）均正确。

## 二、测试端点对普通用户暴露（真问题，已修复）

- **现象**：`@Profile("dev")` 在本环境激活，且 `/api/notification/test/send`、`/api/home/test/push-inbox|push-outbox` **仅靠参数校验**拦截（普通用户 token 可到达业务逻辑）——可向任意 userId 注入通知/Feed（数据污染面）。
- **修复**：两个测试控制器补 `X-Internal-Call` 守卫（`AccessTokenGuard`，与项目既定内部调用口径一致）。
- **连带发现**：**home 是唯一缺 `myxhs.internal.token` 配置的服务**，guard fail-closed 会拒绝全部内部调用 → 补 `${INTERNAL_TOKEN:}`。
- **验证**：无内部令牌 → `403 仅限内部服务调用`；带内部令牌 → `200 操作成功`；回归 test-11 **12/12**、test-13 **9/9**。
- **建议**：prod 启动强制 `--spring.profiles.active=prod`（把 dev 端点从"跑得到"变成"注册不了"）。

## 三、Redis TTL 审计（真问题，已修复）

采样 3,906 键的 TTL 分布，发现**点赞集合全链路无 TTL**（业务 Redis 为 noeviction，无 TTL=内存只增不减）：

| Key 前缀 | 数量 | 无 TTL | 归属 |
|---|---:|---:|---|
| `myxhs:like:set:{type}:{id}` | 81 | 81 | counter 去重集（Lua KEYS[2]） |
| `myxhs:like:user:{uid}:note` | 2 | 2 | analytics 反向索引 |
| `myxhs:like:note:{id}` | 1 | 1 | analytics 权威点赞集 |

- **根因**：counter 的 `LIKE_SET_SCRIPT` 里去重键（KEYS[1]）与计数键（KEYS[3]）都有 TTL，唯独**集合本身（KEYS[2]）漏了**（T-035 修复遗漏）；analytics `LikeService` 全程无 `expire`。
- **修复**：
  1. counter Lua 补 `redis.call('EXPIRE', KEYS[2], ARGV[4])`（30 天，与计数键一致）；
  2. analytics 增 `renewLikeSetTtl()`（like/unlike/回滚共 4 处调用，30 天续期）；
  3. 存量 84 个无 TTL 键回填 30 天。
- **验证（端到端）**：点赞 → `myxhs:like:note` / `myxhs:like:user` / `myxhs:like:set` 三类 key `ttl=2,591,997s`；取消点赞 → 集合清空还原。
- **其他观察**：`xhs-ai:state:*`（546 键）全部无 TTL —— AI 域状态存储，登记给 ai 侧复核是否需 TTL；counter 另有 1 个无 TTL 异常键（疑似演练/手工产物，量级可忽略）。

## 四、结论
- 越权面：核心交易/内容/通知资源均无越权（实弹 5/5）。
- 本轮修复 2 个真问题（测试端点守卫、点赞集合 TTL）+ 1 个连带配置缺失（home internal token）。
- 回归：home/notification 发布后 test-11/13 全绿；analytics/counter 发布后点赞链路端到端验证通过。
