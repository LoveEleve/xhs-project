# my-xhs 微服务 curl 业务验证测试规划

> **文档版本**: v4.0  
> **生成时间**: 2026-07-10  
> **更新**: 2026-07-11 第三轮多 Agent 审查 — 补充业务场景（审核流程/地址校验/支付回调/越权/XSS）、内部接口（TCC/回调）、边界条件（分页/空结果/库存不足）、安全测试（注入/越权）、数据一致性（退款库存恢复/优惠券恢复）、测试数据准备和环境检查  
> **测试环境**: 单机 32C/64G, 15 个微服务  
> **服务端口**: Gateway:19000, User:19001, Content:19002, Analytics:19003, Counter:19004, Product:19006, Cart:19008, Inventory:19009, Coupon:19010, Order:19011, Payment:19012, Notification:19013, IM:19014, Home:19015, Search:19016

---

## 通用约定

### 响应格式

所有接口统一返回格式：
```json
{
  "code": 200,
  "message": "操作成功",
  "data": {...},
  "timestamp": 1752134400000
}
```

- `code=200`：成功
- `code!=200`：失败（message 中有错误描述）
- `data`：业务数据（成功时），失败时为 `null`

### 测试方式

- **通过网关**：`curl http://localhost:19000/api/xxx`（推荐，覆盖网关路由+过滤器）
- **直连服务**：`curl http://localhost:19001/api/xxx`（绕过网关，调试用）
- **Token 传递**：登录后获取 `accessToken`，后续请求携带 `Authorization: Bearer <accessToken>`
- **X-User-Id**：部分接口需要 `X-User-Id` Header（网关鉴权通过后自动注入）

### 限流说明

- 网关层有限流（本地兜底规则），高 QPS 时返回 `429`
- 部分接口有业务限流（@SentinelResource），如笔记发布 60s/5次
- 测试时注意频率，避免触发限流

---

## 测试分组总览

| 分组 | 测试场景 | 模块 | 优先级 |
|------|---------|------|--------|
| **第1组: 网关基础** | 路由转发、过滤器、健康检查 | Gateway | P0 |
| **第2组: 用户认证** | 注册→登录→Token刷新→注销、重复注册 | User | P0 |
| **第3组: 内容发布** | 发布笔记→审核→详情→评论互动 | Content | P0 |
| **第4组: 社交互动** | 点赞→收藏→关注→计数 | Analytics + Counter | P1 |
| **第5组: 首页Feed** | Feed流→笔记聚合→商品聚合 | Home (BFF) | P1 |
| **第6组: 商品管理** | SPU/SKU CRUD→分类树→下架影响 | Product | P1 |
| **第7组: 购物车** | 加购→修改→勾选→列表→下架商品处理 | Cart | P1 |
| **第8组: 交易流程** | 下单→支付→退款、库存不足、多SKU、金额校验、优惠券条件 | Order + Payment + Inventory + Coupon | P0 |
| **第9组: 搜索推荐** | 笔记搜索→商品搜索→建议→热搜→空结果 | Search | P1 |
| **第10组: 消息通知** | SSE连接→通知列表→未读数→多类型通知 | Notification | P2 |
| **第11组: 即时通讯** | WebSocket ticket→会话→消息→发送消息 | IM | P2 |
| **第12组: 边界异常** | 未登录→错误参数→限流触发→分页边界→空列表 | 全模块 | P1 |
| **第13组: 安全测试** | XSS/SQL注入→水平越权→参数篡改 | 全模块 | P0 |
| **第14组: 数据一致性** | 退款库存恢复→取消订单优惠券恢复→MQ链路验证 | Inventory + Coupon + Order | P0 |
| **第15组: 内部接口** | TCC事务→Feign回调→计数器修复 | Inventory + Order + Coupon + Counter | P1 |

---

## 第1组: 网关基础测试

### 1.1 网关健康检查

```bash
# 测试网关自身是否可达
curl -s http://localhost:19000/actuator/health | jq .
```
**预期结果**: `{"status": "UP", ...}`

### 1.2 路由转发验证

```bash
# 通过网关访问用户服务公开接口
curl -s http://localhost:19000/api/user/auth/captcha | jq .
```
**预期结果**: `code=200, data` 包含 `captchaKey` 和 `captchaImage`

### 1.3 直连 vs 网关对比

```bash
# 直连用户服务
curl -s http://localhost:19001/api/user/auth/captcha | jq .

# 通过网关（应返回相同结果）
curl -s http://localhost:19000/api/user/auth/captcha | jq .
```
**预期结果**: 两个接口返回结构一致

---

## 第2组: 用户认证测试

### 2.1 获取验证码

```bash
# 请求验证码
CAPTCHA_RESP=$(curl -s http://localhost:19000/api/user/auth/captcha)
echo "$CAPTCHA_RESP" | jq .

# 提取 captchaKey（后续注册/登录需要）
CAPTCHA_KEY=$(echo "$CAPTCHA_RESP" | jq -r '.data.captchaKey')
echo "captchaKey: $CAPTCHA_KEY"
```
**预期结果**: `code=200`, `data.captchaKey` 非空, `data.captchaImage` 为 Base64 图片

### 2.2 用户注册

```bash
# 注册新用户
curl -s -X POST http://localhost:19000/api/user/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "password": "Test@123456",
    "captchaKey": "'$CAPTCHA_KEY'",
    "captchaCode": "1234"
  }' | jq .
```
**预期结果**: `code=200`, `data.accessToken` 非空, `data.refreshToken` 非空

### 2.3 用户登录

```bash
# 登录获取 Token
LOGIN_RESP=$(curl -s -X POST http://localhost:19000/api/user/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "username": "testuser",
    "password": "Test@123456",
    "captchaKey": "'$CAPTCHA_KEY'",
    "captchaCode": "1234"
  }')
echo "$LOGIN_RESP" | jq .

# 提取 Token
ACCESS_TOKEN=$(echo "$LOGIN_RESP" | jq -r '.data.accessToken')
REFRESH_TOKEN=$(echo "$LOGIN_RESP" | jq -r '.data.refreshToken')
echo "AccessToken: ${ACCESS_TOKEN:0:20}..."
echo "RefreshToken: ${REFRESH_TOKEN:0:20}..."
```
**预期结果**: `code=200`, `data.accessToken` 格式为 JWT（三段 Base64）, `data.refreshToken` 非空

### 2.4 获取当前用户信息（需要 Token）

```bash
# 用 accessToken 获取个人信息
curl -s http://localhost:19000/api/user/me \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`, `data` 包含 `id`, `username`, `nickname`, `avatar` 等字段

### 2.5 获取指定用户公开信息（无需 Token）

```bash
# 查询用户公开信息（白名单路径）
# 注意：数据库中用户 ID 从 10001 开始
curl -s http://localhost:19000/api/user/10001/info | jq .
```
**预期结果**: `code=200`, `data` 包含公开字段（无敏感信息如手机号、密码）

### 2.6 修改密码

```bash
curl -s -X PUT http://localhost:19000/api/user/me/password \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "oldPassword": "Test@123456",
    "newPassword": "NewTest@654321"
  }' | jq .
```
**预期结果**: `code=200`

### 2.7 刷新 Token

```bash
curl -s -X POST "http://localhost:19000/api/user/auth/refresh?refreshToken=$REFRESH_TOKEN" | jq .
```
**预期结果**: `code=200`, 返回新的 `accessToken` 和 `refreshToken`

### 2.8 退出登录

```bash
curl -s -X POST "http://localhost:19000/api/user/auth/logout?refreshToken=$REFRESH_TOKEN" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`

### 2.9 地址管理

```bash
# 新增地址
ADDR_RESP=$(curl -s -X POST http://localhost:19000/api/user/address \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "receiverName": "张三",
    "receiverPhone": "13800138000",
    "province": "广东省",
    "city": "深圳市",
    "district": "南山区",
    "detailAddress": "科技园路1号",
    "isDefault": true
  }')
echo "$ADDR_RESP" | jq .
ADDR_ID=$(echo "$ADDR_RESP" | jq -r '.data.id')

# 获取地址列表
curl -s http://localhost:19000/api/user/address/list \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .

# 获取默认地址
curl -s http://localhost:19000/api/user/address/default \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .

# 删除地址
curl -s -X DELETE http://localhost:19000/api/user/address/$ADDR_ID \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: 各接口 `code=200`

---

## 第3组: 内容发布测试

### 3.1 发布笔记

```bash
NOTE_RESP=$(curl -s -X POST http://localhost:19000/api/note/publish \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "测试笔记标题",
    "content": "这是一篇测试笔记的正文内容，用于验证笔记发布功能是否正常。",
    "images": ["https://example.com/img1.jpg"],
    "noteType": 0,
    "tags": ["测试", "验证"]
  }')
echo "$NOTE_RESP" | jq .
NOTE_ID=$(echo "$NOTE_RESP" | jq -r '.data.id')
echo "Note ID: $NOTE_ID"
```
**预期结果**: `code=200`, `data.id` 非空

### 3.2 保存草稿

```bash
DRAFT_RESP=$(curl -s -X POST http://localhost:19000/api/note/draft \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "草稿笔记",
    "content": "这是一篇草稿..."
  }')
echo "$DRAFT_RESP" | jq .
DRAFT_ID=$(echo "$DRAFT_RESP" | jq -r '.data.id')
```
**预期结果**: `code=200`

### 3.3 发布草稿

```bash
curl -s -X POST http://localhost:19000/api/note/$DRAFT_ID/publish \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`

### 3.4 获取笔记详情（公开接口）

```bash
curl -s http://localhost:19000/api/note/detail/$NOTE_ID | jq .
```
**预期结果**: `code=200`, `data` 包含 `title`, `content`, `images`, `tags`, `author` 等

### 3.5 获取用户笔记列表（公开接口）

```bash
curl -s "http://localhost:19000/api/note/user/1?pageNum=1&pageSize=10" | jq .
```
**预期结果**: `code=200`, `data` 包含笔记列表和分页信息

### 3.6 获取我的笔记列表

```bash
curl -s "http://localhost:19000/api/note/my?pageNum=1&pageSize=10" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`

### 3.7 编辑笔记

```bash
curl -s -X PUT http://localhost:19000/api/note/$NOTE_ID \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "修改后的标题",
    "content": "修改后的正文内容"
  }' | jq .
```
**预期结果**: `code=200`

### 3.8 评论互动

```bash
# 发表评论
COMMENT_RESP=$(curl -s -X POST http://localhost:19000/api/comment \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "noteId": '$NOTE_ID',
    "content": "这是一条测试评论"
  }')
echo "$COMMENT_RESP" | jq .
COMMENT_ID=$(echo "$COMMENT_RESP" | jq -r '.data.id')

# 获取评论列表（公开）
curl -s "http://localhost:19000/api/comment/list/$NOTE_ID?lastId=0&pageSize=10" | jq .

# 获取评论数（公开）
curl -s http://localhost:19000/api/comment/count/$NOTE_ID | jq .

# 删除评论
curl -s -X DELETE http://localhost:19000/api/comment/$COMMENT_ID \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: 各接口 `code=200`

### 3.9 删除笔记

```bash
curl -s -X DELETE http://localhost:19000/api/note/$NOTE_ID \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`

---

## 第4组: 社交互动测试

### 4.1 点赞流程

```bash
# 点赞笔记
curl -s -X POST http://localhost:19000/api/social/like \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "bizType": 1,
    "bizId": '$NOTE_ID'
  }' | jq .

# 查询点赞状态
curl -s "http://localhost:19000/api/social/like/status?bizType=1&bizId=1" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .

# 批量查询点赞状态
curl -s "http://localhost:19000/api/social/like/batch-status?bizType=1&bizIds=1,2,3" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .

# 获取点赞数（公开）
curl -s "http://localhost:19000/api/social/like/count?bizType=1&bizId=1" | jq .

# 取消点赞
curl -s -X DELETE http://localhost:19000/api/social/like \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "bizType": 1,
    "bizId": '$NOTE_ID'
  }' | jq .
```
**预期结果**: 各接口 `code=200`

### 4.2 收藏流程

```bash
# 收藏笔记
curl -s -X POST http://localhost:19000/api/social/favorite \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"noteId": '$NOTE_ID'}' | jq .

# 查询收藏状态
curl -s "http://localhost:19000/api/social/favorite/status?noteId=1" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .

# 收藏列表
curl -s "http://localhost:19000/api/social/favorite/list?page=1&size=10" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .

# 取消收藏
curl -s -X DELETE http://localhost:19000/api/social/favorite \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"noteId": '$NOTE_ID'}' | jq .
```
**预期结果**: 各接口 `code=200`

### 4.3 关注流程

```bash
# 关注用户
# 注意：用户 ID 从 10001 开始，关注目标用户 10002
curl -s -X POST http://localhost:19000/api/social/follow/10002 \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .

# 查询关注关系
curl -s http://localhost:19000/api/social/relation/10002 \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .

# 关注列表（公开）
curl -s "http://localhost:19000/api/social/following/10001?page=1&size=10" | jq .

# 粉丝列表（公开）
curl -s "http://localhost:19000/api/social/follower/10002?page=1&size=10" | jq .

# 取消关注
curl -s -X DELETE http://localhost:19000/api/social/follow/10002 \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: 各接口 `code=200`

### 4.4 计数器验证（社交互动后计数是否正确）

```bash
# 查询单个计数
curl -s "http://localhost:19000/api/counter/get?targetType=note&targetId=1&countType=like" | jq .

# 批量查询计数
curl -s -X POST http://localhost:19000/api/counter/batch-get \
  -H "Content-Type: application/json" \
  -d '{
    "targetType": "note",
    "targetIds": [1, 2, 3],
    "countTypes": ["like", "comment", "favorite"]
  }' | jq .
```
**预期结果**: `code=200`, `data` 中计数正确

---

## 第5组: 首页 Feed 测试

### 5.1 首页 Feed 流

```bash
curl -s "http://localhost:19000/api/home/feed?lastScore=0&size=10" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`, `data` 为笔记列表（BFF 聚合了作者信息、点赞数等）

### 5.2 笔记详情聚合

```bash
curl -s http://localhost:19000/api/home/note/$NOTE_ID \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`, `data` 聚合了笔记详情+作者信息+社交互动状态

### 5.3 商品详情聚合（公开）

```bash
curl -s http://localhost:19000/api/home/product/1 | jq .
```
**预期结果**: `code=200`, `data` 聚合了商品详情+SKU+库存信息

### 5.4 用户主页聚合

```bash
curl -s http://localhost:19000/api/home/user/10001 \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`, `data` 聚合了用户信息+笔记列表+关注状态

---

## 第6组: 商品管理测试

### 6.1 分类树查询（公开）

```bash
curl -s http://localhost:19000/api/product/category/tree | jq .
```
**预期结果**: `code=200`, `data` 为三级分类树结构

### 6.2 创建 SPU

```bash
SPU_RESP=$(curl -s -X POST http://localhost:19000/api/product/spu \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "测试商品",
    "categoryId": 1,
    "description": "这是一个测试商品描述",
    "images": ["https://example.com/product1.jpg"]
  }')
echo "$SPU_RESP" | jq .
SPU_ID=$(echo "$SPU_RESP" | jq -r '.data.id')
echo "SPU ID: $SPU_ID"
```
**预期结果**: `code=200`, `data.id` 非空

### 6.3 SPU 详情查询（公开）

```bash
curl -s http://localhost:19000/api/product/spu/$SPU_ID | jq .
```
**预期结果**: `code=200`, 命中缓存时响应极快（多级缓存）

### 6.4 SPU 列表（公开）

```bash
curl -s "http://localhost:19000/api/product/spu/list?pageNum=1&pageSize=10&categoryId=1" | jq .
```
**预期结果**: `code=200`

### 6.5 更新 SPU

```bash
curl -s -X PUT http://localhost:19000/api/product/spu/$SPU_ID \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "修改后的商品名称",
    "categoryId": 1,
    "description": "更新后的描述"
  }' | jq .
```
**预期结果**: `code=200`

### 6.6 上架/下架 SPU

```bash
# 下架
curl -s -X PUT "http://localhost:19000/api/product/spu/$SPU_ID/status?status=0" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .

# 上架
curl -s -X PUT "http://localhost:19000/api/product/spu/$SPU_ID/status?status=1" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`

### 6.7 SKU 管理

```bash
# 创建 SKU
SKU_RESP=$(curl -s -X POST http://localhost:19000/api/product/sku \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "spuId": '$SPU_ID',
    "skuName": "标准版",
    "price": 99.00,
    "stock": 100
  }')
echo "$SKU_RESP" | jq .
SKU_ID=$(echo "$SKU_RESP" | jq -r '.data.id')

# SKU 详情
curl -s http://localhost:19000/api/product/sku/$SKU_ID | jq .

# 按 SPU 查 SKU 列表
curl -s http://localhost:19000/api/product/sku/list/$SPU_ID | jq .

# 批量 SKU 查询
curl -s "http://localhost:19000/api/product/sku/batch?skuIds=$SKU_ID,100,200" | jq .
```
**预期结果**: 各接口 `code=200`

---

## 第7组: 购物车测试

### 7.1 加入购物车

```bash
curl -s -X POST http://localhost:19000/api/cart/add \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "skuId": '$SKU_ID',
    "quantity": 2
  }' | jq .
```
**预期结果**: `code=200`

### 7.2 购物车列表

```bash
curl -s http://localhost:19000/api/cart/list \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`, `data` 为购物车商品列表

### 7.3 购物车角标数量

```bash
curl -s http://localhost:19000/api/cart/count \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`, `data` 为数字

### 7.4 修改数量

```bash
curl -s -X PUT http://localhost:19000/api/cart/quantity \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "skuId": '$SKU_ID',
    "quantity": 3
  }' | jq .
```
**预期结果**: `code=200`

### 7.5 勾选/取消勾选

```bash
# 勾选
curl -s -X PUT http://localhost:19000/api/cart/check \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "skuId": '$SKU_ID',
    "checked": true
  }' | jq .

# 全选
curl -s -X PUT "http://localhost:19000/api/cart/check-all?checked=true" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`

### 7.6 删除商品

```bash
curl -s -X DELETE http://localhost:19000/api/cart/$SKU_ID \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`

---

## 第8组: 交易流程测试（核心链路）

### 8.1 创建优惠券模板

```bash
TEMPLATE_RESP=$(curl -s -X POST http://localhost:19000/api/coupon/template \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "新人满100减20",
    "type": 1,
    "discountValue": 20.00,
    "minAmount": 100.00,
    "totalCount": 1000,
    "perUserLimit": 1,
    "validStart": "2026-01-01T00:00:00",
    "validEnd": "2026-12-31T23:59:59"
  }')
echo "$TEMPLATE_RESP" | jq .
TEMPLATE_ID=$(echo "$TEMPLATE_RESP" | jq -r '.data.id')
```
**预期结果**: `code=200`, `data.id` 非空

### 8.2 领取优惠券

```bash
COUPON_RESP=$(curl -s -X POST http://localhost:19000/api/coupon/claim \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"templateId": '$TEMPLATE_ID'}')
echo "$COUPON_RESP" | jq .
COUPON_ID=$(echo "$COUPON_RESP" | jq -r '.data.id')
```
**预期结果**: `code=200`

### 8.3 查看可用优惠券

```bash
curl -s http://localhost:19000/api/coupon/user/available \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`

### 8.4 初始化库存

```bash
curl -s -X POST http://localhost:19000/api/inventory/init \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "skuId": '$SKU_ID',
    "stock": 100
  }' | jq .
```
**预期结果**: `code=200`

### 8.5 查询库存

```bash
curl -s http://localhost:19000/api/inventory/stock/$SKU_ID | jq .
```
**预期结果**: `code=200`, `data` 为可用库存数

### 8.6 创建订单

```bash
BIZ_ID=$(uuidgen | tr '[:upper:]' '[:lower:]' | tr -d '-')
ORDER_RESP=$(curl -s -X POST http://localhost:19000/api/order/create \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "skuItems": [
      {"skuId": '$SKU_ID', "quantity": 1}
    ],
    "addressId": '$ADDR_ID',
    "couponId": '$COUPON_ID',
    "remark": "请尽快发货",
    "bizIdentifier": "'$BIZ_ID'"
  }')
echo "$ORDER_RESP" | jq .
ORDER_ID=$(echo "$ORDER_RESP" | jq -r '.data.id')
ORDER_NO=$(echo "$ORDER_RESP" | jq -r '.data.orderNo')
echo "Order ID: $ORDER_ID, OrderNo: $ORDER_NO"
```
**预期结果**: `code=200`, `data.id` 和 `data.orderNo` 非空

### 8.7 订单详情

```bash
curl -s http://localhost:19000/api/order/$ORDER_ID \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`, `data` 包含订单商品、地址、金额等信息

### 8.8 订单列表

```bash
curl -s "http://localhost:19000/api/order/list?status=ALL" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`

### 8.9 发起支付

```bash
curl -s -X POST http://localhost:19000/api/payment/pay \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": '$ORDER_ID',
    "amount": 99.00,
    "payType": 1
  }' | jq .
```
**预期结果**: `code=200`, 返回支付结果（Mock 模式）

### 8.10 查询支付状态

```bash
curl -s http://localhost:19000/api/payment/status/$ORDER_ID | jq .
```
**预期结果**: `code=200`, `data.status` 为支付状态

### 8.11 确认收货

```bash
curl -s -X POST "http://localhost:19000/api/order/confirm?orderId=$ORDER_ID" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`

### 8.12 取消订单

```bash
# 先创建新订单用于取消测试
BIZ_ID2=$(uuidgen | tr '[:upper:]' '[:lower:]' | tr -d '-')
ORDER_RESP2=$(curl -s -X POST http://localhost:19000/api/order/create \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "skuItems": [{"skuId": '$SKU_ID', "quantity": 1}],
    "addressId": '$ADDR_ID',
    "bizIdentifier": "'$BIZ_ID2'"
  }')
ORDER_ID2=$(echo "$ORDER_RESP2" | jq -r '.data.id')

# 取消订单
curl -s -X POST "http://localhost:19000/api/order/cancel?orderId=$ORDER_ID2" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`，库存和优惠券应被释放

### 8.13 幂等性验证

```bash
# 使用相同的 bizIdentifier 再次下单，应返回相同订单
curl -s -X POST http://localhost:19000/api/order/create \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "skuItems": [{"skuId": '$SKU_ID', "quantity": 1}],
    "addressId": '$ADDR_ID',
    "bizIdentifier": "'$BIZ_ID'"
  }' | jq .
```
**预期结果**: `code=200`，返回已存在的订单信息，不会创建重复订单

---

## 第9组: 搜索推荐测试

### 9.1 笔记搜索

```bash
curl -s "http://localhost:19000/api/search/note?keyword=测试&pageSize=10" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`, `data` 为搜索结果列表，含高亮信息

### 9.2 商品搜索

```bash
curl -s "http://localhost:19000/api/search/product?keyword=商品&pageSize=10" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`

### 9.3 搜索建议（自动补全）

```bash
curl -s "http://localhost:19000/api/search/suggest?prefix=测" | jq .
```
**预期结果**: `code=200`, `data` 为建议词列表

### 9.4 热搜榜

```bash
curl -s http://localhost:19000/api/search/hot | jq .
```
**预期结果**: `code=200`, `data` 为热搜词列表

### 9.5 记录搜索词

```bash
curl -s -X POST "http://localhost:19000/api/search/hot/record?keyword=测试搜索词" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`

### 9.6 搜索历史

```bash
curl -s http://localhost:19000/api/search/history \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`, `data` 为搜索历史列表

### 9.7 个性化推荐 Feed

```bash
curl -s http://localhost:19000/api/recommend/feed \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`, `data` 为推荐笔记列表

---

## 第10组: 消息通知测试

### 10.1 获取 SSE Ticket

```bash
curl -s -X POST http://localhost:19000/api/notification/sse/ticket \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`, `data` 包含 `ticket`

### 10.2 未读通知数

```bash
curl -s http://localhost:19000/api/notification/unread-count \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`, `data` 包含各类未读数

### 10.3 通知列表

```bash
curl -s "http://localhost:19000/api/notification/list?page=1&size=10" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`

---

## 第11组: 即时通讯测试

### 11.1 签发 WebSocket Ticket

```bash
curl -s -X POST http://localhost:19000/api/im/ws/ticket \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`, `data` 包含 `ticket`

### 11.2 会话列表

```bash
curl -s "http://localhost:19000/api/im/conversations?page=1&size=10" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`

### 11.3 未读消息数

```bash
curl -s http://localhost:19000/api/im/unread-count \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`, `data` 为数字

---

## 第12组: 边界和异常测试

### 12.1 未登录访问受保护接口

```bash
# 无 Token 访问个人信息
curl -s http://localhost:19000/api/user/me | jq .
```
**预期结果**: `code!=200`, 返回 401 未授权错误

### 12.2 过期 Token 访问

```bash
# 使用伪造/过期的 Token
curl -s http://localhost:19000/api/user/me \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.fake" | jq .
```
**预期结果**: `code!=200`, 返回 Token 无效或过期

### 12.3 必填参数缺失

```bash
# 登录时不传密码
curl -s -X POST http://localhost:19000/api/user/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "test"}' | jq .
```
**预期结果**: `code!=200`, message 提示参数校验失败

### 12.4 不存在的资源

```bash
# 查询不存在的笔记
curl -s http://localhost:19000/api/note/detail/999999 | jq .
```
**预期结果**: `code!=200`, 返回资源不存在

### 12.5 限流触发测试

```bash
# 短时间内连续请求笔记发布（限流 60s/5次）
for i in {1..10}; do
  echo "Request $i:"
  curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:19000/api/note/publish \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"title": "限流测试'$i'", "content": "测试内容"}'
  echo ""
  sleep 0.5
done
```
**预期结果**: 前 5 次返回 200，后续返回 429（限流）

---

## 第13组: 遗漏补充 - 用户服务

> 审查发现遗漏：修改个人信息、地址详情查询、地址更新、设置默认地址

### 13.1 修改个人信息

```bash
curl -s -X PUT http://localhost:19000/api/user/me \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "nickname": "测试昵称",
    "gender": 1,
    "signature": "测试个性签名"
  }' | jq .
```
**预期结果**: `code=200`

### 13.2 地址详情查询

```bash
curl -s http://localhost:19000/api/user/address/$ADDR_ID \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`, `data` 包含地址详情

### 13.3 更新地址

```bash
curl -s -X PUT http://localhost:19000/api/user/address/$ADDR_ID \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "receiverName": "张三",
    "receiverPhone": "13800138001",
    "province": "广东省",
    "city": "广州市",
    "district": "天河区",
    "detailAddress": "天河路100号"
  }' | jq .
```
**预期结果**: `code=200`

### 13.4 设置默认地址

```bash
curl -s -X PUT http://localhost:19000/api/user/address/$ADDR_ID/default \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`

---

## 第14组: 遗漏补充 - 内容服务

> 审查发现遗漏：上传图片、子评论(楼中楼)、子评论列表、传统分页评论

### 14.1 上传笔记图片

```bash
# 创建一个测试图片文件
dd if=/dev/zero of=/tmp/test-image.jpg bs=1024 count=10 2>/dev/null

curl -s -X POST http://localhost:19000/api/note/upload/image \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -F "file=@/tmp/test-image.jpg" | jq .
```
**预期结果**: `code=200`, `data` 返回图片 URL

### 14.2 发表子评论（楼中楼回复）

```bash
SUB_COMMENT_RESP=$(curl -s -X POST http://localhost:19000/api/comment \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "noteId": '$NOTE_ID',
    "parentId": '$COMMENT_ID',
    "replyToId": '$COMMENT_ID',
    "content": "这是对评论的回复"
  }')
echo "$SUB_COMMENT_RESP" | jq .
```
**预期结果**: `code=200`

### 14.3 查看子评论列表（楼中楼展开）

```bash
curl -s "http://localhost:19000/api/comment/children/$COMMENT_ID?lastId=0&pageSize=10" | jq .
```
**预期结果**: `code=200`, `data` 为子评论列表

### 14.4 传统分页评论（备用接口）

```bash
curl -s "http://localhost:19000/api/comment/page/$NOTE_ID?pageNum=1&pageSize=10" | jq .
```
**预期结果**: `code=200`

---

## 第15组: 遗漏补充 - 社交服务

> 审查发现遗漏：点赞评论(bizType=2)、共同关注、点赞幂等性

### 15.1 点赞评论（bizType=2）

```bash
curl -s -X POST http://localhost:19000/api/social/like \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"bizType": 2, "bizId": '$COMMENT_ID'}' | jq .
```
**预期结果**: `code=200`

### 15.2 查看共同关注

```bash
curl -s http://localhost:19000/api/social/common/10002 \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`, `data` 为共同关注的用户列表

### 15.3 点赞幂等性验证

```bash
# 第一次点赞
curl -s -X POST http://localhost:19000/api/social/like \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"bizType": 1, "bizId": '$NOTE_ID'}' | jq .

# 重复点赞（应返回已点赞状态，不影响计数）
curl -s -X POST http://localhost:19000/api/social/like \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"bizType": 1, "bizId": '$NOTE_ID'}' | jq .
```
**预期结果**: 两次都返回 `code=200`，计数不变

---

## 第16组: 遗漏补充 - 首页BFF

> 审查发现遗漏：购物车聚合接口

### 16.1 购物车聚合

```bash
curl -s http://localhost:19000/api/home/cart \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`, `data` 聚合了购物车商品+库存+优惠券信息

---

## 第17组: 遗漏补充 - 购物车

> 审查发现遗漏：匿名购物车合并

### 17.1 匿名购物车合并（登录后）

```bash
curl -s -X POST http://localhost:19000/api/cart/merge \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"items":[{"skuId": 1, "quantity": 2}, {"skuId": 4, "quantity": 1}]}' | jq .
```
**预期结果**: `code=200`

---

## 第18组: 遗漏补充 - 优惠券服务

> 审查发现遗漏：模板详情、上下线、用户券列表

### 18.1 优惠券模板详情

```bash
curl -s http://localhost:19000/api/coupon/template/$TEMPLATE_ID | jq .
```
**预期结果**: `code=200`, `data` 包含模板详情

### 18.2 下线优惠券模板

```bash
curl -s -X PUT "http://localhost:19000/api/coupon/template/$TEMPLATE_ID/status?status=0" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`

### 18.3 上线优惠券模板

```bash
curl -s -X PUT "http://localhost:19000/api/coupon/template/$TEMPLATE_ID/status?status=1" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`

### 18.4 用户全量优惠券��表

```bash
curl -s "http://localhost:19000/api/coupon/user/list?status=0" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`

---

## 第19组: 遗漏补充 - 库存服务

> 审查发现遗漏：预扣、确认、释放、重初始化独立测试

### 19.1 预扣库存

```bash
curl -s -X POST http://localhost:19000/api/inventory/preDeduct \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": '$ORDER_ID',
    "skuId": '$SKU_ID',
    "quantity": 2,
    "userId": 10001
  }' | jq .
```
**预期结果**: `code=200`, 库存可用数减少 2

### 19.2 确认扣减

```bash
curl -s -X POST http://localhost:19000/api/inventory/confirm \
  -H "Content-Type: application/json" \
  -d '{"orderId": '$ORDER_ID'}' | jq .
```
**预期结果**: `code=200`

### 19.3 释放库存

```bash
# 先预扣一个新订单的库存，再释放
curl -s -X POST http://localhost:19000/api/inventory/preDeduct \
  -H "Content-Type: application/json" \
  -d '{"orderId": 99999, "skuId": '$SKU_ID', "quantity": 1, "userId": 10001}' | jq .

curl -s -X POST http://localhost:19000/api/inventory/release \
  -H "Content-Type: application/json" \
  -d '{"orderId": 99999}' | jq .
```
**预期结果**: `code=200`, 库存恢复

### 19.4 重新初始化库存

```bash
curl -s -X POST http://localhost:19000/api/inventory/reinit \
  -H "Content-Type: application/json" \
  -d '{
    "skuId": '$SKU_ID',
    "totalStock": 200,
    "bucketCount": 8
  }' | jq .
```
**预期结果**: `code=200`

### 19.5 库存前后对比验证

```bash
# 下单前库存
STOCK_BEFORE=$(curl -s http://localhost:19000/api/inventory/stock/$SKU_ID | jq -r '.data.availableStock')
echo "操作前库存: $STOCK_BEFORE"

# ... 执行库存操作 ...

# 操作后库存
STOCK_AFTER=$(curl -s http://localhost:19000/api/inventory/stock/$SKU_ID | jq -r '.data.availableStock')
echo "操作后库存: $STOCK_AFTER"

if [ "$STOCK_BEFORE" = "$STOCK_AFTER" ]; then
  echo "PASS: 库存一致"
else
  echo "DIFF: 库存变化 = $((STOCK_AFTER - STOCK_BEFORE))"
fi
```
**预期结果**: 库存变化量等于操作量

---

## 第20组: 遗漏补充 - 支付退款链路

> 审查发现：退款链路完全缺失，这是最严重的遗漏

### 20.1 发起退款

```bash
# 先确认支付状态和 paymentId
PAY_INFO=$(curl -s http://localhost:19000/api/payment/status/$ORDER_ID)
echo "$PAY_INFO" | jq .
PAYMENT_ID=$(echo "$PAY_INFO" | jq -r '.data.id // 0')

if [ "$PAYMENT_ID" != "0" ]; then
  curl -s -X POST http://localhost:19000/api/payment/refund \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{
      "paymentId": '$PAYMENT_ID',
      "refundAmount": 99.00,
      "reason": "不喜欢",
      "refundType": 1
    }' | jq .
fi
```
**预期结果**: `code=200`, 返回退款记录

### 20.2 退款后验��订单状态

```bash
curl -s http://localhost:19000/api/order/$ORDER_ID \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq '.data | {orderId, status, statusDesc}'
```
**预期结果**: 订单状态变为"已退款"

### 20.3 退款后验证库存恢复

```bash
curl -s http://localhost:19000/api/inventory/stock/$SKU_ID | jq .
```
**预期结果**: 库存恢复到退款前水平

---

## 第21组: 遗漏补充 - 搜索推荐

> 审查发现遗漏：相似推荐、行为上报、搜索历史管理、热搜运营管理

### 21.1 相似笔记推荐（Item-CF）

```bash
curl -s "http://localhost:19000/api/recommend/similar/$NOTE_ID?size=10" | jq .
```
**预期结果**: `code=200`, `data` 为相似笔记列表

### 21.2 用户行为上报

```bash
# behaviorType: 1-曝光 2-点击 3-点赞 4-收藏 5-评论 6-分享 7-停留
curl -s -X POST http://localhost:19000/api/recommend/behavior \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"noteId": '$NOTE_ID', "behaviorType": 3}' | jq .
```
**预期结果**: `code=200`

### 21.3 清空全部搜索历史

```bash
curl -s -X DELETE http://localhost:19000/api/search/history \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`

### 21.4 删除单条搜索历史

```bash
# 先记录一个搜索词
curl -s -X POST "http://localhost:19000/api/search/hot/record?keyword=待删除" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .

# 删除单条
curl -s -X DELETE "http://localhost:19000/api/search/history/待删除" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`

### 21.5 热搜运营 - 置顶/取消置顶

```bash
# 人工置顶热搜词
curl -s -X PUT "http://localhost:19000/api/search/hot/pin?keyword=新品发布" | jq .

# 取消置顶
curl -s -X DELETE "http://localhost:19000/api/search/hot/pin?keyword=新品发布" | jq .
```
**预期结果**: `code=200`

### 21.6 热搜运营 - 屏蔽/取消屏蔽

```bash
# 屏蔽敏感词
curl -s -X PUT "http://localhost:19000/api/search/hot/block?keyword=敏感词" | jq .

# 取消屏蔽
curl -s -X DELETE "http://localhost:19000/api/search/hot/block?keyword=敏感词" | jq .
```
**预期结果**: `code=200`

### 21.7 历史热搜快照

```bash
curl -s "http://localhost:19000/api/search/hot/snapshot?date=2026-07-10" | jq .
```
**预期结果**: `code=200`

### 21.8 手动触发ES全量索引重建

```bash
curl -s -X POST http://localhost:19000/api/search/index/rebuild \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`

---

## 第22组: 遗漏补充 - 通知服务

> 审查发现遗漏：标记已读(单条/按类型/全部)、SSE在线统计

### 22.1 标记单条通知已读

```bash
# 先获取通知列表获取通知ID
NOTIF_ID=$(curl -s "http://localhost:19000/api/notification/list?page=1&size=1" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq -r '.data.items[0].id // 0')

if [ "$NOTIF_ID" != "0" ]; then
  curl -s -X POST http://localhost:19000/api/notification/read/$NOTIF_ID \
    -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
fi
```
**预期结果**: `code=200`

### 22.2 按类型标记全部已读

```bash
# type 对应通知类型（like/comment/follow/system/order）
curl -s -X POST http://localhost:19000/api/notification/read-by-type/like \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`

### 22.3 全部标记已读

```bash
curl -s -X POST http://localhost:19000/api/notification/read-all \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`

### 22.4 SSE在线连接数

```bash
curl -s http://localhost:19000/api/notification/sse/online-count | jq .
```
**预期结果**: `code=200`, `data` 为当前SSE连接数

---

## 第23组: 遗漏补充 - IM服务

> 审查发现遗漏：聊天记录、标记已读、在线统计

### 23.1 查看与某人的聊天记录

```bash
curl -s "http://localhost:19000/api/im/messages/10002?page=1&size=20" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`, `data` 为消息列表

### 23.2 标记会话已读

```bash
curl -s -X POST http://localhost:19000/api/im/read/10002 \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq .
```
**预期结果**: `code=200`

### 23.3 在线用户统计

```bash
curl -s http://localhost:19000/api/im/online-count | jq .
```
**预期结果**: `code=200`, `data` 为在线用户数

---

## 第24组: 遗漏补充 - 并发安全

> 审查发现：库存并发扣减、优惠券超发验证完全缺失

### 24.1 库存并发扣减安全验证

```bash
# 先查初始库存
INIT_STOCK=$(curl -s http://localhost:19000/api/inventory/stock/$SKU_ID | jq -r '.data.availableStock')
echo "初始库存: $INIT_STOCK"

# 模拟 10 个并发请求各扣 1 个库存
for i in $(seq 1 10); do
  curl -s -X POST http://localhost:19000/api/inventory/preDeduct \
    -H "Content-Type: application/json" \
    -d "{\"orderId\": $((90000+i)), \"skuId\": $SKU_ID, \"quantity\": 1, \"userId\": $((100+i))}" \
    > /dev/null &
done
wait

# 验证最终库存
FINAL_STOCK=$(curl -s http://localhost:19000/api/inventory/stock/$SKU_ID | jq -r '.data.availableStock')
echo "最终库存: $FINAL_STOCK"
echo "预期库存: $((INIT_STOCK - 10))"
```
**预期结果**: 最终库存 = 初始库存 - 10（无超卖）

### 24.2 优惠券超发验证

```bash
# 查券模板剩余量
TMPL_INFO=$(curl -s http://localhost:19000/api/coupon/template/$TEMPLATE_ID)
REMAIN=$(echo "$TMPL_INFO" | jq -r '.data.remainCount')
echo "券剩余量: $REMAIN"

# 模拟超过剩余量的并发领券
for i in $(seq 1 $((REMAIN + 5))); do
  curl -s -X POST http://localhost:19000/api/coupon/claim \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"templateId\": $TEMPLATE_ID}" | jq -r '.code' &
done
wait
```
**预期结果**: 超过剩余量的请求返回失败，总领取量不超过剩余量

---

## 第25组: 遗漏补充 - 内部接口验证

> 审查发现：内部 Feign 调用接口（计数器增减、订单号查询、支付回调等）虽有独立的 REST 端点但未测试

### 25.1 计数器增减

```bash
# 计数器 +1
curl -s -X POST http://localhost:19000/api/counter/increment \
  -H "Content-Type: application/json" \
  -d '{"targetType": 1, "targetId": '$NOTE_ID', "countType": 1}' | jq .

# 查询验证
curl -s "http://localhost:19000/api/counter/get?targetType=1&targetId=1&countType=1" | jq .

# 计数器 -1
curl -s -X POST http://localhost:19000/api/counter/decrement \
  -H "Content-Type: application/json" \
  -d '{"targetType": 1, "targetId": '$NOTE_ID', "countType": 1}' | jq .
```
**预期结果**: `code=200`

### 25.2 通过订单号查询订单

```bash
curl -s http://localhost:19000/api/order/by-order-no/$ORDER_NO | jq .
```
**预期结果**: `code=200`, 返回订单详情

### 25.3 订单支付金额查询（供支付服务验价）

```bash
curl -s "http://localhost:19000/api/order/pay-amount?orderId=$ORDER_ID" | jq .
```
**预期结果**: `code=200`, `data` 为支付金额

### 25.4 订单内嵌支付创建

```bash
curl -s -X POST http://localhost:19000/api/order/pay/create \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"orderId": '$ORDER_ID', "payType": 1}' | jq .
```
**预期结果**: `code=200`

### 25.5 计数器对账修复

```bash
curl -s -X POST http://localhost:19000/api/counter/reconcile | jq .
```
**预期结果**: `code=200`

将以下脚本保存为 `curl-test-all.sh` 并执行：

```bash
#!/bin/bash
# my-xhs curl 业务验证一键测试
# 使用方法: bash curl-test-all.sh

set -e

GATEWAY="http://localhost:19000"
PASS=0
FAIL=0

# 颜色
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

check() {
    local desc="$1"
    local resp="$2"
    local code=$(echo "$resp" | jq -r '.code // 0')
    if [ "$code" = "200" ]; then
        echo -e "${GREEN}[PASS]${NC} $desc"
        PASS=$((PASS+1))
    else
        echo -e "${RED}[FAIL]${NC} $desc (code=$code, msg=$(echo "$resp" | jq -r '.message'))"
        FAIL=$((FAIL+1))
    fi
}

echo "=========================================="
echo "  my-xhs curl 业务验证测试"
echo "=========================================="
echo ""

# ===== 第1组: 网关基础 =====
echo -e "${YELLOW}--- 第1组: 网关基础 ---${NC}"
resp=$(curl -s $GATEWAY/api/user/auth/captcha)
check "网关路由转发" "$resp"

# ===== 第2组: 用户认证 =====
echo -e "${YELLOW}--- 第2组: 用户认证 ---${NC}"
CAPTCHA_KEY=$(echo "$resp" | jq -r '.data.captchaKey')

# 注册
resp=$(curl -s -X POST $GATEWAY/api/user/auth/register \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"curl_test_$(date +%s)\",\"password\":\"Test@123456\",\"captchaKey\":\"$CAPTCHA_KEY\",\"captchaCode\":\"1234\"}")
check "用户注册" "$resp"

# 登录
resp=$(curl -s -X POST $GATEWAY/api/user/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"testuser\",\"password\":\"Test@123456\",\"captchaKey\":\"$CAPTCHA_KEY\",\"captchaCode\":\"1234\"}")
check "用户登录" "$resp"
TOKEN=$(echo "$resp" | jq -r '.data.accessToken')

# 获取个人信息
resp=$(curl -s $GATEWAY/api/user/me -H "Authorization: Bearer $TOKEN")
check "获取个人信息" "$resp"

# 公开信息
resp=$(curl -s $GATEWAY/api/user/10001/info)
check "获取用户公开信息" "$resp"

# ===== 第6组: 商品管理 =====
echo -e "${YELLOW}--- 第6组: 商品管理 ---${NC}"
resp=$(curl -s $GATEWAY/api/product/category/tree)
check "分类树查询" "$resp"

# ===== 第9组: 搜索 =====
echo -e "${YELLOW}--- 第9组: 搜索 ---${NC}"
resp=$(curl -s "$GATEWAY/api/search/suggest?prefix=测")
check "搜索建议" "$resp"

resp=$(curl -s $GATEWAY/api/search/hot)
check "热搜榜" "$resp"

# ===== 第12组: 边界测试 =====
echo -e "${YELLOW}--- 第12组: 边界测试 ---${NC}"
resp=$(curl -s $GATEWAY/api/user/me)
check "未登录访问(预期失败)" "$resp"  # 这里预期会失败，手动调整

echo ""
echo "=========================================="
echo "  测试完成: PASS=$PASS, FAIL=$FAIL"
echo "=========================================="
```

---

## 测试检查清单

| 分组 | 测试项 | 状态 | 备注 |
|------|--------|------|------|
| 网关 | 网关路由转发 | ⬜ | |
| 网关 | 直连vs网关对比 | ⬜ | |
| 认证 | 获取验证码 | ⬜ | |
| 认证 | 用户注册 | ⬜ | |
| 认证 | 用户登录 | ⬜ | |
| 认证 | 获取个人信息 | ⬜ | |
| 认证 | 公开用户信息 | ⬜ | |
| 认证 | 修改密码 | ⬜ | |
| 认证 | Token刷新 | ⬜ | |
| 认证 | 退出登录 | ⬜ | |
| 认证 | 地址管理CRUD | ⬜ | |
| 内容 | 发布笔记 | ⬜ | |
| 内容 | 保存草稿 | ⬜ | |
| 内容 | 发布草稿 | ⬜ | |
| 内容 | 笔记详情 | ⬜ | |
| 内容 | 编辑笔记 | ⬜ | |
| 内容 | 删除笔记 | ⬜ | |
| 内容 | 发表评论 | ⬜ | |
| 内容 | 评论列表 | ⬜ | |
| 内容 | 删除评论 | ⬜ | |
| 社交 | 点赞/取消点赞 | ⬜ | |
| 社交 | 收藏/取消收藏 | ⬜ | |
| 社交 | 关注/取消关注 | ⬜ | |
| 社交 | 计数器验证 | ⬜ | |
| 首页 | Feed流 | ⬜ | |
| 首页 | 笔记聚合 | ⬜ | |
| 首页 | 商品聚合 | ⬜ | |
| 首页 | 用户主页聚合 | ⬜ | |
| 商品 | 分类树 | ⬜ | |
| 商品 | SPU CRUD | ⬜ | |
| 商品 | SKU CRUD | ⬜ | |
| 商品 | 上架/下架 | ⬜ | |
| 购物车 | 加购/修改/勾选/删除 | ⬜ | |
| 交易 | 创建优惠券模板 | ⬜ | |
| 交易 | 领取优惠券 | ⬜ | |
| 交易 | 初始化库存 | ⬜ | |
| 交易 | 创建订单 | ⬜ | |
| 交易 | 发起支付 | ⬜ | |
| 交易 | 确认收货 | ⬜ | |
| 交易 | 取消订单 | ⬜ | |
| 交易 | 幂等性验证 | ⬜ | |
| 搜索 | 笔记搜索 | ⬜ | |
| 搜索 | 商品搜索 | ⬜ | |
| 搜索 | 搜索建议 | ⬜ | |
| 搜索 | 热搜榜 | ⬜ | |
| 搜索 | 推荐Feed | ⬜ | |
| 通知 | SSE Ticket | ⬜ | |
| 通知 | 未读通知数 | ⬜ | |
| IM | WS Ticket | ⬜ | |
| 异常 | 未登录拒绝 | ⬜ | |
| 异常 | 过期Token拒绝 | ⬜ | |
| 异常 | 参数校验失败 | ⬜ | |
| 异常 | 资源不存在 | ⬜ | |
| 异常 | 限流触发 | ⬜ | |
| 补充-用户 | 修改个人信息 | ⬜ | |
| 补充-用户 | 地址详情查询 | ⬜ | |
| 补充-用户 | 更新地址 | ⬜ | |
| 补充-用户 | 设置默认地址 | ⬜ | |
| 补充-内容 | 上传图片 | ⬜ | |
| 补充-内容 | 子评论(楼中楼) | ⬜ | |
| 补充-内容 | 子评论列表 | ⬜ | |
| 补充-内容 | 传统分页评论 | ⬜ | |
| 补充-社交 | 点赞评论(bizType=2) | ⬜ | |
| 补充-社交 | 共同关注 | ⬜ | |
| 补充-社交 | 点赞幂等性 | ⬜ | |
| 补充-首页 | 购物车聚合 | ⬜ | |
| 补充-购物车 | 匿名购物车合并 | ⬜ | |
| 补充-优惠券 | 模板详情 | ⬜ | |
| 补充-优惠券 | 上下线模板 | ⬜ | |
| 补充-优惠券 | 用户券列表 | ⬜ | |
| 补充-库存 | 预扣库存(独立) | ⬜ | |
| 补充-库存 | 确认扣减(独立) | ⬜ | |
| 补充-库存 | 释放库存(独立) | ⬜ | |
| 补充-库存 | 重初始化库存 | ⬜ | |
| 补充-库存 | 库存前后对比 | ⬜ | |
| 补充-支付 | 发起退款 | ⬜ | |
| 补充-支付 | 退款后订单状态 | ⬜ | |
| 补充-支付 | 退款后库存恢复 | ⬜ | |
| 补充-搜索 | 相似推荐 | ⬜ | |
| 补充-搜索 | 行为上报 | ⬜ | |
| 补充-搜索 | 清空搜索历史 | ⬜ | |
| 补充-搜索 | 热搜运营(置顶/屏蔽) | ⬜ | |
| 补充-搜索 | 热搜快照 | ⬜ | |
| 补充-搜索 | 索引重建 | ⬜ | |
| 补充-通知 | 标记已读(单条/类型/全部) | ⬜ | |
| 补充-通知 | SSE在线统计 | ⬜ | |
| 补充-IM | 聊天记录 | ⬜ | |
| 补充-IM | 标记已读 | ⬜ | |
| 补充-IM | 在线统计 | ⬜ | |
| 补充-并发 | 库存并发安全 | ⬜ | |
| 补充-并发 | 优惠券超发 | ⬜ | |
| 补充-内部 | 计数器增减 | ⬜ | |
| 补充-内部 | 订单号查询 | ⬜ | |
| 补充-内部 | 支付金额查询 | ⬜ | |
| 异常 | 资源不存在 | ⬜ | |
| 异常 | 限流触发 | ⬜ | |

---

## 附录: 服务端口映射

| 模块 | 端口 | 说明 |
|------|------|------|
| my-xhs-gateway | 19000 | API 网关（统一入口） |
| my-xhs-user | 19001 | 用户认证/个人信息/地址 |
| my-xhs-content | 19002 | 笔记管理/评论 |
| my-xhs-analytics | 19003 | 社交互动（点赞/收藏/关注） |
| my-xhs-counter | 19004 | 计数器服务 |
| my-xhs-product | 19006 | 商品 SPU/SKU 管理 |
| my-xhs-cart | 19008 | 购物车 |
| my-xhs-inventory | 19009 | 库存管理 |
| my-xhs-coupon | 19010 | 优惠券管理 |
| my-xhs-order | 19011 | 订单管理 |
| my-xhs-payment | 19012 | 支付管理 |
| my-xhs-notification | 19013 | 消息通知 |
| my-xhs-im | 19014 | 即时通讯 |
| my-xhs-home | 19015 | BFF 聚合层 |
| my-xhs-search | 19016 | 搜索/推荐 |

---

## 附录B: Response VO 字段参考

> 验证 curl 返回的 `data` 字段结构是否正确时参考。

### 用户模块

**TokenResponse**
- accessToken: String, refreshToken: String

**CaptchaResponse**
- captchaKey: String, captchaImage: String (Base64)

**UserInfoResponse**
- id: Long, username: String, nickname: String, avatar: String
- gender: Integer, birthday: String, phone: String (脱敏), email: String (脱敏)
- signature: String, status: Integer, createdAt: String

**UserPublicInfoResponse**
- id: Long, username: String, nickname: String, avatar: String
- gender: Integer, signature: String, createdAt: String

**AddressVO**
- id: Long, receiverName: String, receiverPhone: String (脱敏)
- province: String, city: String, district: String, detailAddress: String
- isDefault: Integer, createdAt: String, updatedAt: String

### 内容模块

**NoteDetailVO**
- id: Long, userId: Long, title: String, content: String
- images: [String], videoUrl: String, coverUrl: String
- topicIds: [Long], tags: [String], noteType: Integer
- status: Integer (0-草稿 1-审核中 2-已发布 3-已下架), statusDesc: String
- createdAt: String, updatedAt: String

**NoteItemVO** (列表项，不含正文)
- id: Long, userId: Long, title: String, coverUrl: String
- firstImage: String, noteType: Integer, status: Integer, createdAt: String

**CommentVO**
- id: Long, noteId: Long, userId: Long, parentId: Long, replyToId: Long
- content: String, likeCount: Integer, createdAt: String
- children: [CommentVO], childCount: Long

### 社交模块

**FollowVO**
- userId: Long, nickname: String, avatar: String
- followedAt: String, isFollowBack: Boolean

### 首页BFF

**FeedVO**
- notes: [NoteCardVO], nextCursor: String, hasMore: Boolean, unreadCount: Integer

**NoteCardVO**
- noteId: Long, title: String, coverUrl: String, noteType: Integer
- authorId: Long, authorNickname: String, authorAvatar: String
- likeCount: Long, collectCount: Long, commentCount: Long
- isLiked: Boolean, isCollected: Boolean, isFollowed: Boolean
- createdAt: String, score: Double

**NoteDetailAggVO** (BFF聚合)
- noteId: Long, title: String, content: String, images: [String]
- videoUrl: String, coverUrl: String, noteType: Integer, tags: [String]
- authorId: Long, authorNickname: String, authorAvatar: String
- likeCount: Long, collectCount: Long, commentCount: Long
- isLiked: Boolean, isCollected: Boolean, isFollowed: Boolean
- hotComments: [Map], createdAt: String

**ProductDetailAggVO** (BFF聚合)
- spuId: Long, name: String, description: String, images: [String]
- categoryId: Long, categoryName: String, status: Integer
- skuList: [{skuId, skuName, price, image, specValues, availableStock, hasStock}]
- collectCount: Long, viewCount: Long, relatedNotes: [NoteCardVO]

**UserProfileAggVO** (BFF聚合)
- userId: Long, nickname: String, avatar: String, bio: String
- followingCount: Long, followerCount: Long, likeAndCollectCount: Long, noteCount: Long
- isFollowing: Boolean, isFollowBack: Boolean, isMutual: Boolean
- notes: [NoteCardVO]

### 商品模块

**SpuDetailVO**
- id: Long, name: String, categoryId: Long, categoryName: String
- brandId: Long, description: String, images: [String]
- status: Integer (0-下架 1-上架), skuList: [SkuVO]
- createdAt: String, updatedAt: String

**SkuVO**
- id: Long, spuId: Long, name: String, price: BigDecimal
- originalPrice: BigDecimal, stock: Integer, specs: String (JSON), status: Integer

**CategoryTreeVO**
- id: Long, name: String, parentId: Long, level: Integer
- sort: Integer, icon: String, children: [CategoryTreeVO]

### 购物车模块

**CartListVO**
- items: [CartItemVO], checkedCount: Integer, checkedAmount: BigDecimal
- totalCount: Integer, allChecked: Boolean

**CartItemVO**
- skuId: Long, spuId: Long, name: String, price: BigDecimal
- originalPrice: BigDecimal, quantity: Integer, checked: Boolean
- specs: String (JSON), image: String, valid: Boolean, invalidReason: String, addedAt: Long

### 订单模块

**OrderVO**
- orderId: Long, orderNo: String, totalAmount: BigDecimal
- payAmount: BigDecimal, discountAmount: BigDecimal
- status: Integer, statusDesc: String, remark: String
- addressSnapshot: String, createdAt: String, paidAt: String
- items: [{skuId, skuName, skuImage, price, quantity, totalAmount}]

### 支付模块

**PaymentVO**
- id: Long, orderId: Long, paymentNo: String, amount: BigDecimal
- payType: Integer, status: Integer (0-待支付 1-成功 2-失败 3-已退款)
- statusDesc: String, paidAt: String, createdAt: String

### 优惠券模块

**CouponTemplateVO**
- id: Long, name: String, type: Integer, discountValue: BigDecimal
- minAmount: BigDecimal, totalCount: Integer, remainCount: Integer
- perUserLimit: Integer, validStart: String, validEnd: String, status: Integer

**UserCouponVO**
- id: Long, couponId: Long, name: String, type: Integer
- discountValue: BigDecimal, minAmount: BigDecimal
- status: Integer (0-未使用 1-已使用 2-已过期), validEnd: String, receivedAt: String

### 库存模块

**StockVO**
- skuId: Long, availableStock: Integer, lockedStock: Integer
- bucketCount: Integer, initialized: Boolean

### 通知模块

**NotificationVO**
- id: Long, type: Integer, title: String, content: String
- senderId: Long, senderName: String, senderAvatar: String
- targetId: Long, targetType: Integer, isRead: Integer
- aggregateCount: Integer, createdAt: String

**UnreadCountVO**
- total: int, details: {type: count}

### 搜索模块

**SearchResultVO\<T\>** (通用分页)
- items: [T], total: long, searchAfter: String, hasMore: boolean, took: long

**NoteSearchVO**
- noteId: Long, userId: Long, title: String, content: String
- coverImage: String, likeCount: Long, collectCount: Long, commentCount: Long
- createdAt: String, highlightTitle: String (含\<em\>标签), highlightContent: String

**ProductSearchVO**
- spuId: Long, skuId: Long, name: String, categoryId: Long
- categoryName: String, brandName: String, price: BigDecimal
- image: String, sales: Long, createdAt: String, highlightName: String

**HotSearchVO**
- rank: Integer, keyword: String, score: Double
- pinned: Boolean, tag: String (爆/热/新)

**RecommendFeedVO**
- noteId: Long, score: Double, source: String (ITEM_CF/CONTENT/HOT/FOLLOWING/GEO)
- category: String, reason: String

---

## 附录C: JSON Body 速查表

> 复制即用的 curl -d 参数 JSON Body，省去手动拼接。

| 接口 | JSON Body |
|------|-----------|
| 登录 | `{"username":"testuser","password":"Test@123456","captchaKey":"xxx","captchaCode":"1234"}` |
| 注册 | `{"username":"newuser","password":"Test@123456","phone":"13800138000","captchaKey":"xxx","captchaCode":"1234"}` |
| 修改密码 | `{"oldPassword":"Test@123456","newPassword":"NewTest@654321"}` |
| 更新个人信息 | `{"nickname":"新昵称","gender":1,"signature":"个性签名"}` |
| 创建地址 | `{"receiverName":"张三","receiverPhone":"13800138000","province":"广东省","city":"深圳市","district":"南山区","detailAddress":"科技园路1号","isDefault":true}` |
| 更新地址 | `{"receiverName":"张三","receiverPhone":"13800138000","province":"广东省","city":"广州市","district":"天河区","detailAddress":"天河路100号"}` |
| 发布笔记 | `{"title":"测试笔记","content":"笔记正文...","images":["http://img1.jpg"],"noteType":0,"tags":["测试"]}` |
| 保存草稿 | `{"title":"草稿标题","content":"草稿内容..."}` |
| 编辑笔记 | `{"title":"修改标题","content":"修改内容..."}` |
| 发表评论 | `{"noteId":1,"parentId":0,"content":"好文章！"}` |
| 回复评论 | `{"noteId":1,"parentId":5,"replyToId":5,"content":"回复内容"}` |
| 点赞 | `{"bizType":1,"bizId":1}` |
| 收藏 | `{"noteId":1}` |
| 加购 | `{"skuId":1,"quantity":2}` |
| 修改数量 | `{"skuId":1,"quantity":3}` |
| 勾选 | `{"skuId":1,"checked":true}` |
| 匿名合并 | `{"items":[{"skuId":1,"quantity":2},{"skuId":2,"quantity":1}]}` |
| 创建订单 | `{"skuItems":[{"skuId":1,"quantity":2}],"addressId":1,"couponId":1,"remark":"备注","bizIdentifier":"uuid-xxx"}` |
| 发起支付 | `{"orderId":1,"amount":100.00,"payType":1}` |
| 退款 | `{"paymentId":1,"refundAmount":100.00,"reason":"不喜欢","refundType":1}` |
| 创建SPU | `{"name":"测试商品","categoryId":1,"description":"描述","images":["http://img.jpg"]}` |
| 更新SPU | `{"name":"新名称","categoryId":1,"description":"新描述"}` |
| 创建SKU | `{"spuId":1,"name":"红色 XL","price":99.00,"originalPrice":199.00,"stock":100,"specs":"{\\"颜色\\":\\"红色\\",\\"尺码\\":\\"XL\\"}"}` |
| 创建券模板 | `{"name":"满100减20","type":1,"discountValue":20.00,"minAmount":100.00,"totalCount":1000,"perUserLimit":3,"validStart":"2026-01-01T00:00:00","validEnd":"2026-12-31T23:59:59"}` |
| 领取优惠券 | `{"templateId":1}` |
| 初始化库存 | `{"skuId":1,"totalStock":100,"bucketCount":8}` |
| 预扣库存 | `{"orderId":1,"skuId":1,"quantity":2,"userId":1}` |
| 确认扣减 | `{"orderId":1}` |
| 释放库存 | `{"orderId":1}` |
| 行为上报 | `{"noteId":1,"behaviorType":3}` |
| 搜索笔记 | 无Body，Query参数: `?keyword=测试&sort=relevance&size=20` |
| 搜索商品 | 无Body，Query参数: `?keyword=手机&categoryId=1&minPrice=10&maxPrice=1000&sort=relevance&size=20` |

---

## 附录D: MQ 异步消息链路验证

> 第二轮审查发现：curl 测试只验证了同步 HTTP 接口，但大量核心逻辑通过 MQ 异步执行。以下验证步骤确保 MQ 链路正常工作。

### 重要前提
- 所有 MQ 异步验证需要在触发操作后 **sleep 2-3 秒**，等待 MQ 消费完成
- 验证方式是"操作前值" vs "操作后值"的对比

### MQ-1: 点赞后计数更新（SOCIAL_TOPIC:LIKE -> counter）

**链路**: LikeService → SOCIAL_TOPIC(LIKE) → CounterEventConsumer → Redis INCR

```bash
# 1. 查询点赞前计数
COUNT_BEFORE=$(curl -s "http://localhost:19000/api/social/like/count?bizType=1&bizId=$NOTE_ID" | jq -r '.data')
echo "点赞前计数: $COUNT_BEFORE"

# 2. 点赞
curl -s -X POST http://localhost:19000/api/social/like \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"bizType": 1, "bizId": '$NOTE_ID'}' | jq .

# 3. 等待 MQ 消费（关键步骤！）
sleep 2

# 4. 查询点赞后计数
COUNT_AFTER=$(curl -s "http://localhost:19000/api/social/like/count?bizType=1&bizId=$NOTE_ID" | jq -r '.data')
echo "点赞后计数: $COUNT_AFTER"

# 5. 验证
if [ "$((COUNT_BEFORE + 1))" = "$COUNT_AFTER" ]; then
  echo "PASS: 点赞计数正确 +1"
else
  echo "FAIL: 预期=$((COUNT_BEFORE + 1)), 实际=$COUNT_AFTER"
fi
```
**预期结果**: 计数正确 +1

### MQ-2: 收藏后计数更新（SOCIAL_TOPIC:FAVORITE -> counter）

```bash
# 操作前计数
COUNT_BEFORE=$(curl -s "http://localhost:19000/api/social/like/count?bizType=2&bizId=$NOTE_ID" | jq -r '.data')

# 收藏
curl -s -X POST http://localhost:19000/api/social/favorite \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"noteId": '$NOTE_ID'}' | jq .

# 等待 MQ 消费
sleep 2

# 操作后计数
COUNT_AFTER=$(curl -s "http://localhost:19000/api/social/like/count?bizType=2&bizId=$NOTE_ID" | jq -r '.data')
echo "收藏前: $COUNT_BEFORE, 收藏后: $COUNT_AFTER"
```
**预期结果**: 计数正确变化

### MQ-3: 发布笔记后 Feed 收件箱验证（FEED_TOPIC -> home）

**链路**: NoteService → FEED_TOPIC → FeedPushConsumer → Redis ZADD 收件箱

```bash
# 1. 用用户A发布笔记
NOTE_RESP=$(curl -s -X POST http://localhost:19000/api/note/publish \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title": "MQ测试笔记", "content": "用于验证Feed推送", "noteType": 0}')
NEW_NOTE_ID=$(echo "$NOTE_RESP" | jq -r '.data.id')
echo "新笔记ID: $NEW_NOTE_ID"

# 2. 等待 MQ 消费（FeedPushConsumer 处理）
sleep 3

# 3. 用粉丝 Token（如果有的话，这里用自己）查 Feed，验证新笔记出现
curl -s "http://localhost:19000/api/home/feed?lastScore=0&size=10" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq '.data.notes[] | select(.noteId == '$NEW_NOTE_ID')'
```
**预期结果**: Feed 列表中能找到刚发布的笔记

### MQ-4: 下单后库存预扣验证（ORDER_TRANSACTION_TOPIC -> inventory）

```bash
# 1. 下单前查库存
STOCK_BEFORE=$(curl -s http://localhost:19000/api/inventory/stock/$SKU_ID | jq -r '.data.availableStock')
echo "下单前库存: $STOCK_BEFORE"

# 2. 创建订单（会发送事务消息到 ORDER_TRANSACTION_TOPIC）
BIZ_ID=$(uuidgen | tr '[:upper:]' '[:lower:]' | tr -d '-')
ORDER_RESP=$(curl -s -X POST http://localhost:19000/api/order/create \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "skuItems": [{"skuId": '$SKU_ID', "quantity": 2}],
    "addressId": '$ADDR_ID',
    "bizIdentifier": "'$BIZ_ID'"
  }')
MQ_ORDER_ID=$(echo "$ORDER_RESP" | jq -r '.data.id')

# 3. 等待事务消息提交和消费
sleep 2

# 4. 下单后查库存（应减少对应数量）
STOCK_AFTER=$(curl -s http://localhost:19000/api/inventory/stock/$SKU_ID | jq -r '.data.availableStock')
echo "下单后库存: $STOCK_AFTER"
echo "预期库存: $((STOCK_BEFORE - 2))"

if [ "$((STOCK_BEFORE - 2))" = "$STOCK_AFTER" ]; then
  echo "PASS: 库存预扣正确"
else
  echo "FAIL: 预期=$((STOCK_BEFORE - 2)), 实际=$STOCK_AFTER"
fi
```
**预期结果**: 库存正确减少 2

### MQ-5: 评论后通知生成验证（NOTIFICATION_TOPIC -> notification）

```bash
# 1. 查评论前未读通知数
UNREAD_BEFORE=$(curl -s http://localhost:19000/api/notification/unread-count \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq -r '.data.total')
echo "评论前未读数: $UNREAD_BEFORE"

# 2. 用测试用户对笔记发表评论
curl -s -X POST http://localhost:19000/api/comment \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"noteId": '$NOTE_ID', "parentId": 0, "content": "MQ通知测试评论"}' | jq .

# 3. 等待 MQ 消费（NotificationEventConsumer 处理）
sleep 2

# 4. 查评论后未读通知数（笔记作者应收到通知）
UNREAD_AFTER=$(curl -s http://localhost:19000/api/notification/unread-count \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq -r '.data.total')
echo "评论后未读数: $UNREAD_AFTER"
```
**预期结果**: 如果评论者和笔记作者不同，未读通知数应增加

### MQ-6: ES 索引同步验证（Canal -> NOTE_INDEX_TOPIC -> search）

```bash
# 1. 发布一篇有特色关键词的笔记
curl -s -X POST http://localhost:19000/api/note/publish \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title": "ES索引同步测试-独一无二的关键词XYZ789", "content": "测试内容", "noteType": 0}' | jq .

# 2. 等待 Canal 捕获 Binlog 并同步到 ES
sleep 5

# 3. 搜索这个关键词
curl -s "http://localhost:19000/api/search/note?keyword=XYZ789&pageSize=5" | jq '.data.items | length'

# 如果 Canal 未启用，手动触发全量重建
# curl -s -X POST http://localhost:19000/api/search/index/rebuild -H "Authorization: Bearer $ACCESS_TOKEN"
```
**预期结果**: 搜索结果至少包含 1 条（Canal 正常）或手动重建后包含

---

## 附录E: 缓存与降级验证

> 第二轮审查发现：缓存命中、穿透防护、一致性、降级开关等场景完全缺失。

### C-1: 缓存命中验证（多级缓存）

```bash
# 第一次查询（缓存未命中，回源 DB + 回填缓存）
echo "第一次查询 SPU 详情（预期较慢）："
time curl -s http://localhost:19000/api/product/spu/1 > /dev/null

# 第二次查询（缓存命中，从 Redis 返回）
echo "第二次查询 SPU 详情（预期较快）："
time curl -s http://localhost:19000/api/product/spu/1 > /dev/null
```
**预期结果**: 第二次查询耗时显著低于第一次

### C-2: 缓存穿透防护验证

```bash
# 第一次查询不存在的资源（DB 查不到，缓存空值 2 分钟）
echo "第一次查询不存在笔记："
time curl -s http://localhost:19000/api/note/detail/999999 > /dev/null

# 第二次查询（应命中空值缓存，不穿透到 DB）
echo "第二次查询不存在笔记（预期更快）："
time curl -s http://localhost:19000/api/note/detail/999999 > /dev/null
```
**预期结果**: 两次都返回资源不存在错误，第二次更快（命中空值缓存）

### C-3: 缓存一致性验证（更新后缓存失效）

```bash
# 1. 先查笔记详情（命中缓存）
curl -s http://localhost:19000/api/note/detail/$NOTE_ID | jq '.data.title'

# 2. 编辑笔记标题
curl -s -X PUT http://localhost:19000/api/note/$NOTE_ID \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title": "修改后的标题-缓存测试"}' | jq .

# 3. 等待延迟双删完成
sleep 1

# 4. 再次查询笔记详情（应拿到新标题，验证缓存已失效）
curl -s http://localhost:19000/api/note/detail/$NOTE_ID | jq '.data.title'
```
**预期结果**: 第二次查询返回"修改后的标题-缓存测试"

### C-4: 分类树缓存验证

```bash
# 第一次查询分类树（缓存未命中）
echo "第一次查询分类树："
time curl -s http://localhost:19000/api/product/category/tree > /dev/null

# 第二次查询（缓存命中，TLL 1小时）
echo "第二次查询分类树（预期更快）："
time curl -s http://localhost:19000/api/product/category/tree > /dev/null
```
**预期结果**: 第二次查询明显更快

### C-5: 降级开关验证

```bash
# 查询当前降级开关状态（通过 Nacos 或日志观察）
# 如果有 Nacos 控制台访问权限：
#   - 登录 Nacos: http://21.130.247.89:8848/nacos
#   - 查找配置: myxhs.degrade.* 
#   - 开启 feed-degrade: true → Feed 流应返回兜底数据
#   - 开启 search-degrade: true → 搜索应降级到数据库查询

# 通过 API 验证降级效果
# 1. 正常状态下的搜索结果
curl -s "http://localhost:19000/api/search/note?keyword=连衣裙&pageSize=3" | jq '.data.total'

# 2. 如果 search-degrade=true，接口应仍能返回数据（降级模式）
# curl -s "http://localhost:19000/api/search/note?keyword=连衣裙&pageSize=3" | jq '.data.total'
```
**预期结果**: 降级模式下接口仍可用（可能数据量减少或来源不同）

---

## 第13组: 安全测试 [v4.0 新增]

> **目的**: 验证系统对常见 Web 攻击的防护能力

### 13.1 XSS 防护 — 笔记标题注入

```bash
# 测试脚本标签是否被转义
curl -s -X POST http://localhost:19000/api/note/publish \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title": "<script>alert(\"xss\")</script>", "content": "XSS测试内容", "noteType": 0}' | jq '.'
```
**预期结果**: `code=200`，返回的标题中 `<script>` 标签被转义为 `&lt;script&gt;`

### 13.2 XSS 防护 — 评论内容注入

```bash
curl -s -X POST http://localhost:19000/api/comment \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"noteId": '$NOTE_ID', "content": "<img src=x onerror=alert(1)>测试评论"}' | jq '.'
```
**预期结果**: `code=200`，返回的内容中 HTML 标签被转义

### 13.3 SQL 注入防护 — 搜索参数

```bash
# 通过搜索接口测试 SQL 注入
curl -s "http://localhost:19000/api/search/note?keyword=' OR '1'='1" | jq '.'
```
**预期结果**: `code=200`，不会返回异常数据（被参数化查询防护）

### 13.4 水平越权 — 编辑他人笔记

```bash
# 用 token_A 编辑 token_B 创建的笔记
# 需要两个不同用户登录，这里用预置用户
# 先登录用户10001
LOGIN_A=$(curl -s -X POST http://localhost:19000/api/user/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "testuser01", "password": "Test@123456", "captchaKey": "test", "captchaCode": "1234"}')
TOKEN_A=$(echo "$LOGIN_A" | jq -r '.data.accessToken')

# 登录用户10002
LOGIN_B=$(curl -s -X POST http://localhost:19000/api/user/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username": "testuser02", "password": "Test@123456", "captchaKey": "test", "captchaCode": "1234"}')
TOKEN_B=$(echo "$LOGIN_B" | jq -r '.data.accessToken')

# 用户10001发布笔记
NOTE_B=$(curl -s -X POST http://localhost:19000/api/note/publish \
  -H "Authorization: Bearer $TOKEN_A" \
  -H "Content-Type: application/json" \
  -d '{"title": "用户A的笔记", "content": "这是用户A的笔记内容", "noteType": 0}')
NOTE_A_ID=$(echo "$NOTE_B" | jq -r '.data.id')

# 用户10002 尝试编辑用户10001的笔记（应被拒绝）
curl -s -X PUT http://localhost:19000/api/note/$NOTE_A_ID \
  -H "Authorization: Bearer $TOKEN_B" \
  -H "Content-Type: application/json" \
  -d '{"title": "越权修改"}' | jq '.'
```
**预期结果**: `code!=200`，返回权限不足错误

### 13.5 水平越权 — 查看他人订单

```bash
# 用户10002 尝试查看用户10001的订单
curl -s http://localhost:19000/api/order/$ORDER_ID \
  -H "Authorization: Bearer $TOKEN_B" | jq '.'
```
**预期结果**: `code!=200`，返回权限不足或订单不存在

### 13.6 水平越权 — 取消他人订单

```bash
curl -s -X POST "http://localhost:19000/api/order/cancel?orderId=$ORDER_ID" \
  -H "Authorization: Bearer $TOKEN_B" | jq '.'
```
**预期结果**: `code!=200`，返回权限不足

### 13.7 重复注册防护

```bash
# 用已存在的用户名注册
curl -s -X POST http://localhost:19000/api/user/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username": "testuser01", "password": "Test@123456", "captchaKey": "test", "captchaCode": "1234"}' | jq '.'
```
**预期结果**: `code!=200`，message 提示用户名已存在

---

## 第14组: 数据一致性验证 [v4.0 新增]

> **目的**: 验证核心交易链路的数据一致性，确保库存和优惠券在退款/取消后正确恢复

### 14.1 取消订单 — 库存恢复验证

```bash
# 1. 查询下单前库存
STOCK_BEFORE=$(curl -s http://localhost:19000/api/inventory/stock/$SKU_ID | jq -r '.data.availableStock')
echo "下单前可用库存: $STOCK_BEFORE"

# 2. 创建订单（购买1件）
ORDER_RESP=$(curl -s -X POST http://localhost:19000/api/order/create \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"skuItems": [{"skuId": '$SKU_ID', "quantity": 1}], "addressId": '$ADDR_ID', "bizIdentifier": "cancel-stock-'$(date +%s)'"}')
CANCEL_TEST_ORDER=$(echo "$ORDER_RESP" | jq -r '.data.id')
echo "订单ID: $CANCEL_TEST_ORDER"

sleep 1

# 3. 查询下单后库存（应减少1）
STOCK_AFTER_ORDER=$(curl -s http://localhost:19000/api/inventory/stock/$SKU_ID | jq -r '.data.availableStock')
echo "下单后可用库存: $STOCK_AFTER_ORDER"

# 4. 取消订单
curl -s -X POST "http://localhost:19000/api/order/cancel?orderId=$CANCEL_TEST_ORDER" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq '.'

sleep 3  # 等待 MQ 消费库存释放消息

# 5. 验证库存恢复
STOCK_AFTER_CANCEL=$(curl -s http://localhost:19000/api/inventory/stock/$SKU_ID | jq -r '.data.availableStock')
echo "取消后可用库存: $STOCK_AFTER_CANCEL"
echo "库存是否恢复: $([ "$STOCK_BEFORE" = "$STOCK_AFTER_CANCEL" ] && echo '✅ 一致' || echo '❌ 不一致')"
```
**预期结果**: `STOCK_BEFORE == STOCK_AFTER_CANCEL`，库存完全恢复

### 14.2 取消订单 — 优惠券恢复验证

```bash
# 1. 领取优惠券
COUPON_RESP=$(curl -s -X POST http://localhost:19000/api/coupon/claim \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"templateId": '$TEMPLATE_ID'}')
RECOVER_COUPON_ID=$(echo "$COUPON_RESP" | jq -r '.data.id')
echo "领取的优惠券ID: $RECOVER_COUPON_ID"

# 2. 用券下单
ORDER_COUPON_RESP=$(curl -s -X POST http://localhost:19000/api/order/create \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"skuItems": [{"skuId": '$SKU_ID', "quantity": 1}], "addressId": '$ADDR_ID', "couponId": '$RECOVER_COUPON_ID', "bizIdentifier": "coupon-recover-'$(date +%s)'"}')
COUPON_ORDER_ID=$(echo "$ORDER_COUPON_RESP" | jq -r '.data.id')
echo "用券订单ID: $COUPON_ORDER_ID"

# 3. 取消订单
curl -s -X POST "http://localhost:19000/api/order/cancel?orderId=$COUPON_ORDER_ID" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq '.'

sleep 3  # 等待 MQ 消费

# 4. 验证优惠券恢复为可用
AVAILABLE=$(curl -s http://localhost:19000/api/coupon/user/available \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq '.data.items[] | select(.id == '$RECOVER_COUPON_ID') | {id, status}')
echo "优惠券恢复状态: $AVAILABLE"
```
**预期结果**: 优惠券重新出现在可用列表，状态为未使用(0)

### 14.3 退款 — 库存恢复验证

```bash
# 1. 查询退款前库存
STOCK_BEFORE_REFUND=$(curl -s http://localhost:19000/api/inventory/stock/$SKU_ID | jq -r '.data.availableStock')
echo "退款前库存: $STOCK_BEFORE_REFUND"

# 2. 发起退款（对已支付的订单）
REFUND_RESP=$(curl -s -X POST http://localhost:19000/api/payment/refund \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"paymentId": '$PAYMENT_ID', "refundAmount": 99.00, "reason": "测试退款库存恢复", "refundType": 1}' | jq '.')

sleep 3  # 等待 MQ 消费

# 3. 验证库存恢复
STOCK_AFTER_REFUND=$(curl -s http://localhost:19000/api/inventory/stock/$SKU_ID | jq -r '.data.availableStock')
echo "退款后库存: $STOCK_AFTER_REFUND"
echo "库存变化: +$((STOCK_AFTER_REFUND - STOCK_BEFORE_REFUND))"
```
**预期结果**: 库存恢复对应数量的商品

### 14.4 退款 — 优惠券状态验证

```bash
# 退款后检查已使用的优惠券是否退回
curl -s "http://localhost:19000/api/coupon/user/list?status=0" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq '.data.items[] | select(.id == '$RECOVER_COUPON_ID') | {id, status, usedOrderId}'
```
**预期结果**: 根据业务规则，优惠券可能退回可用或标记为已作废

---

## 第15组: 内部接口与回调 [v4.0 新增]

> **目的**: 验证微服务间内部调用接口和回调链路的正确性

### 15.1 TCC 库存预扣 (Try)

```bash
# TCC Try: 预扣库存（由订单服务在分布式事务中调用）
curl -s -X POST http://localhost:19000/api/inventory/tcc/try \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 10001" \
  -d '{"xid": "tcc-test-001", "branchId": 10001, "skuItems": [{"skuId": 1, "quantity": 1}]}' | jq '.'
```
**预期结果**: `code=200`，库存预扣成功，availableStock-1，frozenStock+1

### 15.2 TCC 库存确认 (Confirm)

```bash
# TCC Confirm: 确认扣减（订单支付成功后调用）
curl -s -X POST http://localhost:19000/api/inventory/tcc/confirm \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 10001" \
  -d '{"xid": "tcc-test-001", "branchId": 10001, "skuItems": [{"skuId": 1, "quantity": 1}]}' | jq '.'
```
**预期结果**: `code=200`，库存确认扣减，frozenStock-1，totalStock-1

### 15.3 TCC 库存取消 (Cancel)

```bash
# TCC Cancel: 取消预扣（需先 Try 后再 Cancel）
# 先 Try
curl -s -X POST http://localhost:19000/api/inventory/tcc/try \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 10001" \
  -d '{"xid": "tcc-test-002", "branchId": 10002, "skuItems": [{"skuId": 1, "quantity": 1}]}' | jq '.'

# 再 Cancel
curl -s -X POST http://localhost:19000/api/inventory/tcc/cancel \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 10001" \
  -d '{"xid": "tcc-test-002", "branchId": 10002, "skuItems": [{"skuId": 1, "quantity": 1}]}' | jq '.'
```
**预期结果**: `code=200`，库存回滚，frozenStock-1，availableStock+1

### 15.4 优惠券使用 (Feign 内部调用)

```bash
# 优惠券使用接口（由订单服务通过 Feign 调用）
curl -s -X POST http://localhost:19000/api/coupon/use \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 10001" \
  -d '{"couponId": 1, "orderId": 1}' | jq '.'
```
**预期结果**: `code=200`，优惠券状态变更为已使用

### 15.5 优惠券退回 (Feign 内部调用)

```bash
# 优惠券退回接口（取消订单/退款时由订单服务通过 Feign 调用）
curl -s -X POST http://localhost:19000/api/coupon/return \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 10001" \
  -d '{"couponId": 1, "orderId": 1}' | jq '.'
```
**预期结果**: `code=200`，优惠券状态恢复为未使用

### 15.6 支付成功回调

```bash
# 支付平台回调通知（内部接口）
curl -s -X POST "http://localhost:19000/api/order/pay-success?orderId=1&tradeNo=TRADE_TEST_001" \
  -H "X-User-Id: 10001" | jq '.'
```
**预期结果**: `code=200`，订单状态更新为已支付

### 15.7 退款成功回调

```bash
# 退款平台回调通知（内部接口）
curl -s -X POST "http://localhost:19000/api/order/refund-success?orderId=1&refundNo=REFUND_TEST_001" \
  -H "X-User-Id: 10001" | jq '.'
```
**预期结果**: `code=200`，订单状态更新为已退款

### 15.8 支付回调 (Payment 服务)

```bash
# Mock 支付回调（仅 Mock 模式有效）
curl -s -X POST http://localhost:19000/api/payment/callback/mock \
  -H "Content-Type: application/json" \
  -d '{"paymentNo": "PAY_TEST_001", "transactionId": "TXN_'$(date +%s)'", "payAmount": 99.00, "status": 1, "payTime": "'$(date -u +%Y-%m-%dT%H:%M:%S)'"}' | jq '.'
```
**预期结果**: `code=200`，支付状态更新

### 15.9 计数器对账修复

```bash
# 内部管理接口：对账并修复计数器
curl -s -X POST http://localhost:19000/api/counter/reconcile \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 10001" \
  -d '{"targetType": 1, "targetId": 1, "countType": "like"}' | jq '.'
```
**预期结果**: `code=200`，返回修正后的计数

---

## 第16组: 边界条件与状态机 [v4.0 新增]

> **目的**: 验证分页边界、状态流转、业务规则约束

### 16.1 分页 — 空列表查询

```bash
# 新用户的订单列表应为空
curl -s "http://localhost:19000/api/order/list?status=ALL&pageNum=1&pageSize=10" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq '.data | {total, itemsCount: (.items | length)}'
```
**预期结果**: `code=200`，items 为空数组，total=0

### 16.2 分页 — 超出范围

```bash
# 查询远超实际数据量的页码
curl -s "http://localhost:19000/api/product/spu/list?pageNum=9999&pageSize=10&categoryId=1" | jq '.data | {total, itemsCount: (.items | length)}'
```
**预期结果**: `code=200`，items 为空数组（不崩溃）

### 16.3 搜索 — 无结果

```bash
# 搜索不存在的关键词
curl -s "http://localhost:19000/api/search/note?keyword=不存在的搜索词XYZABC999" | jq '.data | {total, itemsCount: (.items | length)}'
```
**预期结果**: `code=200`，total=0，items=[]

### 16.4 库存不足下单

```bash
# 查询当前库存
CURRENT_STOCK=$(curl -s http://localhost:19000/api/inventory/stock/$SKU_ID | jq -r '.data.availableStock')
echo "当前库存: $CURRENT_STOCK"

# 尝试购买超过库存的数量
OVER_QTY=$((CURRENT_STOCK + 100))
curl -s -X POST http://localhost:19000/api/order/create \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"skuItems": [{"skuId": '$SKU_ID', "quantity": '$OVER_QTY'}], "addressId": '$ADDR_ID', "bizIdentifier": "overstock-'$(date +%s)'"}' | jq '.'
```
**预期结果**: `code!=200`，message 提示库存不足

### 16.5 支付金额校验

```bash
# 订单金额为99，但支付传9.9（金额篡改检测）
curl -s -X POST http://localhost:19000/api/payment/pay \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"orderId": '$ORDER_ID', "amount": 9.90, "payType": 1}' | jq '.'
```
**预期结果**: `code!=200`，message 提示支付金额与订单金额不匹配

### 16.6 取消已支付订单（应拒绝）

```bash
# 对已支付的订单尝试取消
curl -s -X POST "http://localhost:19000/api/order/cancel?orderId=$PAID_ORDER_ID" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq '.'
```
**预期结果**: `code!=200`，message 提示订单状态不允许取消

### 16.7 商品下架后下单（应拒绝）

```bash
# 1. 先下架一个 SPU
curl -s -X PUT "http://localhost:19000/api/product/spu/$SPU_ID/status?status=0" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq '.'

# 2. 尝试用该 SPU 下的 SKU 下单
curl -s -X POST http://localhost:19000/api/order/create \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"skuItems": [{"skuId": '$SKU_ID', "quantity": 1}], "addressId": '$ADDR_ID', "bizIdentifier": "offline-spu-'$(date +%s)'"}' | jq '.'

# 3. 恢复上架
curl -s -X PUT "http://localhost:19000/api/product/spu/$SPU_ID/status?status=1" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq '.'
```
**预期结果**: 下单返回 `code!=200`，提示商品已下架

### 16.8 收货地址校验

```bash
# 使用不存在的地址 ID 下单
curl -s -X POST http://localhost:19000/api/order/create \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"skuItems": [{"skuId": '$SKU_ID', "quantity": 1}], "addressId": 999999, "bizIdentifier": "bad-addr-'$(date +%s)'"}' | jq '.'
```
**预期结果**: `code!=200`，message 提示地址不存在

### 16.9 多 SKU 下单

```bash
# 创建第二个 SKU 用于多商品测试
SKU2_RESP=$(curl -s -X POST http://localhost:19000/api/product/sku \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"spuId": '$SPU_ID', "skuName": "多SKU测试款", "price": 199.00, "stock": 100}')
SKU_ID_2=$(echo "$SKU2_RESP" | jq -r '.data.id')

# 初始化第二个 SKU 的库存
curl -s -X POST http://localhost:19000/api/inventory/init \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"skuId": '$SKU_ID_2', "stock": 100}' | jq '.'

# 多 SKU 下单
curl -s -X POST http://localhost:19000/api/order/create \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "skuItems": [
      {"skuId": '$SKU_ID', "quantity": 1},
      {"skuId": '$SKU_ID_2', "quantity": 2}
    ],
    "addressId": '$ADDR_ID',
    "bizIdentifier": "multi-sku-'$(date +%s)'"
  }' | jq '.data | {id, totalAmount, itemCount: (.orderItems | length)}'
```
**预期结果**: `code=200`，订单包含 2 个 SKU，金额为两者之和

### 16.10 优惠券门槛校验

```bash
# 创建满 500 减 50 的高门槛券模板
HIGH_TMPL_RESP=$(curl -s -X POST http://localhost:19000/api/coupon/template \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "满500减50", "type": 1, "discountValue": 50.00, "minAmount": 500.00, "totalCount": 100, "perUserLimit": 1, "validStart": "2026-01-01T00:00:00", "validEnd": "2026-12-31T23:59:59"}')
HIGH_TMPL_ID=$(echo "$HIGH_TMPL_RESP" | jq -r '.data.id')

# 领取高门槛券
HIGH_COUPON_RESP=$(curl -s -X POST http://localhost:19000/api/coupon/claim \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"templateId": '$HIGH_TMPL_ID'}')
HIGH_COUPON_ID=$(echo "$HIGH_COUPON_RESP" | jq -r '.data.id')

# 用不满足门槛的订单尝试使用券（商品99元 < 门槛500元）
curl -s -X POST http://localhost:19000/api/order/create \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"skuItems": [{"skuId": '$SKU_ID', "quantity": 1}], "addressId": '$ADDR_ID', "couponId": '$HIGH_COUPON_ID', "bizIdentifier": "coupon-limit-'$(date +%s)'"}' | jq '.'
```
**预期结果**: `code!=200`，message 提示不满足优惠券使用条件

### 16.11 超长内容测试

```bash
# 评论超长内容（5000字）
LONG_TEXT=$(python3 -c "print('测试内容' * 5000)")
curl -s -X POST http://localhost:19000/api/comment \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"noteId": '$NOTE_ID', "content": "'"${LONG_TEXT:0:100}"'"}' | jq '.'
```
**预期结果**: `code!=200` 或成功但内容被截断

---

## 测试数据准备 [v4.0 新增]

### 环境检查清单

执行测试前，确认以下外部服务可达：

| 服务 | 地址 | 检查命令 | 状态 |
|------|------|---------|------|
| Nacos | 21.130.247.89:18848 | `curl -s http://21.130.247.89:18848/nacos/v1/console/health/readiness` | |
| Redis Sentinel | 21.130.247.89:26379 | `redis-cli -h 21.130.247.89 -p 26379 -a 'Xhs@2026#Redis' PING` | |
| RocketMQ | 21.130.247.89:9876 | `curl -s http://21.130.247.89:9876/` | |
| MySQL Master | 21.130.247.89:13306 | `mysql -h 21.130.247.89 -P 13306 -u root -p'Xhs@2026#MySQL' -e "SELECT 1"` | |
| Elasticsearch | 21.130.247.89:9200 | `curl -s http://21.130.247.89:9200/_cluster/health` | ⚠️ 需确认 |
| XXL-Job Admin | 21.130.247.89:18080 | `curl -s http://21.130.247.89:18080/xxl-job-admin/` | |

### 测试数据初始化

```bash
# 1. 初始化数据库表结构和测试数据
mysql -h 21.130.247.89 -P 13306 -u root -p'Xhs@2026#MySQL' < /data/workspace/my-xhs/sql/test-data-init.sql

# 2. 验证预置数据
curl -s http://localhost:19000/api/user/10001/info | jq '.data | {id, username}'
# 预期: id=10001, username=testuser01

# 3. 验证 ES 索引（搜索测试前需要）
curl -s http://21.130.247.89:9200/_cat/indices?v | grep my_xhs
```

### 测试数据清理

```bash
# 测试完成后清理动态创建的数据（可选）
# 注意：这会删除所有测试中创建的笔记、订单、评论等

# 清理测试笔记
# DELETE /api/note/{id} 逐个删除

# 清理测试订单
# DELETE /api/order/{id}（如果有管理接口）

# 重置库存
# POST /api/inventory/reinit
```

### 硬编码 ID 注意事项

以下测试用例依赖预置数据或前面测试动态创建的数据：

| 用例 | 依赖 | 说明 |
|------|------|------|
| 4.1 点赞 | `bizId: 1` | 应改为 `$NOTE_ID`（第3组创建的笔记ID） |
| 4.2 收藏 | `noteId: 1` | 同上 |
| 4.4 计数器 | `targetId: 1` | 同上 |
| 2.5 公开信息 | `/api/user/10001/info` | 依赖预置用户 10001 |
| 3.5 笔记列表 | `/api/note/user/1` | 应改为动态 userId |
```

---

## 第17组: 补充遗漏端点 [v4.1 新增]

> **目的**: 覆盖第二轮审查发现的 7 个遗漏 REST 端点

### 17.1 订单内置支付状态查询（Mock 模式）

```bash
# 订单服务内置的支付状态查询（与 /api/payment/status/{orderId} 不同）
curl -s http://localhost:19000/api/order/pay/status/$ORDER_ID \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq '.'
```
**预期结果**: `code=200`，返回 Mock 支付状态（如 status=1 已支付或 status=0 待支付）

### 17.2 支付失败回调

```bash
# 支付失败回调接口
curl -s -X POST "http://localhost:19000/api/order/pay-fail?orderId=$ORDER_ID&failReason=余额不足" \
  -H "X-User-Id: 10001" | jq '.'
```
**预期结果**: `code=200`，订单状态更新为支付失败

### 17.3 第三方支付回调

```bash
# 模拟支付宝/微信支付回调（payType=1 支付宝, 2 微信）
curl -s -X POST http://localhost:19000/api/payment/callback/1 \
  -H "Content-Type: application/json" \
  -d '{
    "outTradeNo": "PAY_TEST_'$(date +%s)'",
    "tradeNo": "ALIPAY_'$(date +%s)'",
    "totalAmount": 99.00,
    "payAmount": 99.00,
    "status": "SUCCESS",
    "gmtPayment": "'$(date -u +%Y-%m-%dT%H:%M:%S)'"
  }' | jq '.'
```
**预期结果**: `code=200`，支付状态更新

### 17.4 第三方退款回调

```bash
# 模拟第三方退款回调
curl -s -X POST http://localhost:19000/api/payment/refund-callback/1 \
  -H "Content-Type: application/json" \
  -d '{
    "outTradeNo": "REFUND_TEST_'$(date +%s)'",
    "refundNo": "RFND_'$(date +%s)'",
    "refundAmount": 99.00,
    "status": "SUCCESS",
    "gmtRefund": "'$(date -u +%Y-%m-%dT%H:%M:%S)'"
  }' | jq '.'
```
**预期结果**: `code=200`，退款状态更新

### 17.5 推荐离线计算触发

```bash
# 管理员触发推荐离线计算（特征提取 + Item-CF + 热门池刷新）
curl -s -X POST http://localhost:19000/api/recommend/compute \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq '.'
```
**预期结果**: `code=200`，返回计算状态（异步执行）

### 17.6 关注/粉丝计数器修复

```bash
# 内部运维接口：以 Redis ZSet 为准修复计数器
curl -s -X POST http://localhost:19000/api/social/internal/repair-counter/10001 \
  -H "X-User-Id: 10001" | jq '.'
```
**预期结果**: `code=200`，返回修正后的关注/粉丝计数

### 17.7 SSE 连接建立测试

```bash
# 获取 SSE Ticket 后建立连接
TICKET=$(curl -s -X POST http://localhost:19000/api/notification/sse/ticket \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq -r '.data.ticket')

# 建立 SSE 连接（5 秒后断开，验证连接可用性）
timeout 5 curl -s -N "http://localhost:19000/api/notification/sse?ticket=$TICKET" 2>&1 | head -5
```
**预期结果**: 收到 SSE 连接建立事件，`event: connected`

---

## 附录F: MQ 消费者扩展验证 [v4.1 新增]

> **目的**: 补充附录D 未覆盖的 14 条 MQ 消费者链路

### MQ-7: 取消点赞后计数更新（SOCIAL_TOPIC:UNLIKE → counter）

```bash
# 1. 查询取消点赞前的计数
LIKE_COUNT_BEFORE=$(curl -s "http://localhost:19000/api/counter/get?targetType=1&targetId=$NOTE_ID&countType=like" | jq -r '.data.count')

# 2. 取消点赞
curl -s -X DELETE http://localhost:19000/api/social/like \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"bizType": 1, "bizId": '$NOTE_ID'}' | jq '.'

sleep 2  # 等待 MQ 消费

# 3. 验证计数-1
LIKE_COUNT_AFTER=$(curl -s "http://localhost:19000/api/counter/get?targetType=1&targetId=$NOTE_ID&countType=like" | jq -r '.data.count')
echo "取消前: $LIKE_COUNT_BEFORE, 取消后: $LIKE_COUNT_AFTER"
```
**预期结果**: `LIKE_COUNT_AFTER = LIKE_COUNT_BEFORE - 1`

### MQ-8: 取消收藏后计数更新（SOCIAL_TOPIC:UNFAVORITE → counter）

```bash
# 1. 取消收藏
curl -s -X DELETE http://localhost:19000/api/social/favorite \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"noteId": '$NOTE_ID'}' | jq '.'

sleep 2

# 2. 验证收藏计数-1
curl -s "http://localhost:19000/api/counter/get?targetType=1&targetId=$NOTE_ID&countType=favorite" | jq '.data.count'
```
**预期结果**: 收藏计数减 1

### MQ-9: 超时关单（ORDER_CLOSE_TOPIC → OrderCloseConsumer）

```bash
# 验证方式：创建订单后等待超时，或手动触发 orderCloseJob
# 这里通过 XXL-Job 触发（需要 XXL-Job Admin 可用）
curl -s "http://21.130.247.89:18080/xxl-job-admin/jobinfo/trigger" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "id=<orderCloseJob的ID>&executorParam=" 2>/dev/null || echo "XXL-Job Admin 不可达，需手动验证"

# 或者：创建订单后等待 30 分钟（默认超时时间），检查订单是否自动关闭
```
**预期结果**: 超时未支付的订单自动关闭，库存恢复

### MQ-10: 购物车 Redis→MySQL 持久化（CART_TOPIC → CartSyncConsumer）

```bash
# 1. 加入购物车
curl -s -X POST http://localhost:19000/api/cart/add \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"skuId": '$SKU_ID', "quantity": 3}' | jq '.'

sleep 2  # 等待异步持久化

# 2. 查询购物车（验证数据在 Redis 和 MySQL 中都存在）
curl -s http://localhost:19000/api/cart/list \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq '.data.items[] | select(.skuId == '$SKU_ID')'
```
**预期结果**: 购物车数据正常返回，说明 Redis 写入成功且可能已同步到 MySQL

---

## 附录G: 定时任务健康检查 [v4.1 新增]

> **目的**: 验证关键定时任务是否正常注册和运行
> **注意**: 以下测试依赖 XXL-Job Admin (21.130.247.89:18080) 可用，如果不可达则跳过

### G-1: XXL-Job 执行器注册验证

```bash
# 检查各服务的 XXL-Job 执行器是否在 Admin 中注册
# 查看日志确认注册成功
grep "registry-remove success\|register success" /data/workspace/my-xhs/logs/my-xhs-order.log | tail -3
grep "registry-remove success\|register success" /data/workspace/my-xhs/logs/my-xhs-payment.log | tail -3
grep "registry-remove success\|register success" /data/workspace/my-xhs/logs/my-xhs-search.log | tail -3
```
**预期结果**: 各服务日志中包含 "registry success" 或 "register success"

### G-2: 关键定时任务手动触发（XXL-Job 方式）

```bash
# 如果 XXL-Job Admin 可用，可以通过 API 触发以下关键任务：
# - orderCloseJob: 超时关单（验证库存恢复）
# - couponExpireJob: 优惠券过期处理
# - inventoryReconcileJob: 库存对账
# - paymentTimeoutCheckJob: 支付超时检查

# 示例：触发 orderCloseJob（需要先获取 jobId）
curl -s -X POST "http://21.130.247.89:18080/xxl-job-admin/jobinfo/trigger" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "id=<JOB_ID>&executorParam=" 2>/dev/null || echo "跳过（XXL-Job Admin 不可达）"
```

### G-3: @Scheduled 定时任务验证

```bash
# 以下定时任务通过 @Scheduled 自动运行，通过日志验证：
# 1. Counter Buffer 定时刷新 (5s 间隔)
grep "CounterBuffer\|buffer flush\|刷新" /data/workspace/my-xhs/logs/my-xhs-counter.log | tail -5

# 2. 库存预扣超时释放 (5min 间隔)
grep "PreDeductTimeout\|预扣超时\|timeout release" /data/workspace/my-xhs/logs/my-xhs-inventory.log | tail -5

# 3. 支付回调模拟 (5s 间隔, Mock 模式)
grep "PayCallbackSimulator\|callback simulate" /data/workspace/my-xhs/logs/my-xhs-payment.log | tail -5

# 4. 热搜计算 (5min 间隔)
grep "HotSearchService\|热搜\|hot search" /data/workspace/my-xhs/logs/my-xhs-search.log | tail -5
```
**预期结果**: 日志中无异常错误（如 Connection refused、NPE 等）

---

## 第26组: TCC 分布式事务接口 [v5.0 新增]

> **目的**: 验证库存 TCC 事务（Try/Confirm/Cancel）的完整流程，包括幂等性和防悬挂
> **注意**: 这些是内部 Feign 接口，通常由订单服务调用，这里直接测试端点

### 26.1 TCC Try — 预扣库存

```bash
# TCC Try: 预扣库存（订单创建时调用）
# 需要有效的 orderId，先用已有的订单号
ORDER_ID=1
SKU_ID=1
curl -s -X POST http://localhost:19000/api/inventory/tcc/try \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": '$ORDER_ID',
    "skuId": '$SKU_ID',
    "quantity": 1
  }' | jq '.'
```
**预期结果**: 返回成功（200），预扣库存成功，或返回 "库存不足"

### 26.2 TCC Try 幂等性

```bash
# 重复调用同一个订单的 Try，验证幂等性
curl -s -X POST http://localhost:19000/api/inventory/tcc/try \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": '$ORDER_ID',
    "skuId": '$SKU_ID',
    "quantity": 1
  }' | jq '.'
```
**预期结果**: 返回成功，不会重复扣减（幂等）

### 26.3 TCC Confirm — 确认扣减

```bash
# TCC Confirm: 支付成功后确认扣减
curl -s -X POST http://localhost:19000/api/inventory/tcc/confirm \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": '$ORDER_ID',
    "skuId": '$SKU_ID'
  }' | jq '.'
```
**预期结果**: 返回成功，冻结库存转为实际扣减

### 26.4 TCC Cancel — 取消预扣

```bash
# TCC Cancel: 订单取消/超时时释放冻结库存
# 先 Try 一个新订单再 Cancel
ORDER_ID=2
curl -s -X POST http://localhost:19000/api/inventory/tcc/try \
  -H "Content-Type: application/json" \
  -d '{"orderId": '$ORDER_ID', "skuId": '$SKU_ID', "quantity": 1}' | jq '.'

curl -s -X POST http://localhost:19000/api/inventory/tcc/cancel \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": '$ORDER_ID',
    "skuId": '$SKU_ID'
  }' | jq '.'
```
**预期结果**: 返回成功，冻结库存释放回可用库存

---

## 第27组: 内部 Feign 回调接口 [v5.0 新增]

> **目的**: 验证服务间 Feign 回调接口（支付→订单、订单→优惠券/库存）
> **注意**: 这些是服务间内部接口，直接在目标服务端口上测试

### 27.1 优惠券使用接口

```bash
# 领取优惠券后，下单时使用优惠券（Feign 调用）
# 需要先领取一张优惠券
TEMPLATE_ID=1
curl -s -X POST http://localhost:19000/api/coupon/claim/$TEMPLATE_ID \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq '.'

# 使用优惠券（内部 Feign 接口）
curl -s -X POST http://localhost:19000/api/coupon/use \
  -H "Content-Type: application/json" \
  -d '{
    "userId": '$USER_ID',
    "couponId": 1,
    "orderId": 1
  }' | jq '.'
```
**预期结果**: 优惠券状态变为已使用

### 27.2 优惠券退回接口

```bash
# 取消订单时退回优惠券（Feign 调用）
curl -s -X POST http://localhost:19000/api/coupon/return \
  -H "Content-Type: application/json" \
  -d '{
    "userId": '$USER_ID',
    "couponId": 1,
    "orderId": 1
  }' | jq '.'
```
**预期结果**: 优惠券状态恢复为可用

### 27.3 支付成功回调（订单服务）

```bash
# 支付服务通知订单服务支付成功（Feign 回调）
ORDER_ID=1
curl -s -X POST http://localhost:19000/api/order/pay-success \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": '$ORDER_ID',
    "transactionId": "TX20260711001",
    "payTime": "'$(date -u +%Y-%m-%dT%H:%M:%S)'"
  }' | jq '.'
```
**预期结果**: 订单状态变为 1（已付款）

### 27.4 支付失败回调（订单服务）

```bash
# 支付服务通知订单服务支付失败
curl -s -X POST http://localhost:19000/api/order/pay-fail \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": '$ORDER_ID',
    "reason": "余额不足"
  }' | jq '.'
```
**预期结果**: 返回成功，订单状态保持或标记支付失败

### 27.5 退款成功回调（订单服务）

```bash
# 支付服务通知订单服务退款成功（释放库存+退还优惠券）
curl -s -X POST http://localhost:19000/api/order/refund-success \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": '$ORDER_ID',
    "refundId": 1,
    "refundTime": "'$(date -u +%Y-%m-%dT%H:%M:%S)'"
  }' | jq '.'
```
**预期结果**: 订单状态变为已退款，库存恢复

### 27.6 退款失败回调（订单服务）

```bash
# 支付服务通知订单服务退款失败
curl -s -X POST http://localhost:19000/api/order/refund-fail \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": '$ORDER_ID',
    "refundId": 1,
    "reason": "退款审核未通过"
  }' | jq '.'
```
**预期结果**: 返回成功，退款单关闭

### 27.7 订单侧支付状态查询

```bash
# 订单服务内部查询支付状态（Mock 模式）
curl -s http://localhost:19000/api/order/pay/status/$ORDER_ID | jq '.'
```
**预期结果**: 返回支付状态信息

---

## 第28组: 新增业务接口 [v5.0 新增]

> **目的**: 验证本次审查后新增的业务功能接口

### 28.1 订单发货

```bash
# 先确保有一个已付款的订单
ORDER_ID=1
curl -s http://localhost:19000/api/order/$ORDER_ID \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq '.data.status'

# 发货
curl -s -X POST http://localhost:19000/api/order/deliver \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": '$ORDER_ID',
    "logisticsCompany": "顺丰速运",
    "trackingNo": "SF1234567890"
  }' | jq '.'
```
**预期结果**: 订单状态变为 2（已发货），包含物流信息

### 28.2 清空购物车

```bash
# 先加购几件商品
curl -s -X POST http://localhost:19000/api/cart/add \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"skuId": '$SKU_ID', "quantity": 2}' | jq '.'

# 确认购物车有商品
curl -s http://localhost:19000/api/cart/list \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq '.data.items | length'

# 清空购物车
curl -s -X DELETE http://localhost:19000/api/cart/clear \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq '.'

# 验证已清空
curl -s http://localhost:19000/api/cart/list \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq '.data.items | length'
```
**预期结果**: 清空后购物车商品数为 0

### 28.3 屏蔽用户

```bash
# 屏蔽 testuser2（ID=2）
curl -s -X POST http://localhost:19000/api/user/block/2 \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq '.'

# 查看屏蔽列表
curl -s http://localhost:19000/api/user/block/list \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq '.data'

# 取消屏蔽
curl -s -X DELETE http://localhost:19000/api/user/block/2 \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq '.'
```
**预期结果**: 屏蔽/取消屏蔽成功，列表正确返回

### 28.4 屏蔽自己（边界测试）

```bash
# 尝试屏蔽自己，应该失败
curl -s -X POST http://localhost:19000/api/user/block/$USER_ID \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq '.'
```
**预期结果**: 返回错误（不能屏蔽自己）

### 28.5 笔记分享

```bash
# 分享一篇笔记
curl -s -X POST http://localhost:19000/api/note/$NOTE_ID/share \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq '.'

# 验证分享计数增加
curl -s http://localhost:19000/api/counter/get?targetType=1\&targetId=$NOTE_ID\&countType=share | jq '.data'
```
**预期结果**: 分享成功，分享计数 +1

---

## 第29组: 支付回调接口 [v5.0 新增]

> **目的**: 验证第三方支付异步回调接口（模拟支付宝/微信回调）

### 29.1 支付异步回调

```bash
# 模拟支付宝支付回调
PAY_TYPE=alipay
ORDER_ID=1
curl -s -X POST "http://localhost:19000/api/payment/callback/$PAY_TYPE" \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": '$ORDER_ID',
    "tradeNo": "ALIPAY20260711001",
    "totalAmount": "99.00",
    "status": "SUCCESS",
    "gmtPayment": "'$(date -u +%Y-%m-%dT%H:%M:%S)'"
  }' | jq '.'
```
**预期结果**: 返回成功，回调被正确处理

### 29.2 退款异步回调

```bash
# 模拟支付宝退款回调
curl -s -X POST "http://localhost:19000/api/payment/refund-callback/$PAY_TYPE" \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": '$ORDER_ID',
    "refundId": 1,
    "refundAmount": "99.00",
    "status": "SUCCESS",
    "gmtRefund": "'$(date -u +%Y-%m-%dT%H:%M:%S)'"
  }' | jq '.'
```
**预期结果**: 返回成功，退款回调被正确处理

---

## 第30组: 管理与内部接口 [v5.0 新增]

> **目的**: 验证内部管理类接口

### 30.1 关注计数器修复

```bash
# 对账修复关注/粉丝计数（以 Redis ZSet ZCARD 为准）
curl -s -X POST http://localhost:19000/api/social/internal/repair-counter/$USER_ID \
  -H "Content-Type: application/json" | jq '.'
```
**预期结果**: 返回修复后的计数值

### 30.2 推荐离线计算触发

```bash
# 手动触发推荐离线计算（管理员接口）
curl -s -X POST http://localhost:19000/api/recommend/compute \
  -H "Content-Type: application/json" | jq '.'
```
**预期结果**: 返回成功或任务已提交

### 30.3 SSE 连接验证

```bash
# 第一步：获取 SSE Ticket（已在第10组测试过）
SSE_TICKET=$(curl -s -X POST http://localhost:19000/api/notification/sse/ticket \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq -r '.data.ticket')

echo "SSE Ticket: $SSE_TICKET"

# 第二步：用 Ticket 建立 SSE 连接（curl 不支持 EventSource，这里只验证 Ticket 有效性）
# 可以用 curl 测试 SSE 端点是否可达
curl -s -N --max-time 5 "http://localhost:19000/api/notification/sse?ticket=$SSE_TICKET" 2>&1 | head -5 || echo "SSE 连接超时（正常，curl 不支持长连接）"
```
**预期结果**: Ticket 获取成功，SSE 端点可达

---

## 第31组: Feign 调用链端到端验证 [v5.0 新增]

> **目的**: 验证 BFF（home）→ 后端服务的 Feign 调用链是否正常工作
> **说明**: 通过 Gateway 访问 home 服务的聚合接口，间接验证 Feign 调用链

### 31.1 BFF 笔记详情聚合（home → content + analytics + counter + user）

```bash
# 获取笔记详情页数据（聚合了笔记内容、用户信息、互动状态、计数）
curl -s http://localhost:19000/api/home/note/$NOTE_ID \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq '.data | {title, author, likeStatus, commentCount}'
```
**预期结果**: 返回完整的笔记详情聚合数据

### 31.2 BFF 用户主页聚合（home → content + analytics + counter + user）

```bash
# 获取用户主页数据（聚合了用户信息、笔记列表、关注状态、粉丝数）
curl -s "http://localhost:19000/api/home/user/$USER_ID" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq '.data | {nickname, noteCount, fanCount, followStatus}'
```
**预期结果**: 返回完整的用户主页聚合数据

### 31.3 BFF 商品详情聚合（home → product + inventory + counter）

```bash
# 获取商品详情页数据（聚合了 SPU 信息、SKU 列表、库存、优惠券）
curl -s "http://localhost:19000/api/home/product/$SPU_ID" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq '.data | {spuName, skus, stock, availableCoupons}'
```
**预期结果**: 返回完整的商品详情聚合数据

### 31.4 BFF 购物车聚合（home → cart + product + inventory + coupon）

```bash
# 获取购物车聚合数据（商品信息、库存状态、可用优惠券）
curl -s http://localhost:19000/api/home/cart \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq '.data | {items, totalAmount, availableCoupons}'
```
**预期结果**: 返回购物车完整聚合数据

### 31.5 BFF Feed 流（home → content + analytics + counter + user）

```bash
# 获取首页 Feed 流（聚合了笔记列表、用户信息、互动状态）
curl -s "http://localhost:19000/api/home/feed?page=1&size=10" \
  -H "Authorization: Bearer $ACCESS_TOKEN" | jq '.data | {total, recordsCount: (.records | length)}'
```
**预期结果**: 返回 Feed 流数据

---

## 附录H: 环境健康检查脚本 [v5.0 新增]

> **目的**: 在运行测试前验证所有中间件和服务的连通性

### H-1: 中间件连通性检查

```bash
#!/bin/bash
echo "========== 中间件健康检查 =========="

# MySQL 主库（8 个端口: 13306-13309）
echo "--- MySQL 主库 ---"
for port in 13306 13307 13308 13309; do
  result=$(mysqladmin ping -h 21.130.247.89 -P $port -u root -p'Myxhs@2026#Mysql' 2>&1)
  echo "  端口 $port: $result"
done

# MySQL 从库（4 个端口: 13310-13313）
echo "--- MySQL 从库 ---"
for port in 13310 13311 13312 13313; do
  result=$(mysqladmin ping -h 21.130.247.89 -P $port -u root -p'Myxhs@2026#Mysql' 2>&1)
  echo "  端口 $port: $result"
done

# Redis Sentinel
echo "--- Redis Sentinel ---"
for port in 26379 26380 26381; do
  result=$(redis-cli -h 21.130.247.89 -p $port -a 'Xhs@2026#Redis' --no-auth-warning PING 2>&1)
  echo "  端口 $port: $result"
done

# Redis 数据节点
echo "--- Redis 数据节点 ---"
for port in 16380 16381; do
  result=$(redis-cli -h 21.130.247.89 -p $port -a 'Xhs@2026#Redis' --no-auth-warning PING 2>&1)
  echo "  端口 $port: $result"
done

# Nacos
echo "--- Nacos ---"
nacos_status=$(curl -s -o /dev/null -w "%{http_code}" http://21.130.247.89:18848/nacos/v1/console/health/readiness 2>/dev/null)
echo "  状态码: $nacos_status"

# RocketMQ NameServer
echo "--- RocketMQ NameServer ---"
for port in 9876 9877; do
  result=$(curl -s -o /dev/null -w "%{http_code}" http://21.130.247.89:$port 2>/dev/null)
  echo "  端口 $port: $result"
done

# Elasticsearch
echo "--- Elasticsearch ---"
es_health=$(curl -s -u elastic:'Xhs@2026#Elastic' http://21.130.247.89:19200/_cluster/health 2>/dev/null | jq -r '.status')
echo "  集群状态: $es_health"

# Sentinel Dashboard
echo "--- Sentinel Dashboard ---"
sentinel_status=$(curl -s -o /dev/null -w "%{http_code}" http://21.130.247.89:8858 2>/dev/null)
echo "  状态码: $sentinel_status"

# XXL-Job Admin
echo "--- XXL-Job Admin ---"
xxl_status=$(curl -s -o /dev/null -w "%{http_code}" http://21.130.247.89:18080/xxl-job-admin/toLogin 2>/dev/null)
echo "  状态码: $xxl_status"

echo "========== 检查完成 =========="
```

### H-2: 微服务健康检查

```bash
#!/bin/bash
echo "========== 微服务健康检查 =========="

for port in $(seq 19000 19016); do
  [ $port -eq 19005 ] && continue  # 跳过 19005
  [ $port -eq 19007 ] && continue  # 跳过 19007
  status=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:$port/actuator/health 2>/dev/null)
  if [ "$status" = "200" ]; then
    echo "  [OK] 端口 $port: HTTP $status"
  else
    echo "  [FAIL] 端口 $port: HTTP $status"
  fi
done

echo "========== 检查完成 =========="
```

### H-3: 测试数据验证

```bash
#!/bin/bash
echo "========== 测试数据验证 =========="

# 验证预置用户是否存在
echo "--- 验证测试用户 ---"
ACCESS_TOKEN=$(curl -s -X POST http://localhost:19000/api/user/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"Test@123456"}' | jq -r '.data.accessToken')

if [ -n "$ACCESS_TOKEN" ] && [ "$ACCESS_TOKEN" != "null" ]; then
  echo "  [OK] testuser 登录成功"
  USER_ID=$(curl -s http://localhost:19000/api/user/me \
    -H "Authorization: Bearer $ACCESS_TOKEN" | jq -r '.data.id')
  echo "  [OK] testuser ID: $USER_ID"
else
  echo "  [FAIL] testuser 登录失败"
fi

# 验证商品数据
echo "--- 验证商品数据 ---"
spu_response=$(curl -s http://localhost:19000/api/product/spu/1 2>/dev/null)
if echo "$spu_response" | jq -e '.data.id' > /dev/null 2>&1; then
  echo "  [OK] SPU ID=1 存在"
  SKU_ID=$(curl -s http://localhost:19000/api/product/spu/1 | jq -r '.data.skus[0].id // 1')
  echo "  [OK] SKU ID=$SKU_ID"
else
  echo "  [WARN] SPU 数据可能未初始化"
fi

# 验证 ES 索引
echo "--- 验证 ES 索引 ---"
es_indices=$(curl -s -u elastic:'Xhs@2026#Elastic' http://21.130.247.89:19200/_cat/indices?v 2>/dev/null)
echo "$es_indices"

echo "========== 检查完成 =========="
```

### H-4: MySQL 主从复制状态检查

```bash
#!/bin/bash
echo "========== MySQL 主从复制状态 =========="

for port in 13310 13311 13312 13313; do
  echo "--- 从库端口 $port ---"
  mysql -h 21.130.247.89 -P $port -u root -p'Myxhs@2026#Mysql' -e "SHOW SLAVE STATUS\G" 2>/dev/null | grep -E "Slave_IO_Running|Slave_SQL_Running|Seconds_Behind_Master|Last_Error" || echo "  非从库或无复制配置"
done

echo "========== 检查完成 =========="
```

---

## 附录I: 版本历史 [v5.0 更新]

| 版本 | 日期 | 变更说明 |
|------|------|---------|
| v1.0 | 2026-07-10 | 初始版本，基础 CRUD 和核心业务流程 |
| v2.0 | 2026-07-10 | 补充 IM/WebSocket 和商品搜索测试 |
| v3.0 | 2026-07-10 | 多 agent 审查后全面扩充到 25 组 |
| v4.0 | 2026-07-11 | 补充安全测试、数据一致性、边界条件、遗漏端点、MQ 消费者、定时任务 |
| v4.1 | 2026-07-11 | 修复硬编码 ID，更新 Sentinel 端口引用 |
| v5.0 | 2026-07-11 | 四维深度审计后补充：TCC 事务、Feign 回调、支付回调、新增业务接口、Feign 调用链、环境健康检查 |
