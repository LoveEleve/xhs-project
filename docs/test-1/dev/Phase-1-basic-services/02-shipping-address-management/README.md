# 收货地址管理

> 所属服务：my-xhs-user (9001) | 开发阶段：Phase-1 | 状态：⏳ 待开发

---

## 🎯 一、需求分析

### 1.1 业务场景

用户管理收货地址，支持增删改查、设置默认地址、地址数量上限控制。下单时需要快速获取默认地址，因此默认地址 ID 缓存在 Redis 中。

### 1.2 功能边界

| 功能项 | 是否实现 | 说明 |
|--------|---------|------|
| 地址 CRUD | ✅ | 新增/查询/更新/删除 |
| 默认地址设置 | ✅ | 唯一默认，设新默认时取消旧默认（事务保证） |
| 地址数量上限 | ✅ | 每用户最多 20 个地址 |
| 手机号脱敏 | ✅ | 返回 VO 中手机号中间 4 位打码（`138****1234`） |
| 默认地址缓存 | ✅ | Redis 缓存默认地址 ID，下单时快速获取 |
| 地址智能解析 | ❌ | 需对接第三方地址解析 API，不在 MVP 范围 |
| 地址经纬度 | ❌ | 需对接地图 API，不在 MVP 范围 |

### 1.3 数据量预估

| 指标 | 预估值 | 计算依据 |
|------|--------|----------|
| 地址总量 | 5000 万 | 1000 万用户 × 平均 5 个地址 |
| 日增量 | 5 万/天 | 新用户注册 + 老用户新增地址 |
| 查询峰值 QPS | 2000 | 下单高峰期频繁查询默认地址 |

---

## 🏗️ 二、架构设计

### 2.1 整体架构图

```
Client → Gateway(鉴权) → my-xhs-user(9001) → MySQL(t_address) + Redis(默认地址缓存)
                                                    │
                                                    └── 下单时 my-xhs-order 通过 Feign 调用获取默认地址
```

### 2.2 模块交互

| 调用方 | 被调用方 | 方式 | 场景 |
|--------|---------|------|------|
| my-xhs-order | my-xhs-user | Feign | 下单时获取用户默认收货地址 |
| my-xhs-user | MySQL | MyBatis-Plus | 地址 CRUD |
| my-xhs-user | Redis | RedisOperator | 默认地址 ID 缓存 |

### 2.3 核心流程时序图

**设置默认地址流程：**

```
1. Client → Gateway: PUT /api/user/address/{id}/default（鉴权通过）
2. Gateway → AddressService: 转发请求
3. AddressService → MySQL: 开启事务
4. AddressService → MySQL: UPDATE t_address SET is_default=0 WHERE user_id=? AND is_default=1（取消旧默认）
5. AddressService → MySQL: UPDATE t_address SET is_default=1 WHERE id=? AND user_id=?（设置新默认）
6. AddressService → MySQL: 提交事务
7. AddressService → Redis: DEL user:address:default:{userId}（删除旧缓存）
8. AddressService → Redis: SET user:address:default:{userId} = addressId（写入新缓存）
9. AddressService → Client: 返回成功
```

**下单时获取默认地址流程：**

```
1. OrderService → Redis: GET user:address:default:{userId}
2. 命中 → OrderService → AddressService(Feign): GET /api/user/address/{addressId}
3. 未命中 → OrderService → AddressService(Feign): GET /api/user/address/default
   → AddressService → MySQL: SELECT * FROM t_address WHERE user_id=? AND is_default=1
   → AddressService → Redis: SET user:address:default:{userId}（回填缓存）
```

---

## 🗄️ 三、数据库设计

### 3.1 表结构

```sql
-- 收货地址表（实际 DDL 来自 init-databases.sql）
CREATE TABLE IF NOT EXISTS t_address (
    id           BIGINT       NOT NULL COMMENT 'ID',
    user_id      BIGINT       NOT NULL COMMENT '用户ID',
    receiver     VARCHAR(64)  NOT NULL COMMENT '收货人',
    phone        VARCHAR(20)  NOT NULL COMMENT '联系电话',
    province     VARCHAR(64)  NOT NULL COMMENT '省',
    city         VARCHAR(64)  NOT NULL COMMENT '市',
    district     VARCHAR(64)  NOT NULL COMMENT '区',
    detail       VARCHAR(256) NOT NULL COMMENT '详细地址',
    is_default   TINYINT      NOT NULL DEFAULT 0 COMMENT '是否默认：0-否 1-是',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='收货地址表';
```

### 3.2 索引设计

| 索引名 | 字段 | 类型 | 使用场景 |
|--------|------|------|----------|
| `idx_user_id` | user_id | INDEX | 按用户 ID 查询地址列表（高频查询） |

> **为什么不加 `(user_id, is_default)` 联合索引？** 查询默认地址的场景走 Redis 缓存，极少回源 DB。单字段索引 `idx_user_id` 已覆盖地址列表查询。

---

## 🔑 四、缓存设计

### 4.1 Redis Key 规范

| Key Pattern | 类型 | 过期时间 | 说明 |
|-------------|------|----------|------|
| `user:address:default:{userId}` | String | 30min | 默认地址 ID（下单时高频查询） |

### 4.2 缓存更新策略

| 操作 | 策略 | 说明 |
|------|------|------|
| 读默认地址 | Cache Aside | 先查 Redis → Miss → 查 DB → 写 Redis（30min TTL） |
| 设置默认地址 | 先更新 DB → 再更新 Redis | 事务内更新 DB，事务提交后更新 Redis |
| 删除地址 | 先更新 DB → 再删 Redis | 如果删除的是默认地址，删除 Redis 缓存 |

### 4.3 缓存异常处理

| 问题 | 解决方案 |
|------|----------|
| Redis 不可用 | 降级直接查 DB（默认地址查询 QPS 不高，DB 可承受） |
| 缓存与 DB 不一致 | 设置默认地址时先更新 DB 再更新 Redis；TTL 30min 兜底 |

---

## 📡 五、接口设计

### 5.1 接口列表

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| GET | `/api/user/address/list` | 获取地址列表 | ✅ |
| GET | `/api/user/address/{id}` | 获取地址详情 | ✅ |
| GET | `/api/user/address/default` | 获取默认地址 | ✅ |
| POST | `/api/user/address` | 新增地址 | ✅ |
| PUT | `/api/user/address/{id}` | 更新地址 | ✅ |
| DELETE | `/api/user/address/{id}` | 删除地址 | ✅ |
| PUT | `/api/user/address/{id}/default` | 设置默认地址 | ✅ |

### 5.2 请求/响应示例

**新增地址**

```http
POST /api/user/address
Content-Type: application/json
Authorization: Bearer {accessToken}

{
  "receiver": "张三",
  "phone": "13800138000",
  "province": "广东省",
  "city": "深圳市",
  "district": "南山区",
  "detail": "科技园南区XX栋XX号",
  "isDefault": true
}
```

```json
{
  "code": 200,
  "msg": "新增成功",
  "data": {
    "id": 100001
  }
}
```

**获取地址列表**

```http
GET /api/user/address/list
Authorization: Bearer {accessToken}
```

```json
{
  "code": 200,
  "msg": "success",
  "data": [
    {
      "id": 100001,
      "receiver": "张三",
      "phone": "138****8000",
      "province": "广东省",
      "city": "深圳市",
      "district": "南山区",
      "detail": "科技园南区XX栋XX号",
      "isDefault": true
    },
    {
      "id": 100002,
      "receiver": "李四",
      "phone": "139****9000",
      "province": "北京市",
      "city": "北京市",
      "district": "朝阳区",
      "detail": "望京SOHO XX号",
      "isDefault": false
    }
  ]
}
```

> **注意**：返回的 `phone` 字段已脱敏（中间 4 位打码），原始手机号不暴露给前端。

---

## 💻 六、核心代码实现

### 6.1 新增地址（含数量上限校验）

```java
/**
 * 新增收货地址
 * 关键点：数量上限校验 + 首个地址自动设为默认
 */
@Override
@Transactional(rollbackFor = Exception.class)
public Long addAddress(Long userId, AddressCreateRequest request) {
    // 1. 数量上限校验（每用户最多20个）
    long count = addressMapper.selectCount(
            new LambdaQueryWrapper<Address>()
                    .eq(Address::getUserId, userId)
                    .eq(Address::getDeleted, 0));
    if (count >= 20) {
        throw new BizException(BizErrorCode.ADDRESS_LIMIT_EXCEEDED,
                "收货地址最多20个，请删除不常用的地址");
    }

    // 2. 构建地址实体
    Address address = new Address();
    address.setId(idGenerator.nextId());
    address.setUserId(userId);
    address.setReceiver(request.getReceiver());
    address.setPhone(request.getPhone());
    address.setProvince(request.getProvince());
    address.setCity(request.getCity());
    address.setDistrict(request.getDistrict());
    address.setDetail(request.getDetail());

    // 3. 如果是第一个地址，或者明确设为默认
    if (count == 0 || Boolean.TRUE.equals(request.getIsDefault())) {
        // 取消旧默认
        addressMapper.cancelDefault(userId);
        address.setIsDefault(1);
    } else {
        address.setIsDefault(0);
    }

    // 4. 入库
    addressMapper.insert(address);

    // 5. 如果设为默认，更新 Redis 缓存
    if (address.getIsDefault() == 1) {
        redisOperator.set("user:address:default:" + userId,
                String.valueOf(address.getId()), 30, TimeUnit.MINUTES);
    }

    return address.getId();
}
```

### 6.2 设置默认地址（事务保证唯一默认）

```java
/**
 * 设置默认地址
 * 关键点：事务内先取消旧默认→再设新默认，保证唯一性
 */
@Override
@Transactional(rollbackFor = Exception.class)
public void setDefault(Long userId, Long addressId) {
    // 1. 校验地址归属
    Address address = addressMapper.selectById(addressId);
    if (address == null || !address.getUserId().equals(userId)) {
        throw new BizException(BizErrorCode.ADDRESS_NOT_FOUND);
    }

    // 2. 事务内操作：取消旧默认 + 设新默认
    addressMapper.cancelDefault(userId);  // UPDATE SET is_default=0 WHERE user_id=? AND is_default=1
    addressMapper.setDefault(addressId);  // UPDATE SET is_default=1 WHERE id=?

    // 3. 更新 Redis 缓存（事务提交后）
    redisOperator.set("user:address:default:" + userId,
            String.valueOf(addressId), 30, TimeUnit.MINUTES);
}
```

### 6.3 删除地址（级联处理默认地址）

```java
/**
 * 删除地址（逻辑删除）
 * 关键点：如果删除的是默认地址，需要清除 Redis 缓存
 */
@Override
@Transactional(rollbackFor = Exception.class)
public void deleteAddress(Long userId, Long addressId) {
    Address address = addressMapper.selectById(addressId);
    if (address == null || !address.getUserId().equals(userId)) {
        throw new BizException(BizErrorCode.ADDRESS_NOT_FOUND);
    }

    // 逻辑删除
    addressMapper.logicDelete(addressId);

    // 如果删除的是默认地址，清除 Redis 缓存
    if (address.getIsDefault() == 1) {
        redisOperator.delete("user:address:default:" + userId);
    }
}
```

### 6.4 手机号脱敏

```java
/**
 * 地址 VO 转换 — 手机号脱敏
 * 138****8000
 */
public AddressVO toVO(Address address) {
    AddressVO vo = new AddressVO();
    BeanUtils.copyProperties(address, vo);
    // 手机号脱敏：保留前3位和后4位
    if (StringUtils.isNotBlank(address.getPhone()) && address.getPhone().length() >= 11) {
        vo.setPhone(address.getPhone().substring(0, 3) + "****"
                + address.getPhone().substring(7));
    }
    return vo;
}
```

---

## ⚖️ 七、方案对比

### 7.1 默认地址存储：Redis 缓存 vs 每次查 DB

| 维度 | Redis 缓存默认地址 ID（✅ 选定） | 每次查 DB |
|------|-------------------------------|----------|
| 性能 | O(1) 读取，毫秒级 | 需要 WHERE user_id=? AND is_default=1 |
| 一致性 | 需要维护缓存更新 | 强一致 |
| 适用场景 | 下单高频查询 | 低频场景 |

**选择理由**：下单时必须获取默认地址，高峰期 QPS 可达 2000+。Redis 缓存将 DB 查询降为 0，显著降低 DB 压力。

### 7.2 地址数量限制：应用层校验 vs DB 触发器

| 维度 | 应用层 COUNT 校验（✅ 选定） | DB 触发器 |
|------|---------------------------|----------|
| 可维护性 | 业务逻辑清晰，易修改上限 | 触发器隐藏逻辑，难调试 |
| 性能 | 一次 COUNT 查询 | 每次 INSERT 触发 |
| 灵活性 | 可按用户等级设不同上限 | 固定逻辑 |

**选择理由**：应用层校验更灵活，未来可按 VIP 等级设置不同上限（普通用户 20 个，VIP 50 个）。

---

## 🐛 八、踩坑记录

### 8.1 设置默认地址并发问题

- **现象**：两个请求同时设置不同地址为默认，导致出现两个默认地址
- **原因**：两个事务同时执行 `cancelDefault` + `setDefault`，互相看不到对方的修改
- **解决**：`cancelDefault` 使用 `UPDATE ... WHERE is_default=1` 加行锁，或使用 `@DistributedLock` 按 userId 加锁
- **教训**：涉及"唯一性"的更新操作，必须考虑并发场景

### 8.2 删除默认地址后下单报错

- **现象**：用户删除默认地址后，下单时获取默认地址返回 null
- **原因**：删除默认地址后未清除 Redis 缓存，缓存中仍存储已删除地址的 ID
- **解决**：删除地址时检查 `is_default`，如果是默认地址则同步删除 Redis 缓存
- **教训**：删除操作必须考虑关联缓存的清理

---

## 📊 九、测试验证

### 9.1 功能测试

| 测试场景 | 输入 | 预期结果 | 通过 |
|----------|------|----------|------|
| 新增第1个地址 | 合法地址信息 | 自动设为默认 | ⬜ |
| 新增第2个地址（非默认） | isDefault=false | 非默认地址 | ⬜ |
| 新增第21个地址 | 已有20个地址 | 返回"地址数量已达上限" | ⬜ |
| 设置默认地址 | 已有地址 ID | 旧默认取消，新默认生效 | ⬜ |
| 删除非默认地址 | 非默认地址 ID | 逻辑删除成功 | ⬜ |
| 删除默认地址 | 默认地址 ID | 逻辑删除 + Redis 缓存清除 | ⬜ |
| 查询地址列表 | 已有多个地址 | 手机号脱敏返回 | ⬜ |
| 查询他人地址 | 其他用户的地址 ID | 返回"地址不存在" | ⬜ |
| 获取默认地址（缓存命中） | Redis 有缓存 | 直接返回，不查 DB | ⬜ |
| 获取默认地址（缓存未命中） | Redis 无缓存 | 查 DB + 回填缓存 | ⬜ |

### 9.2 关键场景验证

- [ ] 并发设置默认地址：两个请求同时设置不同地址为默认，最终只有1个默认
- [ ] 删除默认地址后获取默认地址：返回 null 或提示"请设置默认地址"
- [ ] 地址归属校验：用户 A 不能操作用户 B 的地址

---

## 🎤 十、面试考察点

### Q1: 缓存和数据库一致性怎么保证？

**推荐回答思路**：

> 1. "我们用 Cache Aside 模式：读时先查缓存，未命中查 DB 后回填缓存"
> 2. "写时先更新 DB，再删除/更新缓存。对于默认地址这种简单场景，直接更新缓存即可"
> 3. "TTL 30 分钟兜底：即使缓存更新失败，最多 30 分钟后缓存自动过期，下次查询会重新从 DB 加载"
> 4. "对于一致性要求更高的场景（如商品价格），我们用延迟双删：先删缓存→更新 DB→延迟 500ms 再删缓存，覆盖并发读写的不一致窗口"

### Q2: 为什么用延迟双删而不是先删缓存再更新 DB？

**推荐回答思路**：

> 1. "先删缓存再更新 DB 有严重问题：线程 A 删缓存→线程 B 读缓存未命中→线程 B 读 DB 旧值写缓存→线程 A 更新 DB 新值——缓存是旧值，DB 是新值，不一致！"
> 2. "先更新 DB 再删缓存也有极小概率不一致，但概率远低于前者"
> 3. "延迟双删进一步降低风险：先删缓存→更新 DB→延迟 500ms 再删一次缓存，覆盖那个极小概率的不一致窗口"
> 4. "500ms 的延迟时间 = 主从复制延迟 + 业务逻辑执行时间的经验值"

---

## 📚 参考资料

| 资料 | 相关章节 | 参考内容 |
|------|----------|----------|
| 📄 phase-1/README.md | §3.2 | 收货地址完整设计（API/表/Redis Key/Java文件清单） |
| 📄 02-module-detailed-design.md | §3.5 | 收货地址功能/数量限制/默认地址/脱敏 |
| 📄 03-distributed-solutions.md | §1 | Cache Aside + 延迟双删 + Canal 三层保障 |
| 📄 21-cache-consistency-solution | 全文 | 缓存一致性方案专题 |
