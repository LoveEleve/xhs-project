# S09: 记录热搜关键词 — POST /api/search/hot/record

## § 源码分析
- **Controller**: `SearchController.java:152` → `@PostMapping("/hot/record")`, X-User-Id
- **Service**: `HotSearchService.recordSearchKeyword()` → Lua script EVAL: 用户频率(5次/分钟) + IP频率(100次/分钟) + 分钟桶Key `myxhs:search:antispam:user:{uid}:{mm}` + ZINCRBY `myxhs:search:hot:realtime` + 屏蔽词Set过滤
- **下游**: Redis Lua + ZSet realtime + Set blocked

## § 业务逻辑
搜索后异步调S09 → Lua原子执行: INCR用户计数器(KEY=user:{uid}:{minuteBucket}) → 超过5→拒绝 → INCR IP计数器(100/min) → 超过→拒绝 → SISMEMBER blocked Set → 已屏蔽→拒绝 → ZINCRBY `myxhs:search:hot:realtime` +1(指数衰减score) → 成功

## § 前置检查
| 条件 | 验证 | 不满足后果 |
|------|------|------|
| Redis可用 | Lua EVAL需Redis连接 | 记录失败 |

## § 数据验证 L2
| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl POST /api/search/hot/record -d '{"keyword":"测试"}'` | 200 |
| Redis | `ZRANK myxhs:search:hot:realtime "测试"` | 存在且score递增 |
| Redis | `GET myxhs:search:antispam:user:{uid}:{00-59}` | 用户计数器 |

## § 生产级检查 L3
| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 防作弊 | 用户5/min + IP 100/min + 屏蔽词 | ✅ |
| 原子性 | Lua EVAL单次网络往返 | ✅ |

## § curl
```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -X POST "http://localhost:19000/api/search/hot/record" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"keyword":"卫衣"}'
```

## § ASCII流转图
```
POST /search/hot/record {keyword}
  → Lua EVAL (原子):
    INCR myxhs:search:antispam:user:{uid}:{minuteBucket}
    → count>5 → EXIT 0(拒绝)
    INCR myxhs:search:antispam:ip:{ip}:{minuteBucket}
    → count>100 → EXIT 0(拒绝)
    SISMEMBER myxhs:search:hot:blocked keyword
    → 1 → EXIT 0(命中屏蔽)
    ZINCRBY myxhs:search:hot:realtime 1 keyword
    → score = 1 * exp(-t/3600) (指数衰减)
    → EXIT 1(成功)
```
