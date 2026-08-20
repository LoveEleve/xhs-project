# G1-02 注册用例

> 组：G1 认证与用户 | 服务：user(19001) | 入口：**gateway(19000)**
> 依赖：G1-01（验证码，每次注册先取新码）
> 时间引用：矩阵 #7（验证码）；P2-15（事务收窄后验证）

## 代码实证（2026-08-12）
- 校验：username 4~32 位 `[a-zA-Z0-9_]`；password 6~64；phone `1[3-9]\d{9}`；captchaKey/code 必填（@NotBlank → 40002）
- 流程：验证码校验(GETDEL) → 注册锁(`myxhs:user:register:lock:{username}` 3s wait/10s lease) → username/phone 查重 → BCrypt → **insert 在 TransactionTemplate 内（P2-15）**
- 业务码：10002 用户名已存在 / 10003 手机号已注册 / 10005 用户名或手机号已存在 / 40203 操作频繁

## 用例清单

### G1-02-01 注册成功（全字段）
- **前置**：G1-01-01 取验证码（记 {captchaKey}，验证码 = Redis GET 值）
- **入口**：`POST http://localhost:19000/api/user/auth/register`
  ```json
  {"username":"test_reg_{ts}","password":"Test@123456","phone":"13800138001","captchaKey":"{captchaKey}","captchaCode":"{code}"}
  ```
- **L1 断言**：业务码 200
- **L2 数据验证**（关键）：
  ```sql
  SELECT id, username, password, status, deleted FROM my_xhs_user.t_user WHERE username='test_reg_{ts}';
  -- 断言：1 行；password 以 $2a$ 开头（BCrypt 非明文）；status=1；deleted=0
  ```
  ```
  redis-cli -a 'Xhs@2026#Redis' EXISTS myxhs:user:register:lock:{username}   # = 0（锁已释放）
  redis-cli -a 'Xhs@2026#Redis' EXISTS myxhs:user:captcha:{captchaKey}      # = 0（验证码已消费）
  ```
- **🔍 人工观察**：SkyWalking 注册 trace（gateway→user：验证码 Redis→查重 DB→insert DB span 完整）；Kibana "用户注册成功" 日志

### G1-02-02 用户名重复
- **前置**：G1-02-01 注册的用户 {username}
- **入口**：同 username 再注册（新验证码）
- **L1 断言**：业务码 **10002** 用户名已存在
- **L2**：t_user 该 username 仍 1 行（无重复插入）；锁已释放
- **边界**：查重路径（selectCount）先拦，或 DB 唯一索引兜底（DuplicateKeyException）——**两条路径都要覆盖**：并发场景走 DB 唯一索引（见 02-08）

### G1-02-03 手机号重复
- **前置**：G1-02-01 使用的 phone
- **入口**：不同 username + 同 phone
- **L1 断言**：业务码 **10003** 手机号已注册

### G1-02-04 参数校验（4 个分支）
| 分支 | 请求改动 | 预期 |
|---|---|---|
| 用户名 <4 位 | username="ab" | 40002（"用户名长度4~32位"）|
| 用户名含特殊字符 | username="a!b@c" | 40002（"只能包含字母数字下划线"）|
| 密码 <6 位 | password="123" | 40002（"密码长度6~64位"）|
| 手机号格式错 | phone="12345" | 40002（"手机号格式不正确"）|
- **L2**：t_user 无新增行；Redis 无残留锁/验证码 key（校验失败应在锁之前？——**实测确认**：校验失败是否消费验证码？若 @Valid 在 controller 层先拦 → 验证码未消费（key 仍在）；若 service 内校验 → 已消费。以实测为准记录）

### G1-02-05 验证码错误/过期（复用 G1-01）
- 错误码 40104 / 过期 40105（详见 G1-01-03/04/05，注册入口同断言）

### G1-02-06 注册锁释放与并发注册（同用户名）
- **并发**：两个不同验证码 + 同 username 同时提交（`&` 并发 curl）
- **L1 断言**：恰好一个 200；另一个 **10002**（查重命中）或 **40203**（锁竞争失败）——两者之一，以实测记录
- **L2**：t_user 该 username 1 行；`EXISTS myxhs:user:register:lock:{username}` = 0（**锁必须释放**——防泄漏）
- **说明**：锁 3s wait/10s lease，并发窗口内第二个请求等锁或直接失败

### G1-02-07 注册成功后立即登录
- **前置**：G1-02-01 新用户
- **入口**：`POST /api/user/auth/login`（新验证码 + 该用户凭据）→ 见 G1-03
- **L1 断言**：登录成功（验证注册数据完整可用：BCrypt 校验通过、status=1 放行）

### G1-02-08 唯一索引兜底（DB 层，绕过查重）
- **说明**：理论上查重先拦；唯一索引是并发兜底。**直接验证 DB 约束**：
  ```sql
  -- 手工插入同 username（模拟并发穿透查重的窗口）
  INSERT INTO my_xhs_user.t_user (id, username, password, status, deleted, created_at, updated_at)
  VALUES (999999, 'test_reg_{ts}', '$2a$10$xxxx', 1, 0, NOW(), NOW());
  -- 预期：DuplicateKeyException（uk_username）
  -- 恢复：DELETE WHERE id=999999（测试后清理）
  ```
- **L2 断言**：插入被唯一索引拒绝（错误 1062）；验证后清理
- **说明**：验证 t_user 唯一索引存在（`SHOW INDEX FROM t_user` 确认 uk_username/uk_phone）

## 执行记录
| 用例 | 时间 | 结果 | 问题/备注 |
|---|---|---|---|
| G1-02-01 | | | 全字段成功 |
| G1-02-02 | | | 用户名重复 |
| G1-02-03 | | | 手机号重复 |
| G1-02-04 | | | 4 参数分支（记录验证码消费行为）|
| G1-02-05 | | | 验证码复用 |
| G1-02-06 | | | 并发+锁释放 |
| G1-02-07 | | | 注册后登录 |
| G1-02-08 | | | 唯一索引兜底 |

## 断言关键词速查
- 200 成功 / 10002 用户名已存在 / 10003 手机号已注册 / 10005 或 / 40203 锁失败 / 40002 参数校验 / 40104-40105 验证码

## 深度 REVIEW 补充（2026-08-12）

### 新发现
**T-003【中】register 接口无 RateLimit**：注册链路（captcha+register）均无限流——攻击者可自动"取码→注册"批量创建垃圾账号（验证码防爆破不防批量注册）。建议：register 加 `@RateLimit(prefix="myxhs:register", maxRequests=5, windowSeconds=60, perUser=false)`。（已登记 review/ISSUES.md）

### 代码实证补充
- t_user **uk_username/uk_phone 唯一索引**存在；collation **utf8mb4_unicode_ci（不区分大小写）** → `Test_user` 与 `test_user` 视为同一用户名
- phone **可空**（无 @NotBlank，唯一索引对 NULL 不生效）
- 注册无其他 Redis/MQ 副作用（仅验证码消费+锁+insert）
- 默认值：nickname=username、gender=0、status=1（代码实证）

### 新增用例

#### G1-02-09 无 phone 注册（可选字段）
- body 不含 phone → 200 成功
- **L2**：t_user.phone = NULL；uk_phone 对 NULL 不拦截（可多个无 phone 用户）

#### G1-02-10 用户名大小写（collation 实证）
- 注册 `Test_User_{ts}` 成功后，用 `test_user_{ts}`（小写）再注册
- **L1 断言**：业务码 **10002**（utf8mb4_unicode_ci 不区分大小写，唯一索引拦截）
- **L2**：t_user 仅 1 行

#### G1-02-11 注册数据完整性（默认值断言）
- 注册成功后：
  ```sql
  SELECT nickname, gender, status, deleted, created_at, password
  FROM my_xhs_user.t_user WHERE username='test_reg_{ts}';
  ```
- 断言：nickname=username；gender=0；status=1；deleted=0；created_at 非空；**password 以 $2a$10$ 开头**（BCrypt cost=10）

#### G1-02-12 响应/日志无敏感泄露（审查类）
- 注册响应体：`R<Void>` 无密码/验证码字段 ✅（代码实证）
- 日志：`[注册] 用户注册成功, userId=..., username=...` 无密码 ✅（代码实证）
- 执行时抽查一次响应体与日志

### 测试数据管理策略（后续全部组遵守）
- 测试用户统一前缀：`test_reg_`（注册组）/ `test_usr_`（后续组复用）
- 每个用例记录 {username}/{userId} 到执行记录
- **执行后清理约定**：t_user 测试行可保留（演示环境无碍）或按 `DELETE FROM t_user WHERE username LIKE 'test_%'` 清理（**需用户确认**，默认保留）

## 执行记录补充
| 用例 | 时间 | 结果 | 备注 |
|---|---|---|---|
| G1-02-09 | | | 无 phone |
| G1-02-10 | | | 大小写冲突 |
| G1-02-11 | | | 默认值断言 |
| G1-02-12 | | | 泄露审查 |

## 第二轮深度 REVIEW（2026-08-12）

### 代码实证（登录侧防枚举确认）
- login 用户不存在 → **PASSWORD_ERROR(40108) "用户名或密码错误"**（与密码错同码同文案）✅ 防枚举设计正确（UserService:196）
- 但：ACCOUNT_LOCKED(40106) 会暴露"账号存在"（锁定提示，业界常见权衡，观察点）

### 新增用例

#### G1-02-13 并发不同用户名同 phone（uk_phone 竞态兜底）
- **并发**：两个不同 username + 同 phone 同时注册（锁 key 不同 → 锁不覆盖，靠 uk_phone）
- **L1 断言**：恰好一个 200；另一个 **10003**（查重或唯一索引兜底）
- **L2**：t_user 该 phone 仅 1 行
- **说明**：这是唯一索引兜底的真实竞态场景（02-08 是手工注入，本用例是并发实证）

#### G1-02-14 边界值
| 分支 | 值 | 预期 |
|---|---|---|
| username 恰 32 位 | "a"×32 | 200 |
| username 33 位 | "a"×33 | 40002 |
| password 恰 6 位 | "123456" | 200 |
| password 64 位 | "a"×64 | 200 |
| password 65 位 | "a"×65 | 40002 |
| phone 130 开头 | "13000000001" | 200（1[3-9] 含 3）|
| phone 12x | "12000000001" | 40002 |
- **L2**：每个成功分支 t_user 对应行存在；失败分支无新增行

#### G1-02-15 注册耗时观察（P2-15 事务收窄效果）
- 记录注册接口耗时（`time curl`）：预期 <1s（BCrypt cost10 ~100ms 在事务外）
- **说明**：P2-15 后 insert 仅事务内——耗时不含 BCrypt 的事务占用；观察性记录（不设硬断言）

### 审查项（不注入，记录结论）
| 项 | 结论（代码实证）|
|---|---|
| Redis 故障时注册 | try-catch RedisUnavailableException → SERVICE_UNAVAILABLE（UserService:79）✅ 有设计 |
| DB 故障时注册 | insert 抛异常 → TransactionTemplate 回滚；非 DuplicateKeyException 走 500（无业务化）→ 观察点（演示环境可接受）|
| 登录枚举 | 统一 40108 ✅；ACCOUNT_LOCKED 暴露存在性（权衡）|

### 测试数据管理更新
- 02-13/02-14 产生多行测试用户（test_reg_ 前缀），清理约定同前
