# my-xhs-user 测试执行计划

> 16端点 | 链1 | 执行顺序 + 前置准备 + 异常场景

---

## 一、前置准备（测试开始前执行一次）

### 1.1 环境确认

```bash
# 确认 user 服务在线
curl -s "http://21.130.247.89:18848/nacos/v1/ns/instance/list?serviceName=my-xhs-user&namespaceId=my-xhs" | python3 -c "import json,sys; d=json.load(sys.stdin); print(f'user: {len(d[\"hosts\"])} instance')"

# 确认 Redis 可用
python3 -c "import redis; r=redis.Redis(host='21.130.247.89',port=6379,password='Xhs@2026#Redis'); r.ping(); print('redis OK')"

# 确认 MySQL 可用
mysql -h 21.130.247.89 -P 3306 -u root -p'Xhs@2026#MySQL' -e "SELECT 1" 2>/dev/null && echo "mysql OK"
```

### 1.2 创建测试用户

```bash
# 获取验证码
curl -s http://localhost:19000/api/user/auth/captcha > /tmp/cap.json
KEY=$(python3 -c "import json; print(json.load(open('/tmp/cap.json'))['data']['captchaKey'])")
CODE=$(grep "$KEY" /tmp/r_user.log | tail -1 | grep -oP 'code=\K\w+')

# 注册
curl -s -X POST http://localhost:19000/api/user/auth/register \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"chaintest_u1\",\"password\":\"Test@123456\",\"phone\":\"13900000001\",\"captchaKey\":\"$KEY\",\"captchaCode\":\"$CODE\"}" | python3 -m json.tool
```

### 1.3 获取测试 Token

```bash
# 登录
curl -s http://localhost:19000/api/user/auth/captcha > /tmp/cap.json
KEY=$(python3 -c "import json; print(json.load(open('/tmp/cap.json'))['data']['captchaKey'])")
CODE=$(grep "$KEY" /tmp/r_user.log | tail -1 | grep -oP 'code=\K\w+')

RESP=$(curl -s http://localhost:19000/api/user/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"chaintest_u1\",\"password\":\"Test@123456\",\"captchaKey\":\"$KEY\",\"captchaCode\":\"$CODE\"}")

TOKEN=$(echo "$RESP" | python3 -c "import json,sys; print(json.load(sys.stdin)['data']['accessToken'])")
echo "$TOKEN" > /tmp/test_token.txt
echo "Token saved: $(head -c 20 /tmp/test_token.txt)..."
```

### 1.4 注册第二个用户（用于block/关系测试）

```bash
curl -s http://localhost:19000/api/user/auth/captcha > /tmp/cap2.json
KEY2=$(python3 -c "import json; print(json.load(open('/tmp/cap2.json'))['data']['captchaKey'])")
CODE2=$(grep "$KEY2" /tmp/r_user.log | tail -1 | grep -oP 'code=\K\w+')

curl -s -X POST http://localhost:19000/api/user/auth/register \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"chaintest_u2\",\"password\":\"Test@123456\",\"phone\":\"13900000002\",\"captchaKey\":\"$KEY2\",\"captchaCode\":\"$CODE2\"}"
```

---

## 二、执行顺序

| 顺序 | 端点 | 依赖 | 产出文件 | 正常+异常 |
|:--:|------|------|------|:--:|
| 1 | U01-captcha | 无 | execution/user/U01-captcha.md | ✅ 正常 + ⚠️ 异常 |
| 2 | U14-register | U01 | execution/user/U14-register.md | ✅ 正常 + ⚠️ 异常 |
| 3 | U03-login | U01+U14 | execution/user/U03-login.md | ✅ 正常 + ⚠️ 异常 |
| 4 | U06-me | U03(Token) | execution/user/U06-me.md | ✅ 正常 + ⚠️ 异常 |
| 5 | U07-update-me | U03 | execution/user/U07-update-me.md | ✅ 正常 |
| 6 | U09-user-info | U03 | execution/user/U09-user-info.md | ✅ 正常 |
| 7 | U16-change-password | U03 | execution/user/U16-change-password.md | ✅ 正常 |
| 8 | U10-add-address | U03 | execution/user/U10-add-address.md | ✅ 正常 |
| 9 | U11-list-address | U03+U10 | execution/user/U11-list-address.md | ✅ 正常 |
| 10 | U12-update-address | U03+U10 | execution/user/U12-update-address.md | ✅ 正常 |
| 11 | U13-delete-address | U03+U10 | execution/user/U13-delete-address.md | ✅ 正常 + ⚠️ 异常 |
| 12 | U04-refresh | U03 | execution/user/U04-refresh.md | ✅ 正常 + ⚠️ 异常 |
| 13 | U-B1-block | U03+第二个用户 | execution/user/U-B1-block.md | ✅ 正常 + ⚠️ 异常 |
| 14 | U-B2-unblock | U03+U-B1 | execution/user/U-B2-unblock.md | ✅ 正常 |
| 15 | U-B3-block-list | U03+U-B1 | execution/user/U-B3-block-list.md | ✅ 正常 |
| 16 | U05-logout | U03 | execution/user/U05-logout.md | ✅ 正常 + ⚠️ 异常 |

---

## 三、异常场景测试

### 3.1 认证类

| 场景 | 端点 | curl | 预期 |
|------|------|------|------|
| 缺少 JWT | U06-me | `curl http://localhost:19000/api/user/me` | 401 |
| Token 过期 | U06-me | 用已过期 token | 401 |
| 无效 Token | U06-me | `curl -H "Authorization: Bearer invalid"` | 401 |
| 缺少 X-User-Id(直连) | U06-me | `curl http://localhost:19001/api/user/me` | 401(Gateway未注入) |

### 3.2 业务规则类

| 场景 | 端点 | curl | 预期 |
|------|------|------|------|
| 重复注册 | U14-register | 再次 POST 相同 username/phone | "用户已存在" |
| 登录锁定 | U03-login | 连续 5 次错误密码 → 正确密码 | "账号已锁定" |
| 验证码复用 | U03-login | 用同一个 captchaKey 登录两次 | "验证码错误"(原子消费) |
| 地址超20条 | U10-add-address | 连续 21 次 POST | "达到最大地址数" |
| 屏蔽自己 | U-B1-block | POST /block/{自己的userId} | "不能屏蔽自己" |

### 3.3 数据一致性

| 场景 | 端点 | 验证 | 预期 |
|------|------|------|------|
| 更新后缓存一致 | U07-update-me | 更新 nickname → 立即 GET U06 | 返回新 nickname |
| 删除默认地址升级 | U13-delete-address | 删唯一地址(默认) → GET U11 | 无默认地址, 返回null |
| 登出后Token失效 | U05-logout | 登出后用原 token → GET U06 | 401 |

### 3.4 并发安全（可选，生产验证）

| 场景 | 端点 | 验证 | 预期 |
|------|------|------|------|
| 并发注册 | U14-register | 两终端同时 POST 相同 phone | 一个成功，一个 DuplicateKeyException |
| 并发验证码消费 | U03-login | 两终端同时 POST 相同 captchaKey | 一个成功，一个"验证码错误" |

---

## 四、每端点产出模板

**必须使用执行模板**: `execution/TEMPLATE.md` — 严格遵循方法论 L1→L4 分层验证模型:

- L1: 业务逻辑(正常路径+异常路径矩阵+状态流转验证)
- L2: 数据正确性(HTTP/Redis/MySQL/MQ 四层, 每层含实际值和预期值对比)
- L3: 生产级质量(九透镜检查表, 每项含证据)
- L4: 链级可观测性(每链完成后 Prometheus 快照+MQ 积压+SkyWalking+Kibana)

**异常场景必须覆盖 4 类**:
1. 认证异常 (无JWT/过期/无效 Token)
2. 业务规则违反 (重复操作/上限/幂等校验)
3. 数据一致性 (更新后立即读/删除后级联/缓存同步)
4. 并发安全 (可选, 标注难度)

---

## 五、测试数据速查

| 数据 | 值 | 来源 |
|------|------|------|
| Token | `cat /tmp/test_token.txt` | U03 登录后保存 |
| USER_ID | JWT subject 解码 | U06 返回的 id 字段 |
| ADDRESS_ID | U10 返回的 addressId | U10 创建地址 |
| PEER_USER_ID | chaintest_u2 的 id | 第二个用户 U06 返回 |
