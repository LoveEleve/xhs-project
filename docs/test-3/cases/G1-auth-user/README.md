# G1-auth-user — 认证与用户

> 测试文档（待写）| 入口一律走 gateway(19000)；时间机制引用 `../00-time-matrix.md` I 节编号。

## 业务范围
验证码/注册/登录/刷新/注销/用户信息/关注/粉丝

## 归属定时/联动任务
followCounterRepairJob（关注计数修复，xxl#3）

## 时间机制
#7 验证码、#8/9 token、#10/11/12 HMAC、#35 登录锁

## 用例文档
- （待写）G 组用例：前置构造 → 调用 → 断言（业务码+响应体+L2 数据）

## 测试入口边界（重要）
- **用户行为一律走 gateway(19000)**（模拟前端：带 Authorization + HMAC 签名）
- **不走 gateway 的辅助通道**（测试中明确标注用途）：xxl admin API（定时触发）/ redis-cli+mysql+console（L2 数据验证）/ RocketMQ console（投测试消息）/ actuator（健康检查）

## 数据关注矩阵（L2 验证点，代码实证 2026-08-12）
| 用例 | Redis key（实证）| MySQL / MQ |
|---|---|---|
| 验证码 | `myxhs:user:captcha:{key}`（5min；GETDEL 消费后消失）| — |
| 注册 | `user:register:lock:{username}` 释放 | `my_xhs_user.t_user` 新行（BCrypt/status=1）|
| 登录成功 | `user:token:access:{jti}`、`user:token:refresh:{jti}`、`user:hmac:secret:{uid}`（7 天）| — |
| 登录失败/锁定 | `login:fail:{username}`、`login:fail:ip:{ip}`、`login:fail:ips:{username}`(30min)、`login:lock:{username}`/`login:lock:ip:{ip}`(15min) | — |
| 注销 | 黑名单 `user:token:blacklist:{jti}`（剩余有效期）；token key 删除 | — |
| 用户信息 | `user:info:{uid}`（30min 缓存）| `t_user`（改 DB→删缓存→新值）|
| 改密码 | 旧 token 失效（待确认逻辑）| `t_user.password`（BCrypt）|
| 关注/取关 | `follow:fans:{uid}`（ZSet）、`follow:list:{uid}` | `my_xhs_analytics.t_follow`；**MQ SOCIAL_TOPIC:FOLLOW/UNFOLLOW → counter 计数** |
| followCounterRepair | 计数 vs t_follow 实际数 | 构造不一致 → xxl#3 触发 → 修复 |

## 用例文件规划（7 个）
| 文件 | 内容 | 关键 L2 关注 |
|---|---|---|
| G1-01-captcha.md | 验证码获取/校验/过期/防并发消费 | captcha key 生命周期 |
| G1-02-register.md | 注册成功/重名/手机号/锁/幂等 | t_user 行 + 锁释放 |
| G1-03-login.md | 成功/密码错/验证码错/锁定三分支/解锁 | token 对 + fail/lock keys |
| G1-04-token.md | access/refresh/过期(自签JWT)/注销黑名单 | token/blacklist keys |
| G1-05-hmac.md | timestamp 5min/nonce/secret 失效 | hmac secret key |
| G1-06-userinfo.md | me/公开 info/改资料/改密码 | user:info 缓存 + t_user |
| G1-07-follow.md | 关注/取关/列表/计数修复 | t_follow + follow:fans + MQ + xxl#3 |

## 深度 REVIEW 修正（2026-08-12，代码实证）

### 遗漏补充
| 项 | 端点 | 数据关注 |
|---|---|---|
| **地址管理**（遗漏）| /api/user/address CRUD + /list + /default + /{id}/default | `t_user_address` 表 + `myxhs:user:address:default:{uid}`、`myxhs:user:address:lock:{uid}` |
| **拉黑**（遗漏）| POST/DELETE /api/user/block/{targetUserId}、GET /api/user/block/list | `myxhs:user:block:{uid}`（拉黑后关注/评论受限待测）|
| 邮箱验证码 | 常量 `myxhs:user:email:code:` 存在但**无发送实现** | **不测**（功能未实现，标注）|

### 修正（实证）
1. **改密码**：确认**无 token 失效逻辑**（changePassword 只更新 password，旧 token 继续有效）→ 测试验证旧 token 仍可用 + 列为**安全审查点**（改密后旧 token 是否应失效，待业务决策）
2. **关注端点路由**：`/api/social/**` → gateway 路由到 **analytics 服务**（非 user 服务）——关注链路 = gateway → analytics，数据在 `my_xhs_analytics` 库
3. **followCounterRepairJob 修复对象**：以 **Redis ZSet 为准**（ZCARD 覆盖 counter 计数 + 修复 Redis↔MySQL 关系行差异）——非"计数对 t_follow 单向"

### 用例文件规划补（7 → 9）
| 文件 | 内容 |
|---|---|
| G1-08-address.md | 地址 CRUD/默认地址/列表（新）|
| G1-09-block.md | 拉黑/取消拉黑/黑名单列表（新）|

### 缓存一致性确认（无问题，差点误报）
- updateUserInfo 有 `cacheHelper.delayDoubleDelete(USER_INFO)`（UserService:310）——改资料后缓存正确失效 ✅

## 人工观察点（🔍 用户登录控制台执行）
| 用例跑完后 | 登录 | 看什么 |
|---|---|---|
| G1-01 验证码 | Kibana | 验证码生成/校验日志无异常 |
| G1-02/03 注册+登录 | SkyWalking | login 注册 trace：gateway→user→redis/db span 完整（无异步断链）|
| G1-02/03 | Grafana | user 服务 QPS、HTTP 错误率、JVM |
| G1-03 锁定分支 | Kibana | 登录失败/锁定 WARN 日志 |
| G1-04 token | Kibana | token 生成/黑名单日志 |
| G1-05 HMAC | Kibana | 签名校验拒绝 WARN 日志（403 分支）|
| G1-07 关注 | SkyWalking | 关注链路 gateway→analytics→counter（MQ 消费 span）|
| G1-07 | RocketMQ | SOCIAL_TOPIC 消息投递/消费进度 |
| G1-07 计数修复 | XXL-Job | followCounterRepairJob 执行日志（手动触发回执）|
| 全程 | Prometheus | up 15/15、orders/user 业务指标有数据 |
