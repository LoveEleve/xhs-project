# my-xhs API 接口文档

> 最后更新：2026-05-30 | 来源：逐 Controller 扫描验证 | 共 14 个服务模块

---

## 一、鉴权体系

```
请求 → Gateway (19000)
        ├── ① 白名单匹配 → 放行（完全公开）
        ├── ② HMAC-SHA256 签名校验
        └── ③ JWT Bearer Token 解析 → 注入 X-User-Id Header → 转发微服务
```

| 场景 | 需要 | 说明 |
|------|------|------|
| 注册/登录 | 无 | 白名单，直接调 |
| 笔记详情/评论列表/用户公开信息 | 无 | 白名单 |
| 需登录接口（走网关） | `Authorization: Bearer $TOKEN` | 网关自动注入 X-User-Id |
| 需登录接口（直连微服务） | `Authorization` + `X-User-Id` | **必须两个 Header 都带** |
| 内部回调（支付成功等） | X-User-Id | 无需 Token |

### 白名单（网关放行，无需 Token）

```
/api/user/auth/captcha, /register, /login, /refresh, /logout
/api/user/*/info
/api/note/detail/**, /api/note/user/**
/api/comment/list/**, /children/**, /count/**, /page/**
/api/social/following/**, /follower/**, /like/count
/api/counter/get, /api/counter/batch-get
/api/home/**, /api/search/**
```

### curl 快速上手：获取 Token

```bash
BASE="http://21.214.97.212:19000/api"   # 走网关

# 1. 验证码
curl -s $BASE/user/auth/captcha
# → {"code":200,"data":{"captchaKey":"xxx","captchaImage":"..."}}

# 2. 从 Redis 读验证码（绕过图片 OCR）
python3 -c "
import urllib.request,json,socket
r=json.loads(urllib.request.urlopen('http://21.214.97.212:19001/api/user/auth/captcha').read())
key=r['data']['captchaKey']
s=socket.socket();s.settimeout(5);s.connect(('21.91.124.110',16379))
s.send(b'AUTH Xhs@2026#Redis\r\n');s.recv(1024)
s.send(f'GET myxhs:user:captcha:{key}\r\n'.encode())
code=s.recv(1024).decode().strip().split('\r\n')[-1].strip('\"')
print(f'KEY={key}\nCODE={code}')
s.close()
"

# 3. 注册
curl -s -X POST $BASE/user/auth/register -H 'Content-Type: application/json' \
  -d '{"username":"test","password":"Test@2026","captchaKey":"$KEY","captchaCode":"$CODE"}'

# 4. 登录（获取 Token）
LOGIN=$(curl -s -X POST $BASE/user/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"test","password":"Test@2026","captchaKey":"$KEY2","captchaCode":"$CODE2"}')
TOKEN=$(echo $LOGIN | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['accessToken'])")
UID=$(echo $TOKEN | cut -d. -f2 | base64 -d 2>/dev/null | python3 -c "import json,sys;print(json.load(sys.stdin)['sub'])")
echo "Token: ${TOKEN:0:20}...  UID: $UID"

# 5. 后续请求
curl -s $BASE/user/me -H "Authorization: Bearer $TOKEN"
```

---

## 二、服务类型总览

| 服务 | 端口 | 类型 | 说明 |
|------|:---:|:---:|------|
| my-xhs-gateway | 19000 | 🚪 网关 | 统一入口，7 层过滤链，路由转发 |
| my-xhs-user | 19001 | 👤 公开 | 注册/登录/用户信息/地址 |
| my-xhs-content | 19002 | 📝 公开 | 笔记发布/编辑/详情，评论（DFA 过滤） |
| my-xhs-analytics | 19003 | ❤️ 公开 | 点赞/收藏/关注社交互动 |
| my-xhs-counter | 19004 | 📊 内部 | 计数器（点赞数/粉丝数等），由 Feign/MQ 调用 |
| my-xhs-product | 19006 | 🛒 公开 | 商品/SPU/SKU/分类树 |
| my-xhs-cart | 19008 | 🛍️ 需登录 | 购物车 CRUD |
| my-xhs-inventory | 19009 | 📦 内部 | 库存预扣/释放/确认，由订单服务 Feign 调用 |
| my-xhs-coupon | 19010 | 🎫 公开+内部 | 领券/列表（公开），用券/退券（订单 Feign 内部调用） |
| my-xhs-order | 19011 | 📋 需登录 | 下单/支付/取消/退款回调 |
| my-xhs-payment | 19012 | 💳 需登录 | Mock 支付/退款（支付宝/微信 Mock） |
| my-xhs-notification | 19013 | 🔔 需登录 | 通知列表/已读/SSE 实时推送 |
| my-xhs-im | 19014 | 💬 混合 | REST 管理 + **WebSocket** 实时私信 |
| my-xhs-home | 19015 | 🏠 公开 | BFF 聚合（Feed 流/笔记详情/用户主页） |
| my-xhs-search | 19016 | 🔍 公开 | 搜索/热搜/推荐 Feed/相似笔记 |

> **内部服务**（不直接暴露给前端）：counter、inventory、coupon 的用券/退券端点 — 通过 Feign 由 order 服务调用
> **混合服务**：IM 的实时消息走 WebSocket，管理接口走 REST

---
## 三、API 列表

> 🔓 公开  🔒 需 X-User-Id  🔑 内部回调（非用户调用）

---

### 2.1 my-xhs-user — 用户服务 :19001

#### `/api/user/auth` — 认证

| 方法 | 路径 | 鉴权 | 请求体/参数 |
|------|------|:---:|------|
| GET | `/captcha` | 🔓 | — |
| POST | `/register` | 🔓 | `RegisterRequest{username,password,captchaKey,captchaCode}` |
| POST | `/login` | 🔓 | `LoginRequest{username,password,captchaKey,captchaCode}` |
| POST | `/refresh` | 🔓 | `?refreshToken` |
| POST | `/logout` | 🔓 | `?refreshToken`, Header `Authorization`(可选) |

#### `/api/user` — 用户信息

| 方法 | 路径 | 鉴权 | 请求体/参数 |
|------|------|:---:|------|
| GET | `/me` | 🔒 | — |
| GET | `/{userId}/info` | 🔓 | PathVariable |
| PUT | `/me` | 🔒 | `UpdateUserRequest` |
| PUT | `/me/password` | 🔒 | `ChangePasswordRequest{oldPassword,newPassword}` |

#### `/api/user/address` — 收货地址

| 方法 | 路径 | 鉴权 | 请求体/参数 |
|------|------|:---:|------|
| GET | `/list` | 🔒 | — |
| GET | `/default` | 🔒 | — |
| GET | `/{id}` | 🔒 | PathVariable |
| POST | 根路径 | 🔒 | `AddressCreateRequest` |
| PUT | `/{id}` | 🔒 | PathVariable + `AddressUpdateRequest` |
| DELETE | `/{id}` | 🔒 | PathVariable |
| PUT | `/{id}/default` | 🔒 | PathVariable |

---

### 2.2 my-xhs-content — 内容服务 :19002

#### `/api/note` — 笔记

| 方法 | 路径 | 鉴权 | 请求体/参数 |
|------|------|:---:|------|
| POST | `/publish` | 🔒 | `NotePublishRequest{title,content,images,videoUrl,coverUrl,topicIds,tags,noteType}` |
| POST | `/draft` | 🔒 | `NotePublishRequest`（字段同发布） |
| PUT | `/{id}` | 🔒 | PathVariable + `NoteUpdateRequest` |
| DELETE | `/{id}` | 🔒 | PathVariable |
| GET | `/detail/{id}` | 🔓 | PathVariable |
| GET | `/user/{userId}` | 🔓 | PathVariable + `?pageNum&pageSize` |
| GET | `/my` | 🔒 | `?status&pageNum&pageSize` |

#### `/api/comment` — 评论

| 方法 | 路径 | 鉴权 | 请求体/参数 |
|------|------|:---:|------|
| POST | 根路径 | 🔒 | `CommentCreateRequest{noteId,parentId,replyToId,content}` |
| DELETE | `/{id}` | 🔒 | PathVariable |
| GET | `/list/{noteId}` | 🔓 | PathVariable + `?lastId&pageSize`（游标分页） |
| GET | `/children/{parentId}` | 🔓 | PathVariable + `?lastId&pageSize`（游标分页） |
| GET | `/count/{noteId}` | 🔓 | PathVariable |
| GET | `/page/{noteId}` | 🔓 | PathVariable + `?pageNum&pageSize`（传统分页） |

---

### 2.3 my-xhs-product — 商品服务 :19006

| 方法 | 路径 | 鉴权 | 请求体/参数 |
|------|------|:---:|------|
| POST | `/api/product/spu` | 🔒 | 创建 SPU |
| PUT | `/api/product/spu/{spuId}` | 🔒 | PathVariable + 更新体 |
| GET | `/api/product/spu/{spuId}` | 🔓 | PathVariable |
| GET | `/api/product/spu/list` | 🔓 | `?categoryId&pageNum&pageSize` |
| PUT | `/api/product/spu/{spuId}/status` | 🔒 | PathVariable + `{status}` |
| POST | `/api/product/sku` | 🔒 | 创建 SKU |
| GET | `/api/product/sku/{skuId}` | 🔓 | PathVariable |
| GET | `/api/product/sku/list/{spuId}` | 🔓 | PathVariable |
| GET | `/api/product/sku/batch` | 🔑 | `?skuIds=1,2,3` 批量查询（购物车用） |
| GET | `/api/product/category/tree` | 🔓 | — |

---

### 2.4 my-xhs-cart — 购物车 :19008

| 方法 | 路径 | 鉴权 | 请求体/参数 |
|------|------|:---:|------|
| POST | `/api/cart/add` | 🔒 | `{skuId,quantity}` |
| PUT | `/api/cart/quantity` | 🔒 | `{skuId,quantity}` |
| DELETE | `/api/cart/{skuId}` | 🔒 | PathVariable |
| PUT | `/api/cart/check` | 🔒 | `{skuId,checked}` |
| PUT | `/api/cart/check-all` | 🔒 | `{checked}` |
| GET | `/api/cart/list` | 🔒 | — |
| POST | `/api/cart/merge` | 🔒 | 合并游客购物车 |
| GET | `/api/cart/count` | 🔒 | — |

---

### 2.5 my-xhs-order — 订单服务 :19011

| 方法 | 路径 | 鉴权 | 请求体/参数 |
|------|------|:---:|------|
| POST | `/api/order/create` | 🔒 | `OrderCreateRequest{skuItems,addressId,remark,couponId}` |
| GET | `/api/order/{orderId}` | 🔒 | PathVariable |
| GET | `/api/order/list` | 🔒 | `?status&pageNum&pageSize` |
| GET | `/api/order/by-order-no/{orderNo}` | 🔒 | PathVariable |
| POST | `/api/order/cancel` | 🔒 | `{orderId}` |
| POST | `/api/order/confirm` | 🔒 | `{orderId}` |
| POST | `/api/order/pay/create` | 🔒 | `{orderId}` |
| GET | `/api/order/pay/status/{orderId}` | 🔒 | PathVariable |
| POST | `/api/order/pay-success` | 🔑 | 支付成功回调 |
| POST | `/api/order/pay-fail` | 🔑 | 支付失败回调 |
| POST | `/api/order/refund-success` | 🔑 | 退款成功回调 |
| POST | `/api/order/refund-fail` | 🔑 | 退款失败回调 |
| GET | `/api/order/pay-amount` | 🔑 | `?orderId` |

---

### 2.6 my-xhs-payment — 支付服务 :19012

| 方法 | 路径 | 鉴权 | 请求体/参数 |
|------|------|:---:|------|
| POST | `/api/payment/pay` | 🔒 | `{orderId,payType}` (1=支付宝Mock/2=微信Mock/99=同步Mock) |
| POST | `/api/payment/callback/{payType}` | 🔑 | 第三方支付回调 |
| POST | `/api/payment/refund` | 🔒 | `{orderId,reason}` |
| POST | `/api/payment/refund-callback/{payType}` | 🔑 | 第三方退款回调 |
| GET | `/api/payment/status/{orderId}` | 🔒 | PathVariable |

---

### 2.7 my-xhs-inventory — 库存服务 :19009

| 方法 | 路径 | 鉴权 | 请求体/参数 |
|------|------|:---:|------|
| POST | `/api/inventory/init` | 🔑 | `{skuId,stock}` 初始化库存 |
| POST | `/api/inventory/preDeduct` | 🔑 | `{skuId,quantity,orderId}` Lua 原子预扣 |
| POST | `/api/inventory/confirm` | 🔑 | `{orderId,skuId}` 确认扣减 |
| POST | `/api/inventory/release` | 🔑 | `{orderId,skuId}` 释放回库存 |
| GET | `/api/inventory/stock/{skuId}` | 🔓 | PathVariable |

---

### 2.8 my-xhs-coupon — 优惠券服务 :19010

| 方法 | 路径 | 鉴权 | 请求体/参数 |
|------|------|:---:|------|
| POST | `/api/coupon/template` | 🔒 | 创建优惠券模板 |
| PUT | `/api/coupon/template/{id}/status` | 🔒 | `{status}` 启用/禁用 |
| GET | `/api/coupon/template/{id}` | 🔓 | PathVariable |
| POST | `/api/coupon/claim` | 🔒 | `{couponId}` Lua 防超发领取 |
| GET | `/api/coupon/user/list` | 🔒 | `?status` |
| GET | `/api/coupon/user/available` | 🔒 | — |
| POST | `/api/coupon/use` | 🔑 | `{userCouponId,orderAmount}` 核销 |
| POST | `/api/coupon/return` | 🔑 | `{userCouponId}` 退还 |

---

### 2.9 my-xhs-analytics — 社交互动 :19003

#### 点赞 `/api/social`

| 方法 | 路径 | 鉴权 | 请求体/参数 |
|------|------|:---:|------|
| POST | `/like` | 🔒 | `LikeRequest{bizType,bizId}` bizType:1-笔记/2-评论 |
| POST | `/unlike` | 🔒 | `LikeRequest{bizType,bizId}` |
| GET | `/like/status` | 🔒 | `?bizType&bizId` |
| GET | `/like/batch-status` | 🔒 | `?bizType&bizIds=1,2,3` |
| GET | `/like/count` | 🔓 | `?bizType&bizId` |

#### 收藏

| 方法 | 路径 | 鉴权 | 请求体/参数 |
|------|------|:---:|------|
| POST | `/favorite` | 🔒 | `FavoriteRequest{noteId}` |
| POST | `/unfavorite` | 🔒 | `FavoriteRequest{noteId}` |
| GET | `/favorite/status` | 🔒 | `?noteId` |
| GET | `/favorite/list` | 🔒 | `?pageNum&pageSize` |

#### 关注 `/api/social`

| 方法 | 路径 | 鉴权 | 请求体/参数 |
|------|------|:---:|------|
| POST | `/follow` | 🔒 | `{userId}` 关注用户 |
| POST | `/unfollow` | 🔒 | `{userId}` 取消关注 |
| GET | `/following/{userId}` | 🔓 | PathVariable + `?pageNum&pageSize` 关注列表 |
| GET | `/follower/{userId}` | 🔓 | PathVariable + `?pageNum&pageSize` 粉丝列表 |
| GET | `/relation/{targetUserId}` | 🔒 | PathVariable 检查关注关系 |

---

### 2.10 my-xhs-search — 搜索服务 :19016

#### `/api/search` — 搜索

| 方法 | 路径 | 鉴权 | 请求体/参数 |
|------|------|:---:|------|
| GET | `/note` | 🔓 | `?keyword&sort&size&searchAfter` |
| GET | `/product` | 🔓 | `?keyword&categoryId&minPrice&maxPrice&sort` |
| GET | `/suggest` | 🔓 | `?prefix` (ES Completion Suggester) |
| GET | `/history` | 🔒 | — |
| DELETE | `/history` | 🔒 | — |
| DELETE | `/history/{keyword}` | 🔒 | PathVariable |
| POST | `/index/rebuild` | 🔒 | 全量索引重建 |
| GET | `/hot` | 🔓 | 热搜榜 Top50 |
| POST | `/hot/record` | 🔑 | 记录搜索词 |
| PUT | `/hot/pin` | 🔒 | `{keyword}` 置顶 |
| DELETE | `/hot/pin` | 🔒 | `{keyword}` 取消置顶 |
| PUT | `/hot/block` | 🔒 | `{keyword}` 屏蔽 |
| DELETE | `/hot/block` | 🔒 | `{keyword}` 取消屏蔽 |
| GET | `/hot/snapshot` | 🔓 | `?date` 历史热搜快照 |

#### `/api/recommend` — 推荐

| 方法 | 路径 | 鉴权 | 请求体/参数 |
|------|------|:---:|------|
| GET | `/feed` | 🔒 | `?size` |
| GET | `/similar/{noteId}` | 🔓 | PathVariable + `?size` |
| POST | `/behavior` | 🔒 | `BehaviorRequest{noteId,behaviorType,duration}` |
| POST | `/compute` | 🔒 | 手动触发离线计算 |

---

### 2.11 my-xhs-home — 首页聚合 :19015

| 方法 | 路径 | 鉴权 | 请求体/参数 |
|------|------|:---:|------|
| GET | `/api/home/feed` | 🔒 | `?size&lastScore` |
| GET | `/api/home/note/{noteId}` | 🔓 | PathVariable |
| GET | `/api/home/product/{spuId}` | 🔓 | PathVariable |
| GET | `/api/home/user/{targetUserId}` | 🔓 | PathVariable |
| GET | `/api/home/cart` | 🔒 | 购物车聚合（含优惠券+库存） |

---

### 2.12 my-xhs-counter — 计数服务 :19004

| 方法 | 路径 | 鉴权 | 请求体/参数 |
|------|------|:---:|------|
| POST | `/api/counter/increment` | 🔑 | `{targetType,targetId,countType,delta}` |
| POST | `/api/counter/decrement` | 🔑 | `{targetType,targetId,countType,delta}` |
| GET | `/api/counter/get` | 🔓 | `?targetType&targetId&countType` |
| POST | `/api/counter/batch-get` | 🔓 | `{targetType,targetIds[],countType}` |
| POST | `/api/counter/reconcile` | 🔑 | 对账修复 |

---

### 2.13 my-xhs-notification — 通知服务 :19013

| 方法 | 路径 | 鉴权 | 请求体/参数 |
|------|------|:---:|------|
| POST | `/api/notification/sse/ticket` | 🔒 | 获取 SSE 连接 ticket |
| GET | `/api/notification/sse` | 🔒 | SSE 实时推送（`Accept:text/event-stream`） |
| GET | `/api/notification/list` | 🔒 | `?pageNum&pageSize` |
| GET | `/api/notification/unread-count` | 🔒 | — |
| POST | `/api/notification/read/{id}` | 🔒 | PathVariable |
| POST | `/api/notification/read-by-type/{type}` | 🔒 | PathVariable |
| POST | `/api/notification/read-all` | 🔒 | — |
| GET | `/api/notification/sse/online-count` | 🔒 | SSE 在线用户数 |

---

### 2.14 my-xhs-im — 即时通讯 :19014

> **核心通信**: WebSocket `ws://host:19014/api/im/ws?ticket=xxx`（两步法鉴权）
> REST 接口仅用于：签发 ticket、查询会话列表/历史消息/未读数

| 方法 | 路径 | 鉴权 | 请求体/参数 |
|------|------|:---:|------|
| POST | `/api/im/ws/ticket` | 🔒 | 获取 WebSocket 连接 ticket（5分钟有效） |
| GET | `/api/im/conversations` | 🔒 | `?page&size` 会话列表 |
| GET | `/api/im/messages/{peerId}` | 🔒 | PathVariable + `?page&size` 聊天记录 |
| POST | `/api/im/read/{peerId}` | 🔒 | PathVariable 标记已读 |
| GET | `/api/im/unread-count` | 🔒 | 未读消息总数 |
| GET | `/api/im/online-count` | 🔒 | 在线用户数 |

---

## 四、通用约定

### 响应格式

```json
{"code":200, "message":"操作成功", "data":{...}, "timestamp":1780132253913, "success":true}
```

### 分页

| 类型 | 参数 | 默认值 |
|------|------|--------|
| 传统 | `pageNum` `pageSize` | 1, 10 |
| 游标（评论） | `lastId` `pageSize` | 无, 10 |
| Feed 游标 | `lastScore` `size` | 无, 10 |

### 错误码

| code | 含义 |
|------|------|
| 200 | 成功 |
| 40002 | 参数校验失败 |
| 401 | 未登录 |
| 403 | 无权限 |
| 500 | 服务器错误 |

---

## 五、全链路测试 curl 脚本

> 以下脚本覆盖所有 14 个服务的**公开端点**，可直接复制执行。
> 修改日期：2026-06-02

```bash
#!/bin/bash
# my-xhs 全链路冒烟测试（覆盖所有 14 个服务模块）
API="http://21.214.97.212"  # 直连模式
H="" X="" TOKEN=""

echo "=== 1. my-xhs-user (19001) 用户认证 ==="
# 验证码
CAPTCHA=$(curl -s $API:19001/api/user/auth/captcha)
echo "  验证码: $(echo $CAPTCHA | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['captchaKey'])")"

# 注册
curl -s -X POST $API:19001/api/user/auth/register -H 'Content-Type: application/json' \
  -d '{"username":"smoke_test","password":"Test@2026","captchaKey":"","captchaCode":""}' | python3 -c "import json,sys;d=json.load(sys.stdin);print('  注册:',d['message'])"

# 登录
LOGIN=$(curl -s -X POST $API:19001/api/user/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"smoke_test","password":"Test@2026","captchaKey":"","captchaCode":""}')
TOKEN=$(echo $LOGIN | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null)
UID=$(echo $TOKEN | cut -d. -f2 | base64 -d 2>/dev/null | python3 -c "import json,sys;print(json.load(sys.stdin)['sub'])" 2>/dev/null)
if [ -n "$TOKEN" ]; then
  H="-H 'Authorization: Bearer $TOKEN'"
  X="-H 'X-User-Id: $UID'"
  echo "  登录: OK (UID=$UID)"; else echo "  登录: 跳过（可能已注册）"
fi

echo ""
echo "=== 2. my-xhs-content (19002) 笔记 ==="
NOTE=$(curl -s -X POST $API:19002/api/note/publish $H $X -H 'Content-Type: application/json' \
  -d '{"title":"冒烟测试笔记","content":"这是一条自动化测试","noteType":0}')
NOTE_ID=$(echo $NOTE | python3 -c "import json,sys;print(json.load(sys.stdin).get('data',''))" 2>/dev/null)
echo "  发布: NOTE_ID=$NOTE_ID"

curl -s $API:19002/api/note/detail/$NOTE_ID | python3 -c "import json,sys;d=json.load(sys.stdin);print('  详情:',d.get('code'),d.get('data',{}).get('title',''))"

echo ""
echo "=== 3. my-xhs-analytics (19003) 社交 ==="
curl -s -X POST $API:19003/api/social/like $H $X -H 'Content-Type: application/json' \
  -d "{\"bizType\":1,\"bizId\":$NOTE_ID}" | python3 -c "import json,sys;print('  点赞:',json.load(sys.stdin)['code'])"
curl -s "$API:19003/api/social/like/count?bizType=1&bizId=$NOTE_ID" | python3 -c "import json,sys;print('  计数:',json.load(sys.stdin)['data'])"
curl -s -X POST $API:19003/api/social/follow $H $X -H 'Content-Type: application/json' \
  -d "{\"userId\":$UID}" | python3 -c "import json,sys;print('  关注:',json.load(sys.stdin)['code'])"

echo ""
echo "=== 4. my-xhs-counter (19004) 计数 ==="
curl -s -X POST $API:19004/api/counter/increment $X -H 'Content-Type: application/json' \
  -d '{"targetType":1,"targetId":1,"countType":1}' | python3 -c "import json,sys;print('  增量:',json.load(sys.stdin)['code'])"

echo ""
echo "=== 5. my-xhs-product (19006) 商品 ==="
curl -s $API:19006/api/product/category/tree | python3 -c "import json,sys;print('  分类:',len(json.load(sys.stdin).get('data',[])),'个')"
curl -s "$API:19006/api/product/spu/list?pageNum=1&pageSize=3" | python3 -c "import json,sys;print('  商品:',json.load(sys.stdin)['code'])"

echo ""
echo "=== 6. my-xhs-cart (19008) 购物车 ==="
curl -s -X POST $API:19008/api/cart/add $H $X -H 'Content-Type: application/json' \
  -d '{"skuId":1,"quantity":2}' | python3 -c "import json,sys;print('  加购:',json.load(sys.stdin)['code'])"
curl -s "$API:19008/api/cart/list" $H $X | python3 -c "import json,sys;d=json.load(sys.stdin);print('  列表:',d['code'],len(d.get('data',{}).get('items',[])),'件')"

echo ""
echo "=== 7. my-xhs-coupon (19010) 优惠券 ==="
TMPL=$(curl -s -X POST $API:19010/api/coupon/template $H $X -H 'Content-Type: application/json' \
  -d '{"name":"测试券","type":1,"discountValue":10,"minAmount":50,"totalCount":100,"perUserLimit":3,"validDays":30}')
TMPL_ID=$(echo $TMPL | python3 -c "import json,sys;d=json.load(sys.stdin);print(d['data']['id'] if 'data' in d else '')" 2>/dev/null)
echo "  创建模板: TID=$TMPL_ID"
curl -s -X POST $API:19010/api/coupon/claim $H $X -H 'Content-Type: application/json' \
  -d "{\"couponId\":$TMPL_ID}" | python3 -c "import json,sys;print('  领取:',json.load(sys.stdin)['code'])"
curl -s "$API:19010/api/coupon/user/available" $H $X | python3 -c "import json,sys;print('  可用:',len(json.load(sys.stdin).get('data',[])),'张')"

echo ""
echo "=== 8. my-xhs-order (19011) 下单 + 支付 ==="
ORDER=$(curl -s -X POST $API:19011/api/order/create $H $X -H 'Content-Type: application/json' \
  -d '{"skuItems":[{"skuId":1,"quantity":1}],"addressId":1,"remark":"冒烟测试"}')
ORDER_ID=$(echo $ORDER | python3 -c "import json,sys;d=json.load(sys.stdin);print(d['data']['orderId'] if 'data' in d else '')" 2>/dev/null)
echo "  创建: ORDER_ID=$ORDER_ID"
curl -s -X POST $API:19011/api/order/pay/create $H $X -H 'Content-Type: application/json' \
  -d "{\"orderId\":$ORDER_ID}" | python3 -c "import json,sys;print('  支付:',json.load(sys.stdin)['code'])"
curl -s "$API:19011/api/order/$ORDER_ID" $H $X | python3 -c "import json,sys;d=json.load(sys.stdin);print('  详情:',d['code'],'status:',d.get('data',{}).get('status',''))"

echo ""
echo "=== 9. my-xhs-payment (19012) 支付独立服务 ==="
curl -s "$API:19012/api/payment/status/$ORDER_ID" $H $X | python3 -c "import json,sys;print('  查询:',json.load(sys.stdin)['code'])"

echo ""
echo "=== 10. my-xhs-search (19016) 搜索 ==="
curl -s "$API:19016/api/search/note?keyword=冒烟测试" | python3 -c "import json,sys;d=json.load(sys.stdin);print('  搜索:',d['code'],'条:',len(d.get('data',{}).get('records',[])))"
curl -s "$API:19016/api/search/suggest?prefix=测" | python3 -c "import json,sys;print('  建议:',json.load(sys.stdin)['code'])"
curl -s $API:19016/api/search/hot | python3 -c "import json,sys;print('  热搜:',len(json.load(sys.stdin).get('data',[])),'条')"

echo ""
echo "=== 11. my-xhs-home (19015) BFF聚合 ==="
curl -s "$API:19015/api/home/feed?size=3" $H $X | python3 -c "import json,sys;d=json.load(sys.stdin);print('  Feed:',d['code'],len(d.get('data',[])),'条')"

echo ""
echo "=== 12. my-xhs-notification (19013) 通知 ==="
curl -s "$API:19013/api/notification/unread-count" $H $X | python3 -c "import json,sys;print('  未读:',json.load(sys.stdin)['code'])"

echo ""
echo "=== 13. my-xhs-im (19014) IM ==="
curl -s -X POST $API:19014/api/im/ws/ticket $H $X | python3 -c "import json,sys;d=json.load(sys.stdin);print('  WS票据:',d['code'],'ticket' if d.get('data',{}).get('ticket') else '')"

echo ""
echo "=== 14. my-xhs-inventory (19009) 库存 ==="
curl -s "$API:19009/api/inventory/stock/1" | python3 -c "import json,sys;print('  库存:',json.load(sys.stdin)['code'])"

echo ""
echo "=== 15. my-xhs-gateway (19000) 网关 ==="
curl -s $API:19000/actuator/health | python3 -c "import json,sys;print('  网关:',json.load(sys.stdin)['status'])"

echo ""
echo "=== 全链路冒烟测试完成 ==="
```
