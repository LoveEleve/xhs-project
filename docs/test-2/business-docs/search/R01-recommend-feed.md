# R01: 推荐Feed — GET /api/recommend/feed

## § 源码分析
- **Controller**: `RecommendController.java:52` → `@GetMapping("/feed")`, X-User-Id
- **Service**: `RecommendService.getRecommendFeed()` → 5路召回并行(ItemCF Redis ZSet + Content ES向量 + Hot Redis ZSet global + Following ZSet inbox + Geo ES geo_distance) → 粗排质量分(点赞率/完读率) → 精排CTR预估模型 → 重排多样性(类目打散) → SDIFF过滤已看Set `myxhs:recommend:seen:{uid}` 7天TTL
- **下游**: Redis ZSet + ES + MySQL t_user_behavior + Redis seen Set

## § 业务逻辑
5路并行召回(ItemCF相似+Content内容+Hot热门+Following关注+Geo附近) → 粗排过滤低质(min质量分) → 精排排序(CTR预估) → 重排多样性(类目打散maxSameCategory=3) → 已看去重(SDIFF) → 返回Top20

## § 前置检查
| 条件 | 验证 | 不满足后果 |
|------|------|------|
| ItemCF数据存在 | `ZCARD myxhs:recommend:itemcf:{noteId}` | ItemCF路边空 |
| Cold Start | 新用户数据不足 | 降级hot+geo |

## § 数据验证 L2
| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | `curl "http://localhost:19000/api/recommend/feed?size=20" -H "Authorization: Bearer $TOKEN"` | 200 |
| Redis | `ZCARD myxhs:recommend:itemcf:{noteId}` | ItemCF相似度 |
| Redis | `SCARD myxhs:recommend:seen:{uid}` | 已看数 |

## § 生产级检查 L3
| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | 5路并行召回(30s总超时) | ✅ |
| 冷启动 | 降级hot+geo兜底 | ✅ |

## § curl
```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s "http://localhost:19000/api/recommend/feed?size=10&page=1" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

## § ASCII流转图
```
GET /recommend/feed?size=20
  → RecommendController:19016 → RecommendService.getRecommendFeed()
    → 5路并行召回(CompletableFuture.allOf):
      ├─ ItemCF: ZREVRANGE myxhs:recommend:itemcf:{likedNoteId} → nid列表
      ├─ Content: ES note_index kNN向量搜索 → nid列表
      ├─ Hot: ZREVRANGE myxhs:recommend:hot:global → nid列表
      ├─ Following: ZREVRANGE myxhs:feed:inbox:{uid} → nid列表
      └─ Geo: ES geo_distance → nid列表
    → 合并去重 → 粗排(质量分>=0.3过滤)
    → 精排(CTR预估排序)
    → 重排(类目打散 maxSame=3)
    → SDIFF myxhs:recommend:seen:{uid}(已看过滤)
    → 返回 Top20
```
