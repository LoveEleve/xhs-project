# G1-01 验证码用例

> 组：G1 认证与用户 | 服务：user(19001) | 入口：**gateway(19000)**
> 时间引用：矩阵 #7（验证码 5min/GETDEL 防并发消费）
> 执行原则：L1 断言业务码+响应体；L2 断言 Redis；无响应码通过论。

## 前置条件
- 无（本组基线用例，不依赖其他数据）

## 用例清单

### G1-01-01 获取验证码（正常）
- **入口**：`GET http://localhost:19000/api/user/auth/captcha`
- **L1 断言**：
  - HTTP 200
  - 业务码 200，`data.captchaKey` 非空（UUID 无横线 32 位）
  - `data.captchaImage` 非空（base64 图片串，前缀 data:image/png 或裸 base64——实测确认格式）
- **L2 数据验证**（关键，禁止只看响应）：
  ```
  redis-cli -a 'Xhs@2026#Redis' EXISTS myxhs:user:captcha:{captchaKey}   # = 1
  redis-cli -a 'Xhs@2026#Redis' TTL myxhs:user:captcha:{captchaKey}      # 300s（5min，误差 ±2s）
  redis-cli -a 'Xhs@2026#Redis' GET  myxhs:user:captcha:{captchaKey}     # 大写验证码（与图片一致，可人眼比对）
  ```
- **🔍 人工观察**：Kibana 搜 `logger_name:"com.myxhs.user.service.CaptchaService"` 见"生成成功, key=..."

### G1-01-02 验证码正常消费（注册/登录流程）
- **前置**：G1-01-01 取到 {captchaKey}
- **入口**：`POST http://localhost:19000/api/user/auth/register`（body 含 captchaKey/captchaCode）
- **L1 断言**：业务码 200（注册成功——详见 G1-02）
- **L2 数据验证**（GETDEL 防并发消费实证）：
  ```
  redis-cli -a 'Xhs@2026#Redis' EXISTS myxhs:user:captcha:{captchaKey}   # = 0（已被 GETDEL 原子消费）
  ```

### G1-01-03 验证码错误
- **前置**：G1-01-01 取到 {captchaKey}
- **入口**：`POST /api/user/auth/register`，`captchaCode` = 故意错 4 位
- **L1 断言**：业务码 **40104 CAPTCHA_ERROR**（"验证码错误或已过期"）
- **L2 数据验证**：
  ```
  EXISTS myxhs:user:captcha:{captchaKey}   # = 0（GETDEL 已消费——错误码也消费！）
  ```
- **边界确认**：错误验证码同样被消费（防暴力尝试），二次用同 key 提交 → **40105**（见 01-04）

### G1-01-04 验证码二次使用（防并发消费）
- **前置**：G1-01-01 取到 {captchaKey}，先用错码触发一次消费（01-03）
- **入口**：`POST /api/user/auth/register`，同 {captchaKey} + 正确码
- **L1 断言**：业务码 **40105 CAPTCHA_EXPIRED**（key 已被消费，视为过期）
- **L2 数据验证**：EXISTS = 0（key 不存在）
- **说明**：这是 GETDEL 的核心价值——同码不能二次使用（防重放）

### G1-01-05 验证码过期（时间操纵，矩阵 #7 主③）
- **前置**：G1-01-01 取到 {captchaKey}
- **操纵**（不等待 5min）：
  ```
  redis-cli -a 'Xhs@2026#Redis' DEL myxhs:user:captcha:{captchaKey}
  ```
- **入口**：`POST /api/user/auth/register`，用该 key + 正确码
- **L1 断言**：业务码 **40105 CAPTCHA_EXPIRED**
- **兜底**：不删 key，改 TTL：`EXPIRE myxhs:user:captcha:{captchaKey} 1` 后等 2s（等价）

### G1-01-06 缺失参数
- **入口**：`POST /api/user/auth/register`，body 缺 captchaKey 或 captchaCode
- **L1 断言**：业务码 **40002**（参数校验失败——"验证码不能为空"实证见 8/12 日志）
- **说明**：RegisterRequest @NotBlank 校验

## 执行记录
| 用例 | 时间 | 结果 | 问题/备注 |
|---|---|---|---|
| G1-01-01 | | | |
| G1-01-02 | | | |
| G1-01-03 | | | |
| G1-01-04 | | | |
| G1-01-05 | | | |
| G1-01-06 | | | |

## 断言关键词速查
- 40104 验证码错误（输入错误，key 被消费）
- 40105 验证码过期（key 不存在/已消费/超时）
- 40002 参数校验失败（缺失）

## 深度 REVIEW 补充（2026-08-12）

### 代码实证（安全/实现细节）
- 验证码：**4 位** × 字符集 `"23456789ABCDEFGHJKLMNPQRSTUVWXYZ"`（31 字符，去除 0/1/I/O 易混淆）→ **31^4 ≈ 92 万组合**
- 随机源：**SecureRandom** ✅（不可预测）
- 图片：6 条干扰线 + 随机颜色 + 背景噪点（基础防 OCR）
- GETDEL 消费：错误码也消费 → 每试错一次需重新取码（**防爆破设计有效**）
- **观察点 T-1**：captcha 生成接口**无 @RateLimit**（高频取码可刷 Redis key，5min TTL 自清理——低危，记录不阻塞）
- **观察点 T-2**：验证码响应**无 Cache-Control: no-store**（规范上应加，低危）

### 新增用例

#### G1-01-07 验证码格式与图片断言
- **L1**：captchaKey = 32 位 UUID（无横线，正则 `^[0-9a-f]{32}$`）
- **L2 图片魔数**（自动断言，不依赖人眼）：
  ```python
  python3 -c "
  import base64, json, urllib.request
  r = json.load(urllib.request.urlopen('http://localhost:19000/api/user/auth/captcha'))
  img = base64.b64decode(r['data']['captchaImage'])
  print('PNG头:', img[:8].hex())  # 预期 89504e470d0a1a0a
  print('图片字节:', len(img))
  "
  ```
- **L2 字符集断言**：`redis-cli GET myxhs:user:captcha:{key}` 的值 ∈ 31 字符集且长度 4（`grep -E '^[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}$'`）

#### G1-01-08 并发消费（GETDEL 原子性）
- **前置**：取两个验证码 key（A/B），两个不同用户名注册
- **并发**：同时提交同 key A 的两次注册（`&` 并发 curl）
- **L1 断言**：恰好一个 200，另一个 **40105**（key 已被消费）
- **L2**：EXISTS myxhs:user:captcha:{A} = 0；t_user 只有一条新注册
- **兜底**：结果不确定性（并发时序）→ 重试 3 次，断言"每次恰好一个成功"

#### G1-01-09 连续取码（key 并存与清理）
- **前置**：连续取 3 个验证码
- **L2**：3 个 key 并存（无旧 key 失效逻辑——设计如此）；TTL 均为 300s
- **说明**：Redis 中验证码 key 数量 = 5min 窗口内取码数（正常量级无碍；与 T-1 关联）

## 执行记录补充
| 用例 | 时间 | 结果 | 问题/备注 |
|---|---|---|---|
| G1-01-07 | | | 图片魔数/字符集断言 |
| G1-01-08 | | | 并发消费原子性 |
| G1-01-09 | | | key 并存 |

## 发现问题登记（T- 系列，待确认后修复）
| # | 问题 | 级别 | 建议 |
|---|---|---|---|
| T-1 | captcha 生成接口无 RateLimit | 低 | 加 @RateLimit（如 10 次/60s/IP）|
| T-2 | 验证码响应无 Cache-Control: no-store | 低 | AuthController 加响应头 |
