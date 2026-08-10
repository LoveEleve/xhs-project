# S03: 搜索补全 — GET /api/search/suggest

## § 源码分析
- **Controller**: `SearchController.java:85` → prefix 参数
- **Service**: `SuggestService.getSuggest()` → ES suggest_index Completion Suggester(FST前缀树) + Redis `myxhs:search:suggest:cache:{md5}` (空结果5min/正常1h)
- **下游**: ES suggest_index + Redis suggest cache

## § 业务逻辑
prefix查询 → Redis缓存命中直接返回 → 未命中 ES Completion Suggester → 回写Redis(空结果短TTL防穿透, 正常结果长TTL) → 返回top5补全

## § 前置检查
| 条件 | 验证 | 不满足后果 |
|------|------|------|
| ES suggest_index | `curl "localhost:9200/suggest_index/_count"` | 补全返回空 |
| prefix>=1字符 | 前端校验 | 空请求 |

## § 数据验证 L2
| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl "http://localhost:19000/api/search/suggest?prefix=测"` | 200, suggestList |
| ES | Completion Suggester API | FST命中 |

## § 生产级检查 L3
| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | FST O(n)前缀匹配 + Redis缓存 | ✅ |
| 防穿透 | 空结果5min短TTL | ✅ |

## § curl
```bash
curl -s "http://localhost:19000/api/search/suggest?prefix=%E6%B5%8B" | python3 -m json.tool
```

## § ASCII流转图
```
GET /search/suggest?prefix=XX
  → Redis GET myxhs:search:suggest:cache:{md5(keyword)}
    → 命中 → 返回
    → 未命中 → ES suggest_index Completion Suggester
      → 有结果→回写Redis(1h TTL)→返回top5
      → 空结果→回写Redis(5min TTL)→返回[]
```
