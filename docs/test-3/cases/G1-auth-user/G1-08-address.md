# G1-08 地址管理用例

> 组：G1 认证与用户 | 服务：user(19001) | 入口：**gateway(19000)**
> 依赖：G1-03 登录（{accessToken}/{hmacSecret}/{userId}）——地址为**写操作**需 HMAC 签名
> 代码实证：写路径有 Redisson 锁（`myxhs:user:address:lock:{uid}`）；默认地址 Redis `myxhs:user:address:default:{uid}`；数据表 `t_user_address`

## 用例清单

### G1-08-01 创建地址
- **入口**：`POST /api/user/address`（HMAC 签名）
  ```json
  {"receiverName":"张三","receiverPhone":"13800138001","province":"广东省","city":"深圳市","detail":"科技园1号","isDefault":true}
  ```
- **L1 断言**：200；响应含 addressId
- **L2**：
  ```sql
  SELECT * FROM my_xhs_user.t_user_address WHERE user_id={userId};   # 1 行
  ```
  ```
  GET myxhs:user:address:default:{userId}   # = addressId（isDefault=true 生效）
  ```

### G1-08-02 地址列表 / 详情
- `GET /api/user/address/list` → 200 含地址
- `GET /api/user/address/{id}` → 200
- **越权检查**：`GET /api/user/address/{他人id}` → 404/拒绝（userId 校验——实测记录）

### G1-08-03 更新地址
- `PUT /api/user/address/{id}` 改 detail
- **L2**：t_user_address.detail 更新；默认地址变更逻辑（isDefault=true → default key 更新）

### G1-08-04 删除地址
- `DELETE /api/user/address/{id}`
- **L2**：行删除；若删的是默认地址 → `EXISTS myxhs:user:address:default:{uid}` = 0（UserAddressService:224 实证）

### G1-08-05 设置默认地址
- `PUT /api/user/address/{id}/default` → 200
- **L2**：`GET myxhs:user:address:default:{uid}` = 新 id；旧默认 key 被覆盖

### G1-08-06 参数校验
- 手机号格式错 / 字段缺失 → 40002（AddressCreateRequest @Valid）

### G1-08-07 并发创建（锁）
- 并发创建 2 条 isDefault=true → 默认地址唯一（锁串行，最终 1 个默认）
- **L2**：default key 存在且指向最终一条；t_user_address 2 行

## 执行记录
| 用例 | 时间 | 结果 | 问题/备注 |
|---|---|---|---|
| G1-08-01~07 | | | |

## 断言速查
- t_user_address 行 + `myxhs:user:address:default:{uid}` + 锁 key
- 越权：他人地址 404/拒绝（实测）

## 修复后同步（2026-08-12 实测字段名）
- AddressCreateRequest 字段：**district（区/县）** + **detailAddress（详细地址）**——非 detail；receiverName/receiverPhone/province/city/isDefault
- **签名**：地址为写操作，需新签名算法（见 G1-05）
