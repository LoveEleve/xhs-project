# G1-03 登录用例

> 组：G1 认证与用户 | 服务：user(19001) | 入口：**gateway(19000)**
> 依赖：G1-01 验证码 + G1-02 注册用户（{username}/{password}）
> 时间引用：矩阵 #35（登录锁，P2-9 新语义）、#8（access 30min）、#12（HMAC secret）

## 代码实证（2026-08-12）
- 流程：验证码(GETDEL) → IP 锁检查(40203) → 账号锁检查(40106) → 查用户 → **status=0 → 40107 账号禁用** → BCrypt matches（错 → 40108 + 失败计数）→ 成功清计数 + generateTokenPair
- 错误码：40104/40105 验证码、40106 账号锁定、40107 账号禁用、40108 密码错误（用户不存在同码）、40203 IP 锁（"尝试过于频繁"）
- 失败计数：`myxhs:user:login:fail:{username}`、`fail:ip:{ip}`、`fail:ips:{username}`（均 30min）
- **锁定判定（P2-9）**：failCount≥5 **且** distinctIps≥2 → 账号锁 15min（锁后清 fail/ips）；单 IP≥20 → IP 锁 15min
- 成功路径：`generateTokenPair` → access/refresh JWT + `user:token:access:{jti}`/`refresh:{jti}` + `user:hmac:secret:{uid}`（7 天）

## 用例清单

### G1-03-01 登录成功（全断言）
- **前置**：G1-02-01 用户 {username}/{password}；新验证码
- **入口**：`POST /api/user/auth/login`（body: username/password/captchaKey/captchaCode + **X-Forwarded-For 模拟真实来源**——gateway 会覆盖为真实 IP）
- **L1 断言**：业务码 200；`data.accessToken`/`data.refreshToken`/`data.hmacSecret` 均非空
- **L2 数据验证**（关键）：
  ```bash
  # JWT 声明解码（access: subject=userId, type=access; refresh: type=refresh）
  python3 -c "
  import base64, json
  t='{accessToken}'
  p=t.split('.')[1]+'='*((4-len(t.split('.')[1])%4)%4)
  print(json.loads(base64.urlsafe_b64decode(p)))
  "
  redis-cli -a 'Xhs@2026#Redis' EXISTS myxhs:user:token:access:{jti}     # = 1
  redis-cli -a 'Xhs@2026#Redis' TTL  myxhs:user:token:access:{jti}     # ~1800s（30min）
  redis-cli -a 'Xhs@2026#Redis' EXISTS myxhs:user:token:refresh:{jti}    # = 1（7 天）
  redis-cli -a 'Xhs@2026#Redis' EXISTS myxhs:user:hmac:secret:{uid}      # = 1（7 天）
  redis-cli -a 'Xhs@2026#Redis' EXISTS myxhs:user:login:fail:{username}  # = 0（失败计数已清）
  ```
- **🔍 人工观察**：SkyWalking login trace（gateway→user：captcha→redis→db→token 生成 span）；Grafana user 服务 QPS

### G1-03-02 密码错误 → 40108 + 计数递增
- **前置**：G1-03-01 清理后（fail key=0 基线）
- **入口**：正确验证码 + 错密码
- **L1 断言**：业务码 **40108**（文案"用户名或密码错误"）
- **L2**：
  ```
  GET myxhs:user:login:fail:{username}      # = 1
  GET myxhs:user:login:fail:ip:{ip}         # = 1
  SMEMBERS myxhs:user:login:fail:ips:{username}  # 含本机 IP
  TTL myxhs:user:login:fail:{username}      # ~1800s（30min 窗口）
  ```

### G1-03-03 用户不存在 → 40108（防枚举同码）
- **入口**：不存在的 username + 任意密码
- **L1 断言**：**40108**（与密码错同码同文案——防枚举 ✅ 实证）

### G1-03-04 验证码错误 → 40104（复用 G1-01）

### G1-03-05 单 IP 5 次失败 → 账号不锁（P2-9 分支一）
- **前置**：连续 5 次错密码（同 IP，本机固定 IP）
- **L1 断言**：5 次均 40108；**第 6 次用正确密码 → 200 登录成功**（账号未被锁）
- **L2**：`GET myxhs:user:login:fail:{username}` = 5；`EXISTS myxhs:user:login:lock:{username}` = 0
- **说明**：单 IP 只累计不锁账号（防单源攻击者反锁受害者）——P2-9 核心设计

### G1-03-06 多 IP ≥2 + 失败≥5 → 账号锁（P2-9 分支二，③ 数据操纵）
- **前置**：G1-02-01 新用户 {username2}；基线清零
- **操纵**（模拟"第二个来源 IP"，因 gateway 会覆盖 XFF 无法真实伪造）：
  ```
  # 先真实失败 4 次（同 IP）→ failCount=4, ips={真实IP}
  # 手动注入第二 IP 证据：
  redis-cli -a 'Xhs@2026#Redis' SADD myxhs:user:login:fail:ips:{username2} 8.8.8.8
  redis-cli -a 'Xhs@2026#Redis' INCR myxhs:user:login:fail:{username2}      # 第 5 次（操纵计数）
  ```
- **入口**：第 5 次失败后，用**正确密码**登录
- **L1 断言**：业务码 **40106 账号锁定**（即使密码正确）
- **L2**：`EXISTS myxhs:user:login:lock:{username2}` = 1（15min TTL）；fail/ips key 已被清（锁定后删除）
- **兜底**：简化版——直接 `SET myxhs:user:login:lock:{username2} 1 EX 900` 模拟锁定态，验证锁定行为（40106）；上述完整路径验证判定逻辑

### G1-03-07 单 IP 20 次 → IP 锁（P2-9 分支三）
- **操纵**：`SET myxhs:user:login:fail:ip:{ip} 19 EX 1800`（预置 19 次）→ 再失败 1 次
- **L1 断言**：第 20 次失败后，**任意登录请求（含正确密码）→ 40203"尝试过于频繁"**（IP 锁先于账号锁检查）
- **L2**：`EXISTS myxhs:user:login:lock:ip:{ip}` = 1
- **注意**：IP 锁影响本机全部登录测试 → **测完立即解锁**：`DEL myxhs:user:login:lock:ip:{ip}`（执行记录标注）

### G1-03-08 解锁（③ 操纵）
- **操纵**：`DEL myxhs:user:login:lock:{username2}` + `DEL myxhs:user:login:lock:ip:{ip}`
- **入口**：正确密码登录 → **200**（锁解除）
- **L2**：锁 key = 0

### G1-03-09 登录成功清失败计数
- **前置**：G1-03-05 后 failCount=5
- **入口**：正确密码登录
- **L2**：`EXISTS myxhs:user:login:fail:{username}` = 0（clearLoginFail 删除 fail/ips key）

### G1-03-10 账号禁用（status=0）
- **操纵**：`UPDATE my_xhs_user.t_user SET status=0 WHERE username='{username2}'`（测后恢复 status=1）
- **入口**：正确密码登录
- **L1 断言**：业务码 **40107 账号已被禁用**
- **恢复**：status=1（执行记录标注）

## 执行记录
| 用例 | 时间 | 结果 | 问题/备注 |
|---|---|---|---|
| G1-03-01 | | | 成功+token 断言 |
| G1-03-02 | | | 计数递增 |
| G1-03-03 | | | 防枚举同码 |
| G1-03-04 | | | 验证码复用 |
| G1-03-05 | | | 单 IP 不锁 |
| G1-03-06 | | | 多 IP 锁定（操纵）|
| G1-03-07 | | | IP 锁（注意解锁）|
| G1-03-08 | | | 解锁 |
| G1-03-09 | | | 成功清计数 |
| G1-03-10 | | | 账号禁用（注意恢复）|

## 断言关键词速查
- 200 成功 / 40104-40105 验证码 / 40106 账号锁定 / 40107 账号禁用 / 40108 密码错误(含用户不存在) / 40203 IP 锁或操作频繁 / 40002 参数
