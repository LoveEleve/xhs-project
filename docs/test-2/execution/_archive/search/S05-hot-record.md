# S05 — 热搜记录 (POST /api/search/hot/record)

> 2026-08-08 | 阶段14-2 | search服务 | testuser: mytestuser

## ASCII 流转图

```
curl → Gateway:19000 (JWT→HMAC→路由)
       → my-xhs-search:19016 (/api/search/hot/record)
         → HotSearchService.recordSearchKeyword()
           → Lua Script (原子操作):
             ├── SISMEMBER blockedSet → 屏蔽词检查
             ├── INCR ipKey → IP频率限制
             ├── SET userKey → 用户频率限制
             └── HINCRBY bucketKey → 写入分钟桶
           → Redis Sentinel→Master:16379
             ├── search:window:{yyyyMMddHHmm} (T=2h)
             └── 定时任务每分钟→聚合→search:hot:realtime ZSet
```

## 业务逻辑

接收搜索词，通过 Lua 脚本原子执行：屏蔽词检查、IP/用户频率限制、分钟桶计数。定时任务每分钟从最近5个分钟桶聚合数据到 ZSet，应用衰减算法(V=1×e^(-λt)+old×0.5)计算热度。

## 七层验证

| 层 | 状态 | 验证内容 |
|------|:--:|------|
| HTTP | ✅ | 200 OK, X-Trace-Id captured |
| Redis | ✅ | realtime ZSet: "测试商品"(0.90) + "测试"(0.74); 418 keys in 16379 |
| MySQL | N/A | 只写Redis |
| MQ | N/A | 无MQ |
| SW | ✅ | traceId+access log confirmed |
| Prometheus | ⚠️ | 待确认 |
| Kibana | ✅ | traceId有日志 |

## 修复后最终验证 (2026-08-08 11:56)

> 所有代码修复部署完毕后，用新关键词"卫衣/美妆"做全链路验证。

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -X POST "http://localhost:19000/api/search/hot/record?keyword=%E5%8D%AB%E8%A1%A3" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 2085927845755985922" -H "X-Forwarded-For: 172.16.0.1"
# → {"code":200,"message":"操作成功","success":true}

curl -s -X POST "http://localhost:19000/api/search/hot/record?keyword=%E7%BE%8E%E5%A6%86" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 2085927845755985922" -H "X-Forwarded-For: 172.16.0.2"
# → {"code":200,"message":"操作成功","success":true}
```

**Redis 窗口验证**: `myxhs:search:window:202608081156` = {卫衣:1, 美妆:1}

**@Scheduled(60s)定时计算确认**: 日志 11:56:00→11:57:00→11:58:00 连续3次60s间隔，"Top 9 词, 最高分=0.90" (新词已聚合)

**MySQL快照**: t_hot_search_snapshot 11:57+11:58 各增加9行，卫衣=rank#1(0.90)

## 执行命令

```bash
TOKEN=$(cat /tmp/test_token.txt)
# 英文
curl -s -i -X POST "http://localhost:19000/api/search/hot/record?keyword=hello" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 999999"
# 中文（URL编码）
curl -s -i -X POST "http://localhost:19000/api/search/hot/record?keyword=%E6%B5%8B%E8%AF%95%E5%95%86%E5%93%81" \
  -H "Authorization: Bearer $TOKEN" -H "X-User-Id: 999999" -H "X-Forwarded-For: 127.0.0.1"
```

## Redis 验证

```python
import redis
r = redis.Redis(host='21.130.247.89', port=16379, password='Xhs@2026#Redis')
# 实时热搜
r.zrevrange('myxhs:search:hot:realtime', 0, 10, withscores=True)
# 屏蔽词
r.smembers('myxhs:search:hot:blocked')
# 置顶词
r.smembers('myxhs:search:hot:pinned')
```

## 注意事项

1. **Redis用16379非16381**: search通过Sentinel连接,master=16379
2. **Lua脚本原子操作**: 4步操作在单个Lua调用中完成
3. **频率限制**: 用户5s内同一词重复记录被拦截,IP 30次/分钟
4. **异步聚合**: 每分钟定时任务从bucket聚合到ZSet,有衰减算法
