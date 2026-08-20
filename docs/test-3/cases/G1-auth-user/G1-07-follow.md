# G1-07 关注/粉丝用例

> 组：G1 认证与用户 | 服务：**analytics(19003)**（/api/social/** 路由）| 入口：**gateway(19000)**
> 依赖：两个用户 {A}/{B}（G1-02 注册 + G1-03 登录，拿 token/hmacSecret）
> 时间引用：矩阵 #3（followCounterRepairJob，xxl id=3）

## 全链路数据格式（代码实证 2026-08-12）
- 关注写流程：Redis ZSet（**权威**）→ 同步落 MySQL t_follow → `SOCIAL_TOPIC:FOLLOW`（syncSend）→ counter 服务消费更新计数
- Redis：`myxhs:follow:list:{uid}`（ZSet，我关注的，member=目标ID，score=时间戳）、`myxhs:follow:fans:{uid}`（ZSet，粉丝）
- MySQL：`my_xhs_analytics.t_follow`（id/user_id/follow_user_id/created_at）
- 计数：`myxhs:counter:2:{uid}:7`（FOLLOWING=7）、`myxhs:counter:2:{uid}:6`（FANS=6）
- 管理端点：`/api/social/follow/internal/repair-counter/{userId}`（X-Admin-Call）
- followCounterRepairJob（xxl#3，每小时）：以 Redis ZSet 为准修复 counter（ZCARD）+ Redis↔MySQL 关系行差异

## 用例清单

### G1-07-01 A 关注 B（全链路验证）
- **入口**：`POST /api/social/follow/{B}`（A 的 token + HMAC 签名）
- **L1 断言**：200
- **L2 数据验证**（关键，四层）：
  ```bash
  # 1) Redis ZSet（权威）
  redis-cli -a 'Xhs@2026#Redis' ZSCORE myxhs:follow:list:{A} {B}     # 非空（关注时间戳）
  redis-cli -a 'Xhs@2026#Redis' ZSCORE myxhs:follow:fans:{B} {A}     # 非空（粉丝）
  # 2) MySQL 落库
  SELECT * FROM my_xhs_analytics.t_follow WHERE user_id={A} AND follow_user_id={B};   # 1 行
  # 3) MQ（🔍 人工观察 RocketMQ dashboard：SOCIAL_TOPIC tag=FOLLOW 消息）
  # 4) counter 计数（消费者处理后）
  redis-cli -a 'Xhs@2026#Redis' GET myxhs:counter:2:{A}:7     # A 的关注数 = 1
  redis-cli -a 'Xhs@2026#Redis' GET myxhs:counter:2:{B}:6     # B 的粉丝数 = 1
  ```
- **🔍 人工观察**：RocketMQ SOCIAL_TOPIC 消息 + SkyWalking 关注 trace（gateway→analytics→MQ→counter）

### G1-07-02/03 列表（A 关注列表 / B 粉丝列表）
- `GET /api/social/following/{A}`、`GET /api/social/follower/{B}`
- **L1**：200；列表含对方
- **L2**：与 ZSet 内容一致（reverseRangeWithScores 分页）

### G1-07-04 重复关注（幂等）
- **入口**：A 再次关注 B
- **L1 断言**：200 或业务码（zAdd 已存在——实测记录：重复关注是否报错/静默/幂等成功）
- **L2**：t_follow 仍 1 行；ZSet 无重复 member

### G1-07-05 A 取关 B
- **入口**：`DELETE /api/social/follow/{B}`
- **L2**：
  ```
  ZSCORE myxhs:follow:list:{A} {B}    # 空（已删）
  ZSCORE myxhs:follow:fans:{B} {A}    # 空
  SELECT COUNT(*) FROM t_follow WHERE user_id={A} AND follow_user_id={B};   # 0
  GET myxhs:counter:2:{A}:7     # 0（UNFOLLOW 消费后）
  GET myxhs:counter:2:{B}:6     # 0
  ```

### G1-07-06 关系/共同关注
- `GET /api/social/relation/{B}`（A 视角：是否已关注 B）
- `GET /api/social/common/{B}`（共同关注：A、B 共同关注了谁——需第三用户 C 辅助：A→C、B→C 后 common 含 C）
- **L2**：与 ZSet 交集一致

### G1-07-07 计数 vs ZSet 一致性（L2 对账）
- 关注/取关若干后：`ZCARD myxhs:follow:fans:{B}` == `GET myxhs:counter:2:{B}:6`（Redis 与 counter 一致——正常路径断言）

### G1-07-08 followCounterRepairJob 修复（xxl#3 手动触发）
- **操纵**（构造不一致）：
  ```
  # 不一致1：counter 计数错误
  redis-cli -a 'Xhs@2026#Redis' SET myxhs:counter:2:{B}:6 999
  # 不一致2：MySQL 缺失关系行（Redis 有但 t_follow 无）
  DELETE FROM my_xhs_analytics.t_follow WHERE user_id={A} AND follow_user_id={B};
  ```
- **触发**：xxl admin 手动触发 id=3（见时间矩阵 A 节）
- **L2 断言**：
  ```
  GET myxhs:counter:2:{B}:6        # = 1（ZCARD 覆盖修复）
  SELECT COUNT(*) FROM t_follow WHERE user_id={A} AND follow_user_id={B};   # = 1（关系行补全）
  ```
- **说明**：以 Redis ZSet 为准——修复方向正确性验证（Redis 权威模型）

### G1-07-09 管理端点 repair-counter（X-Admin-Call）
- **入口**：直连或 gateway？`POST /api/social/follow/internal/repair-counter/{B}` + `X-Admin-Call: {ADMIN_TOKEN}`（tokens.env 值）
- **L1 断言**：200（管理端点校验新 token——P-B4 后旧 token 应 403/401——**双断言**：旧 token 拒绝 + 新 token 放行）
- **L2**：counter 计数被修复

## 执行记录
| 用例 | 时间 | 结果 | 问题/备注 |
|---|---|---|---|
| G1-07-01~09 | | | |

## 断言关键词速查
- 四层验证：Redis ZSet（权威）/ t_follow / MQ SOCIAL_TOPIC / counter 计数 key
- 计数 key：`myxhs:counter:2:{uid}:7`(关注) / `:6`(粉丝)
- xxl#3 手动触发修复（ZCARD 覆盖 + 关系行）

## 深度 REVIEW 补充（2026-08-12）

### 代码实证（多项反转/确认）
1. **自关注校验已存在**（FollowService:75/143 `CANNOT_FOLLOW_SELF`）——原疑点不成立
2. **关注/取关限流已有**（FollowController:37/53 `20 次/60s/perUser`）；repair-counter 限流 2 次/60s（:148）
3. **t_follow 唯一索引 uk_user_follow(user_id, follow_user_id)** ✅——并发重复关注 DB 兜底（无双行）
4. **repair-counter 在 gateway white-list**（application.yml:371）→ **07-09 可走 gateway**（无需 JWT，服务端 X-Admin-Call 校验）——**用例修正：走 gateway**
5. **target 存在性校验缺失** → **T-013**（可关注不存在用户，幽灵关注）
6. **counter 计数为 MQ 异步消费（秒级延迟）**——07-01 查 counter 前 **sleep 1-2s**（测试注意，写入文档）

### 新增用例

#### G1-07-10 自关注拒绝
- A 关注 A 自己 → **CANNOT_FOLLOW_SELF**（业务码实测记录）
- **L2**：t_follow 无自关注行

#### G1-07-11 关注不存在用户（T-013 实证）
- A 关注 uid=88888888（不存在）→ 实测：**200（缺陷）还是拒绝？**——记录
- **L2**：follow:list:{A} 含 88888888；t_follow 落行（幽灵关注）
- **说明**：登记 T-013；清理：测试后取关或对账清理

#### G1-07-12 关注限流（20 次/60s）
- A 快速关注 21 个不同 target（或同 target 重复）→ 第 21 次 → **限流拦截**（业务码实测：40203 或自定义）
- **L2**：限流 key（`myxhs:social:follow:*` 类）存在

### 修正
- 07-01：counter 断言前标注 **sleep 1-2s**（MQ 消费延迟）
- 07-09：**走 gateway**（白名单放行）带 X-Admin-Call；旧 token 拒绝 + 新 token 放行断言不变

### G1-07-13/14 count 端点（读 counter key）
#### G1-07-13 follower/count
- **前置**：A 关注 B 后（B 粉丝=1）
- **入口**：`GET /api/social/follower/count/{B}`（公开？——gateway white-list 是否含 count——**实测**：无 token 可访问则公开）
- **L1 断言**：200；`total` = 1
- **L2**：与 `GET myxhs:counter:2:{B}:6` 一致（count 端点读 counter key——FollowService:365/374 实证）

#### G1-07-14 following/count
- **入口**：`GET /api/social/following/count/{A}` → total = 1（与 counter:2:{A}:7 一致）

## 修复后同步（2026-08-12 T-013/016/017/018）
- **07-11 反转**：关注不存在用户 → **10001 用户不存在**（T-013 已修，原"可关注幽灵"行为已改）
- **T-016 已修**：对方拉黑当前用户 → **10010 已被对方拉黑**（block 序列化统一 T-018）——新增用例：B 拉黑 A → A 关注 B → 10010；取消后 → 200
- **计数双 key**（代码实证）：analytics 本地 Lua 写 `myxhs:counter:user_following:{uid}`/`user_follower:{uid}`；counter 服务消费 MQ 写 `myxhs:counter:2:{uid}:7`/`:6`——**两套并存**，断言都查
- **T-017 修复后**：Feign 内部调用带 X-Internal-Call（关注校验链路 analytics→user 可用）
