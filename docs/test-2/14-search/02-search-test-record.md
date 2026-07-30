# 14-search 搜索服务 — curl 测试记录

> 端口：19016 | 数据库：ES 8.12 + my_xhs_search | 测试日期：2026-07-30

---

## 测试用例概览

| # | 接口 | 方法 | 说明 | 状态 |
|:--:|---|---|---|---|
| 1 | `/api/search/note` | GET | 笔记搜索 | ✅ |
| 2 | `/api/search/product` | GET | 商品搜索 | ✅ |
| 3 | `/api/search/suggest` | GET | 搜索建议 | ✅ |
| 4 | `/api/search/history` | GET/DELETE | 搜索历史 | ✅ |
| 5 | `/api/search/hot` | GET | 热搜 Top 50 | ✅ |
| 6 | `/api/search/hot/record` | POST | 热搜上报 | ✅ |
| 7 | `/api/search/hot/pin` | PUT | 置顶 | ✅ |
| 8 | `/api/search/hot/block` | PUT | 屏蔽 | ✅ |
| 9 | `/api/recommend/feed` | GET | 推荐 Feed | ✅ |
| 10 | `/api/recommend/similar/{noteId}` | GET | 相似笔记 | ✅ |
| 11 | `/api/recommend/behavior` | POST | 行为上报 | ✅ |

---

## 1. GET /api/search/note — 笔记搜索

```bash
# 注意：中文参数需 URL encode
curl -s "http://localhost:19016/api/search/note?keyword=%E8%8D%89%E7%A8%BF&size=3" \
  -H "X-User-Id: 10001"
```

**响应** ✅ L1：
```json
{"code":200,"data":{"items":[],"total":0,"searchAfter":null,"hasMore":false,"took":9}}
```

total=0 是因为 ES 中笔记数据 `status=2`（草稿），搜索过滤条件 `filter: status=1`。逻辑正确。

**ACCESS 日志** ✅ L2：traceId 正常生成。

**ES 索引** ✅：`note_index` 3 shards, 1 replica, IK 分词，7 条文档。

---

## 2. GET /api/search/product — 商品搜索

```bash
curl -s "http://localhost:19016/api/search/product?keyword=%E5%95%86%E5%93%81&size=3"
```

**响应** ✅ L1：
```json
{
  "code": 200,
  "data": {
    "items": [{
      "spuId": 2081302094884671490,
      "name": "curl测试商品-已更新",
      "highlightName": "curl测试<em>商品</em>-已更新",
      "price": null,
      "sales": 0
    }],
    "total": 1,
    "searchAfter": null,
    "hasMore": false,
    "took": 69
  }
}
```

**高亮验证** ✅：`<em>商品</em>` 标签正确。

**ES** ✅：`product_index` 1 文档（status=1）。

---

## 3. GET /api/search/suggest — 搜索建议

```bash
curl -s "http://localhost:19016/api/search/suggest?prefix=%E6%B5%8B"
```

**响应** ✅ L1：`{"code":200,"data":[],"success":true}`

`suggest_index` 0 条文档，返回空数组。建议数据需通过索引同步写入。

---

## 4. GET/DELETE /api/search/history — 搜索历史

```bash
curl -s "http://localhost:19016/api/search/history" -H "X-User-Id: 10001"
# → ["草稿","Canal","test","测试"]

curl -s -X DELETE "http://localhost:19016/api/search/history/test" -H "X-User-Id: 10001"
# → 200

curl -s "http://localhost:19016/api/search/history" -H "X-User-Id: 10001"
# → ["草稿","Canal","测试"]
```

**Redis** ✅ L3：`LRANGE myxhs:search:history:10001 0 -1` 验证。

---

## 5. GET /api/search/hot — 热搜 Top 50

```bash
curl -s "http://localhost:19016/api/search/hot"
```

**响应** ✅ L1：
```json
{
  "code": 200,
  "data": [
    {"rank":1,"keyword":"test","score":1.72,"pinned":false,"tag":"爆"},
    {"rank":2,"keyword":"草稿","score":1.64,"pinned":false,"tag":"爆"},
    {"rank":3,"keyword":"Canal","score":0.82,"pinned":false,"tag":"热"}
  ]
}
```

**定时计算** ✅ L5：HotSearchService @Scheduled 5min 执行。

---

## 6. POST /api/search/hot/record — 热搜上报

```bash
curl -s -X POST "http://localhost:19016/api/search/hot/record?keyword=%E6%B5%8B%E8%AF%95%E7%83%AD%E6%90%9C" \
  -H "X-User-Id: 10001" -H "X-Forwarded-For: 10.0.0.1"
```

**Redis** ✅ L3：Lua 原子操作——blocked 检查 → IP 限速 → 用户去重 → 分钟桶 INCR。

---

## 7/8. 置顶/屏蔽

```bash
curl -s -X PUT "http://localhost:19016/api/search/hot/pin?keyword=%E6%B5%8B%E8%AF%95"
curl -s -X PUT "http://localhost:19016/api/search/hot/block?keyword=%E5%9E%83%E5%9C%BE"
```

**Redis** ✅ L3：`SADD myxhs:search:hot:pinned 测试` / `SADD myxhs:search:hot:blocked 垃圾`。

---

## 9. GET /api/recommend/feed — 推荐 Feed

```bash
curl -s "http://localhost:19016/api/recommend/feed" -H "X-User-Id: 10001"
```

**响应** ✅ L1：`{"code":200,"data":[],"success":true}`

0 条（无用户行为数据 / Item-CF 未计算 / 推荐池为空）。行为正常。

---

## 10. GET /api/recommend/similar/{noteId} — 相似笔记

```bash
curl -s "http://localhost:19016/api/recommend/similar/20001?size=5"
```

**响应** ✅ L1：`{"code":200,"data":[],"success":true}`

Item-CF 矩阵未计算，返回空。

---

## 11. POST /api/recommend/behavior — 行为上报

```bash
curl -s -X POST "http://localhost:19016/api/recommend/behavior" \
  -H "X-User-Id: 10001" \
  -H "Content-Type: application/json" \
  -d '{"noteId":20001,"behaviorType":2,"duration":5000}'
```

**响应** ✅ L1：`{"code":200,"success":true}`

**注意**：`behaviorType` 需传 **整数**（1=曝光 2=点击 3=点赞 4=收藏 5=评论 6=分享 7=停留），传字符串会返回 40002。

**MQ 链路** 🟡：→ `RECOMMEND_BEHAVIOR_TOPIC` → `BehaviorReportConsumer` → `INSERT t_user_behavior`。Consumer 需验证。

---

## 修正记录

| 问题 | 修复 | 涉及文件 |
|---|---|---|
| ES `_id` 排序 → `search_phase_execution_exception: all shards failed` | 三种排序策略移除 `_id ASC` tiebreaker | `NoteSearchService.java`, `ProductSearchService.java` |

ES 8.x 默认禁止 `_id` 字段排序（`indices.id_field_data.enabled=false`），需用其他字段（如 `noteId`/`spuId`）做 tiebreaker。

---

## 层验证汇总

| 层 | 内容 | 状态 | 备注 |
|:--:|------|:----:|------|
| L1 | API 响应 | ✅ | 全部 200 |
| L2 | ACCESS 日志 | ✅ | traceId 正常 |
| L3 | Redis | ✅ | 热搜/历史/防刷 |
| L4 | MySQL | 🟡 | 行为上报 t_user_behavior（需验证 MQ 链路） |
| L5 | 应用日志 | ✅ | 搜索失败/降级日志 |
| L6 | Nacos 注册 | ✅ | my-xhs-search 已注册 |
| L7 | XXL-Job | 🟡 | recommendItemCFJob/Feature/HotPool 需 Admin 配置 |
| L8 | MQ | 🟡 | 索引同步/BEHAVIOR_TOPIC 需验证 |
| L9 | @RateLimit | N/A | |
| L10 | Sentinel | ✅ | 已注册，无规则 |
| L11 | SkyWalking | 🟡 | ES 查询 traceId（需验证） |
| L12 | Gateway 路由 | 🟡 | 需通过 Gateway（需 JWT+HMAC） |
| L13 | Actuator | ✅ | UP（es/redis/discovery） |
| L14 | ES 日志 | 🟡 | `_grokparsefailure` 同其他模块，待中间件 |
| L15 | Prometheus | ✅ | 468 行指标 |

**发现的问题**：
1. 🟡 ES `_id` 排序 bug → **已修复**
2. 🟡 中文参数需 URL encode（curl 直传 400，Tomcat URIEncoding 未配 UTF-8）
3. 🟡 L14 ES traceId 缺失（同其他模块，中间件处理中）
