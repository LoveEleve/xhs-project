# S08: 实时热搜 — GET /api/search/hot

## § 源码分析
- **Controller**: `SearchController.java:144` → `@GetMapping("/hot")`, 公开接口
- **Service**: `HotSearchService.getHotSearchList()` → ZREVRANGE `myxhs:search:hot:realtime` + SMEMBERS `myxhs:search:hot:pinned` → SDIFF过滤 `myxhs:search:hot:blocked` → 合并排序
- **下游**: Redis ZSet + Set pinned + Set blocked

## § 业务逻辑
取热搜ZSet Top20(按score指数衰减) → 合并置顶Set(管理员pin的热词排前) → 过滤屏蔽Set → 返回排序后列表

## § 前置检查
| 条件 | 验证 | 不满足后果 |
|------|------|------|
| Redis可用 | `python3 -c "import redis;r=redis.Redis(port=26379);r.ping()"` | 返回空 |
| Nacos注册 | Gateway路由 | 503 |

## § 数据验证 L2
| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl "http://localhost:19000/api/search/hot"` | 200, hotKeywordVOList |
| Redis | `ZREVRANGE myxhs:search:hot:realtime 0 19 WITHSCORES` | 热搜列表 |
| Redis | `SMEMBERS myxhs:search:hot:pinned` | 置顶词 |

## § 生产级检查 L3
| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | ZSet O(logN) + Set O(1) | ✅ |
| 安全 | 屏蔽词过滤(blocked Set) | ✅ |

## § curl
```bash
curl -s "http://localhost:19000/api/search/hot" | python3 -m json.tool
```

## § ASCII流转图
```
GET /search/hot
  → ZREVRANGE myxhs:search:hot:realtime 0 19 WITHSCORES
  → SMEMBERS myxhs:search:hot:pinned (管理员置顶)
  → SDIFF过滤 myxhs:search:hot:blocked
  → 合并: pinned词排前 + ZSet衰减排后
  → 返回 {hotKeywordVOList}
```
