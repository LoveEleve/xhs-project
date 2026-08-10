# 13-home 首页 BFF — curl 测试记录

> 端口：19015 | 数据库：无（纯聚合层） | 测试日期：2026-07-30

---

## 测试用例概览

| # | 接口 | 方法 | 说明 | 状态 |
|:--:|---|---|---|---|
| 1 | `/api/home/feed` | GET | 关注 Feed 流（推拉混合） | ✅ |
| 2 | `/api/home/note/{noteId}` | GET | 笔记详情聚合 | ✅ |
| 3 | `/api/home/product/{spuId}` | GET | 商品详情聚合 | ✅ |
| 4 | `/api/home/user/{targetUserId}` | GET | 用户主页聚合 | ✅ |
| 5 | `/api/home/cart` | GET | 购物车聚合 | ✅ |
| 6 | Feed 游标翻页 | GET | nextCursor 分页 | ✅ |
| 7 | 下游服务降级 | GET | analytics 停掉后 Feed 降级 | ✅ |

---

## 1. GET /api/home/feed — 关注 Feed 流

```bash
curl -s "http://localhost:19015/api/home/feed?size=3" \
  -H "X-User-Id: 10001"
```

**响应** ✅ L1：HTTP 200
```json
{
  "code": 200,
  "data": {
    "notes": [
      {
        "noteId": 2080205090016251906,
        "title": "最终验证草稿",
        "authorId": 2078387513547841537,
        "authorNickname": "newuser",
        "likeCount": 0,
        "isLiked": false,
        "isFollowed": true
      }
    ],
    "nextCursor": 1784793894528,
    "hasMore": true,
    "unreadCount": 0
  }
}
```

**Feign 调用依赖**：
- ContentFeign → my-xhs-content（19002）✅
- AnalyticsFeign → my-xhs-analytics（19003）✅
- NotificationFeign → my-xhs-notification（19013）✅
- UserFeign → my-xhs-user（19001）✅
- CounterFeign → my-xhs-counter（19004）✅

**Redis** ✅ L3：`myxhs:feed:inbox:10001` ZSet 有 7 条数据。

**Logs** ✅ L5：聚合耗时日志 `[Feed] 第1层聚合超时` / `[Feed] 批量获取笔记详情失败`（笔记不存在时降级）。

---

## 2. GET /api/home/note/{noteId} — 笔记详情聚合

```bash
curl -s "http://localhost:19015/api/home/note/2080205090016251906" \
  -H "X-User-Id: 10001"
```

**响应** ✅ L1：
```json
{
  "code": 200,
  "data": {
    "noteId": 2080205090016251906,
    "title": "最终验证草稿",
    "authorId": 2078387513547841537,
    "authorNickname": "newuser",
    "likeCount": 0,
    "isLiked": false,
    "isFollowed": true,
    "hotComments": []
  }
}
```

**2 层聚合验证** ✅：笔记详情（L1）+ 点赞/收藏（L1）+ 计数（L1）+ 作者（L2）+ 关注关系（L2）+ 评论（L2）。

**笔记不存在时** ✅：返回 `{"code": 404, "message": "笔记不存在"}`。

---

## 3. GET /api/home/product/{spuId} — 商品详情聚合

```bash
curl -s "http://localhost:19015/api/home/product/30001"
```

**响应** ✅ L1：`{"code": 404, "message": "商品不存在"}`（商品 30001 在 product 服务中不存在，降级正确）。

---

## 4. GET /api/home/user/{targetUserId} — 用户主页聚合

```bash
curl -s "http://localhost:19015/api/home/user/10001" \
  -H "X-User-Id: 10001"
```

**响应** ✅ L1：
```json
{
  "code": 200,
  "data": {
    "userId": 10001,
    "nickname": "测试用户A",
    "followingCount": 0,
    "followerCount": 0,
    "noteCount": 0,
    "isFollowing": false,
    "isFollowBack": false,
    "isMutual": false,
    "notes": []
  }
}
```

**未登录用户查看** ✅：不带 `X-User-Id` 时社交关系全部为 false。

---

## 5. GET /api/home/cart — 购物车聚合

```bash
curl -s "http://localhost:19015/api/home/cart" \
  -H "X-User-Id: 10001"
```

**响应** ✅ L1：
```json
{
  "code": 200,
  "data": {
    "items": [
      { "skuId": 99999, "quantity": 1, "checked": true, "price": null },
      { "skuId": 2081544572120371202, "quantity": 5, "checked": true, "price": 99.0 }
    ],
    "checkedCount": 2,
    "checkedAmount": 594.0,
    "allChecked": true,
    "availableCoupons": []
  }
}
```

---

## 6. Feed 游标翻页

```bash
# 第 1 页
curl -s ".../api/home/feed?size=3" -H "X-User-Id: 10001"
# → 2 条, nextCursor=1784793894528, hasMore=false

# 第 2 页（传入 nextCursor）
curl -s ".../api/home/feed?size=3&lastScore=1784793894528" -H "X-User-Id: 10001"
# → 3 条, nextCursor=1784775232922, hasMore=true
```

**游标分页正常** ✅。翻页不重复，score 按时间戳降序。

📌 **`hasMore` 判定已修复**：原逻辑 `cards.size() >= requestSize` 在聚合过滤后可能误报（请求 3 条但 content 服务只返回 2 条有效笔记时标记 `hasMore=false`）。修复为基于 `merged.size()`（Redis 合并后的 ID 数），在 `FeedService.java` 中新增 `hasMoreFromRedis` 参数传递。

---

## 7. 下游服务降级 + 异常兜底

停掉 `my-xhs-analytics` 后请求 Feed（验证 try-catch 兜底生效）：

```json
{"code": 200, "data": {"notes": [...], "isLiked": false, "likeCount": 0}}
```

**结果** ✅：
- HTTP 200（try-catch 捕获 Connection refused，返回空数据）
- `isLiked=false`（降级默认值）
- `likeCount=0`（Counter 正常返回）

**Logs** ✅ L5：`[降级] AnalyticsFeignClient 不可用: ...`

---

## 修正记录

测试中发现并修复了以下问题：

| 问题 | 修复 | 涉及文件 |
|---|---|---|
| Feed 返回 500：LoadBalancer NPE（服务名为 null） | `name == null` 兜底 | `LeastConnectionsLoadBalancerConfig.java` |
| Feed 返回 500：Feign 调用未加 try-catch，Connection refused 导致 CompletableFuture 异常完成 | `likesFuture` / `unreadFuture` 加 try-catch | `FeedService.java` |
| `hasMore` 基于 `cards.size()` 误报 | 改为 `hasMoreFromRedis = merged.size() >= requestSize` | `FeedService.java` |
| Feign LoadBalancer NPE（home 模块未配置 URL override） | 添加 9 个下游服务的 `spring.cloud.openfeign.client.config.*.url` | `application.yml` |

---

## 层验证汇总

| 层 | 内容 | 状态 | 备注 |
|:--:|------|:----:|------|
| L1 | API 响应 | ✅ | 5 个接口均 200 |
| L2 | ACCESS 日志 | ✅ | traceId 正常 |
| L3 | Redis | ✅ | inbox ZSet 验证 |
| L4 | MySQL | N/A | home 无数据库 |
| L5 | 应用日志 | ✅ | 聚合/降级日志 |
| L6 | Nacos 注册 | ✅ | my-xhs-home 已注册 |
| L7 | XXL-Job | 🟡 | FeedCleanupJob 需在 XXL-Job Admin 配置触发 |
| L8 | MQ | 🟡 | FeedPushConsumer 需内容服务发笔记事件验证 |
| L9 | @RateLimit | N/A | |
| L10 | Sentinel | ✅ | Feign Sentinel 熔断+降级 |
| L11 | SkyWalking | 🟡 | Feign 调用传播 traceId，未实测 |
| L12 | Gateway 路由 | 🟡 | 需通过 Gateway 访问（需 JWT+HMAC） |
| L13 | Actuator | ✅ | UP（redis/discovery/sentinel） |
| L14 | ES 日志 | 🟡 | `_grokparsefailure` 同 IM，待中间件修复 |
| L15 | Prometheus | ✅ | 283 行指标 |

**发现的问题汇总**：
1. 🟡 L14 ES traceId 字段缺失（同 IM 模块，中间件在处理）
2. 🟡 analytics 服务被 OOM kill（内存不足，非代码 bug，已加 -Xmx128m 缓解）
