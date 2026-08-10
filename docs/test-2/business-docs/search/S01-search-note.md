# S01: 笔记搜索 — GET /api/search/note

## § 源码分析
- **Controller**: `SearchController.java:47` → keyword, sort, searchAfter, X-User-Id
- **Service**: `NoteSearchService.searchNotes()` → ES note_index multi_match title^3+content ik_smart + filter status=1 + searchAfter分页 + highlight tags
- **下游**: ES note_index + Redis `myxhs:search:history:{uid}` + Redis `myxhs:search:hot:realtime` (ZSet)

## § 业务逻辑
关键词搜索 → ES ik_smart分词 → multi_match (title权重3, content权重1) → filter status=1(仅已发布) → searchAfter分页(防深度翻页) → 高亮标记 → 异步记录热搜关键词(S09) + 写入搜索历史列表(S04) → 返回搜索结果

## § 前置检查
| 条件 | 验证 | 不满足后果 |
|------|------|------|
| ES可用 | `curl -s "localhost:9200/_cat/health"` | 搜索返回空 |
| X-User-Id or 公开访问 | 登录token或未登录均可用 | 历史不记录(匿名) |

## § 数据验证 L2
| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl -s "http://localhost:19000/api/search/note?keyword=测试&size=10"` | 200, searchResultList |
| ES | `curl -s "localhost:9200/note_index/_search" -d '{"query":{"match":{"content":"测试"}}}'` | hits返回 |
| Redis | `LRANGE myxhs:search:history:{uid} 0 19` | 关键词已追加 |

## § 生产级检查 L3
| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | ik_smart分词 + searchAfter分页 | ✅ |
| 安全 | 搜索词SQL注入过滤(ES query DSL) | ✅ |
| 可扩展 | ES分片集群负载均衡 | ✅ |

## § curl
```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s "http://localhost:19000/api/search/note?keyword=%E6%B5%8B%E8%AF%95&size=10&sort=time" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

## § ASCII流转图
```
GET /search/note?keyword=XX&size=10
  → Gateway → search:19016 SearchController.searchNotes()
    → ES note_index query(multi_match title^3+content, ik_smart)
    → filter term(status=1)
    → search_after cursor分页(10条)
    → highlight pre_tags/post_tags
    → 异步: Redis S09 record keyword (Lua反作弊) + S04 LPUSH history
    → 返回 {searchResultList, searchAfter, total}
```
