# G1-05 HMAC 签名用例

> 组：G1 认证与用户 | 网关：gateway(19000) | 依赖：G1-03 登录（拿 {hmacSecret}/{accessToken}）
> 时间引用：矩阵 #10（timestamp 5min）、#11（nonce）、#12（secret 7 天）

## 代码实证（2026-08-12）
- 签名串：`signStr = method + path + timestamp + nonce`（path = 原始 URI path，无 strip）
- 算法：HMAC-SHA256，hex 输出（per-session secret）
- nonce：Redis `myxhs:gateway:nonce:{nonce}` **SET NX EX 原子，TTL=5min**（防重放窗口）
- timestamp：±5min（TIMESTAMP_TOLERANCE_MS）
- **body 不参与签名**（P2-10 记录项——本轮实证）
- 白名单：hmac-white-list（读操作免签，如 /api/user/me）
- 403 场景：签名不匹配/过期 timestamp/超前后/重放 nonce/secret 缺失

## 测试工具（python 签名器）
```python
import hmac, hashlib, time, uuid, base64
def sign(secret, method, path, ts, nonce):
    # 重要：服务端输出为 Base64（HmacSignatureFilter:237），非 hex！
    return base64.b64encode(
        hmac.new(secret.encode(), f"{method}{path}{ts}{nonce}".encode(), hashlib.sha256).digest()
    ).decode()
# 用法：ts=str(int(time.time()*1000)); nonce=uuid.uuid4().hex
# 头：X-Timestamp / X-Nonce / X-Signature / Authorization: Bearer {access}
```

## 用例清单

### G1-05-01 正常签名写操作
- **入口**：`PUT /api/user/me`（改昵称）+ 完整签名头
- **L1 断言**：200
- **L2**：`EXISTS myxhs:gateway:nonce:{nonce}` = 1（TTL≈300s，见 05-06）

### G1-05-02 签名不匹配
- **入口**：签名串任意字符篡改（如 method 改大写）
- **L1 断言**：**403** "签名校验失败：签名不匹配"

### G1-05-03 timestamp 过期（now-10min）
- **L1 断言**：**403**（"X-Timestamp 过期"类文案，实测记录）

### G1-05-04 timestamp 超前（now+10min）
- **L1 断言**：**403**

### G1-05-05 nonce 重放
- **前置**：05-01 已用 {nonce1}
- **入口**：同 {nonce1} + 同签名重发
- **L1 断言**：**403**（SET NX 失败——nonce 已存在）

### G1-05-06 nonce TTL（L2）
- **L2**：`TTL myxhs:gateway:nonce:{nonce1}` ≈ 300s（5min 窗口）

### G1-05-07 缺失签名头
- 缺 X-Signature / 缺 X-Timestamp / 缺 X-Nonce 三分支
- **L1 断言**：均 **403**

### G1-05-08 secret 过期（③ 操纵）
- **操纵**：`DEL myxhs:user:hmac:secret:{userId}`
- **入口**：正常签名请求
- **L1 断言**：**403** "HMAC 密钥已过期，请重新登录"
- **恢复**：重新登录拿新 secret（或此用例放在 04-08/04-11 之后自然状态）

### G1-05-09 body 篡改签名仍通过（P2-10 缺陷实证 → T-009）
- **前置**：05-01 正常签名
- **入口**：同签名头，**body 内容篡改**（如 nickname 改别的值）
- **L1 断言**：**200 通过（缺陷实证）**——body 不参与签名，篡改 body 不破坏签名
- **L2**：t_user.nickname 为篡改值（**证明防篡改缺失**）
- **说明**：登记 T-009（P2-10 关联）；修复需 body 摘要参与签名（如 signStr = method+path+ts+nonce+SHA256(body)）

### G1-05-10 白名单读操作免签
- **入口**：`GET /api/user/me` 无签名头（仅 Authorization）
- **L1 断言**：**200**（hmac-white-list 放行——读操作免签设计）

## 执行记录
| 用例 | 时间 | 结果 | 问题/备注 |
|---|---|---|---|
| G1-05-01~10 | | | |

## 断言关键词速查
- 403 签名失败（不匹配/过期/超前/重放/缺头/secret 过期）
- 200 正常（含白名单免签）
- T-009：body 篡改签名仍通过（缺陷实证）

## 深度 REVIEW 补充（2026-08-12）

### 关键修正（会直接导致测试失败）
- **签名输出格式 = Base64**（HmacSignatureFilter:237 `Base64.getEncoder()`）——签名器已修正；若前端用 hex 必然 403

### 代码实证补充
- **query 参数不参与签名**（signStr 无 query）→ **T-010**：带 query 的写操作可被篡改参数（user 域写端点暂无 query 传参，影响面转移至各业务组验证）
- nonce **无长度限制**（仅存在性校验）→ 观察：超长 nonce 可撑 Redis key（低危 DoS）
- 签名串 **method+path+ts+nonce 无分隔符拼接**（经典拼接歧义，实际受 5min 窗口约束碰撞概率极低）→ 观察
- Redis 存储 secret 带 Jackson 引号（L2 比对需 strip——代码已处理，测试仅提示）

### 新增用例

#### G1-05-11 hex 签名（格式错误实证）
- 用 hexdigest 签名的请求 → **403**（与 base64 不符——验证格式约束）

#### G1-05-12 timestamp 非数字
- X-Timestamp="abc" → **403** "X-Timestamp 格式错误"（NumberFormatException 处理，代码实证）

#### G1-05-13 超长 nonce（观察）
- nonce=10KB 随机串 → 403 或 500？——实测记录（预期：SET NX 正常 → 403 重放语义或成功——记录行为；若 500 则登记）

### 审查确认
- 未登录写操作：JWT 401 先拦（AuthFilter 先于 HMAC）✅
- 伪造 X-User-Id=受害者 + 自己 access → gateway 覆盖 X-User-Id（JWT subject）→ 取受害者 secret → 签名不匹配 403 ✅（P1-3 联动安全）
- 多设备：登录换新 secret → 旧设备签名 403（与 G1-04-11 一致）✅

## 第二轮深度 REVIEW（2026-08-12）

### 代码实证（校验顺序，HmacSignatureFilter:110-175）
1. 头存在性 → 2. timestamp 格式+窗口（**失败不消费 nonce**）→ 3. **nonce SETNX（签名校验前！）** → 4. 取 secret → 5. 验签
- **关键行为**：签名不匹配的请求**已消费 nonce**——同 nonce 重试会命中"重复请求"而非"签名不匹配"

### 用例行为修正
- **05-02 补充断言**：签名失败后，**同 nonce 重发 → 403 "重复请求"**（非"签名不匹配"）——客户端重试必须换 nonce
- 05-03/04（timestamp 失败）不消费 nonce → 同 nonce 修正 timestamp 后重发可成功（补充可选验证）

### 观察项
- **签名暴力尝试无速率限制**：HMAC 失败请求每 5min 窗口可无限尝试（每次换 nonce+ts）；签名空间 2^256 暴力不可行，唯一代价是 Redis nonce key 消耗（5min TTL 自清理）——低，观察
- **path 编码一致性**：getPath() 为解码路径，含中文/空格 URL 编码时前端签名需用同规范——测试工具用原始 ASCII path 无影响——观察
- 5min 双保险：重放窗口内 nonce 拦、窗口外 timestamp 拦 ✅
- 多设备/伪造 X-User-Id 均已确认 ✅（上轮）

### 审查确认（无问题）
- HMAC 失败响应统一 403（不可枚举）✅
- 时钟偏移容差 ±5min（测试环境本机时钟一致）✅
- nonce=UUID 空间碰撞可忽略 ✅

## 修复后同步（2026-08-12 T-009/010/011）
- **签名算法升级**：`signStr = method|path|query|ts|nonce|bodyHash`（竖线分隔 + query + SHA-256(body) hex），输出仍 Base64
- 签名器更新：
  ```python
  def sign(secret, method, path, query, ts, nonce, body):
      bh = hashlib.sha256(body if body else b"").hexdigest()
      return base64.b64encode(hmac.new(secret.encode(),
          f"{method}|{path}|{query}|{ts}|{nonce}|{bh}".encode(), hashlib.sha256).digest()).decode()
  ```
- **注意**：`PUT /api/user/me`、`/me/password` 在 hmac-white-list（**免签名**）——签名测试须用需签名端点（如 POST /api/user/address）
- **T-009 修复后**：body 篡改 → 403（原"签名仍通过"缺陷已修）
- **T-010 修复后**：query 参与签名
