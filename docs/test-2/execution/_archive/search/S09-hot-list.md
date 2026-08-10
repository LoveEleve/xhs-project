# S09 — 热搜榜 Top 50 (GET /api/search/hot)

> 2026-08-08 | 阶段14-4 | search服务 | testuser: mytestuser

## ASCII 流转图

```
curl → Gateway:19000 (JWT→HMAC→路由)
       → my-xhs-search:19016 (/api/search/hot)
         → HotSearchService.getHotSearchList()
           ├── Redis:16379 → SMEMBERS myxhs:search:hot:pinned (置顶词Set)
           ├── Redis:16379 → ZREVRANGE myxhs:search:hot:realtime 0 49 (Top 50 ZSet)
           └── 组装: 置顶词排前面 + 过滤屏蔽词 → List<HotSearchVO>
```

## 业务逻辑

从 Redis 实时热搜 ZSet 获取 Top 50 词，置顶词优先展示(score=0.0, pinned=true)。两个数据源分开取后内存组装。

## 七层验证

| 层 | 状态 | 验证内容 |
|------|:--:|------|
| HTTP | ✅ | 200 OK, X-Trace-Id captured, 4条结果 |
| Redis | ✅ | pinned:3词 + realtime ZSet匹配("测试商品"0.90) |
| MySQL | N/A | 纯Redis查询 |
| MQ | N/A | — |
| SW | ✅ | traceId captured |
| Kibana | ✅ | traceId日志(Gateway+search访问日志) |
| Prometheus | ✅ | /api/search/hot 调用计数 2+ |

## 修复后最终验证 (2026-08-08 11:58)

> 新词"卫衣/美妆"通过 S05 记录→60s定时计算→进入 ZSet，S09 即时反映。

```json
{"code":200,"data":[
  {"rank":1,"keyword":"测试","score":0.0,"pinned":true,"tag":"置顶"},
  {"rank":2,"keyword":"pin_1785745683","score":0.0,"pinned":true,"tag":"置顶"},
  {"rank":3,"keyword":"test","score":0.0,"pinned":true,"tag":"置顶"},
  {"rank":4,"keyword":"美妆","score":0.8187,"pinned":false,"tag":"热"},
  {"rank":5,"keyword":"卫衣","score":0.8187,"pinned":false,"tag":"热"},
  {"rank":6,"keyword":"连衣裙","score":0.2499,"pinned":false,"tag":"热"},
  {"rank":7,"keyword":"运动鞋","score":0.2499,"pinned":false,"tag":"热"},
  {"rank":8,"keyword":"手机壳","score":0.2499,"pinned":false,"tag":"热"},
  {"rank":9,"keyword":"test123","score":0.0672,"pinned":false,"tag":"热"},
  {"rank":10,"keyword":"hello","score":0.0202,"pinned":false,"tag":"热"},
  {"rank":11,"keyword":"测试商品","score":0.0183,"pinned":false,"tag":"新"}
]}
```

**关键指标**: 11项(3置顶+8热词), 新词"美妆/卫衣"即时入榜 rank#4/#5, 分数衰减公式正常(0.90→0.82)。

## 执行命令

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -i "http://localhost:19000/api/search/hot" -H "Authorization: Bearer $TOKEN"
```

## 响应

```json
{"code":200,"data":[
  {"rank":1,"keyword":"测试","score":0.0,"pinned":true,"tag":"置顶"},
  {"rank":2,"keyword":"pin_1785745683","score":0.0,"pinned":true,"tag":"置顶"},
  {"rank":3,"keyword":"test","score":0.0,"pinned":true,"tag":"置顶"},
  {"rank":4,"keyword":"测试商品","score":0.9048,"pinned":false,"tag":"热"}
]}
```

## Redis对应数据

```python
import redis
r = redis.Redis(host='21.130.247.89', port=16379, password='Xhs@2026#Redis')
r.smembers('myxhs:search:hot:pinned')  # {'测试', 'test', 'pin_1785745683'}
r.zrevrange('myxhs:search:hot:realtime', 0, 5, withscores=True)  # [('测试商品', 0.90), ('测试', 0.74)]
```
