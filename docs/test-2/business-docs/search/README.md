# my-xhs-search 搜索服务
> 18端点 | SearchController+RecommendController | 19016

## SearchController(/api/search) — 14端点
| ID | 方法 | 路径 | 说明 |
|------|------|------|------|
| S01 | GET | /search/note | ES note_index搜索+ik_smart |
| S02 | GET | /search/product | ES product_index搜索 |
| S03 | GET | /search/suggest | Completion Suggester(FST) |
| S04 | GET | /search/history | 搜索历史(Redis List) |
| S05 | DELETE | /search/history | 清空历史 |
| S06 | DELETE | /search/history/{kw} | 删除单条 |
| S07 | POST | /search/index/rebuild | 索引重建(X-Admin) |
| S08 | GET | /search/hot | 实时热搜(Redis ZSet) |
| S09 | POST | /search/hot/record | 记录关键词(Lua反作弊) |
| S10 | PUT | /search/hot/pin | 置顶(X-Admin) |
| S11 | DELETE | /search/hot/pin | 取消置顶 |
| S12 | PUT | /search/hot/block | 屏蔽(X-Admin) |
| S13 | DELETE | /search/hot/block | 取消屏蔽 |
| S14 | GET | /search/hot/snapshot | 快照查询(MySQL) |

## RecommendController(/api/recommend) — 4端点
| ID | 方法 | 路径 | 说明 |
|------|------|------|------|
| R01 | GET | /recommend/feed | 5路召回→精排→重排 |
| R02 | GET | /recommend/similar/{noteId} | ItemCF相似 |
| R03 | POST | /recommend/behavior | 行为上报(MQ) |
| R04 | POST | /recommend/compute | 离线计算(X-Admin) |

## ES三索引
- note_index: 笔记(multi_match title^3+content, ik_smart)
- product_index: 商品(match name+filter status/category/price)
- suggest_index: 补全(Completion Suggester FST)

## Redis Key
- `myxhs:search:hot:realtime` — 热搜ZSet  
- `myxhs:search:hot:pinned` — 置顶Set
- `myxhs:search:hot:blocked` — 屏蔽Set
- `myxhs:search:history:{uid}` — 搜索历史List
- `myxhs:search:suggest:cache:{md5}` — 补全缓存
- `myxhs:search:antispam:user:{uid}:{kw}` — 反作弊用户频率
- `myxhs:recommend:itemcf:{noteId}` — ItemCF相似24h TTL
- `myxhs:recommend:seen:{uid}` — 已看Set 7天

## § 业务逻辑
{业务描述}

## § 前置检查
| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 服务运行 | Nacos | 503 |

## § 数据验证 L2
| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | curl -s | 200 |

## § 生产级检查 L3
| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | 可行 | ✅ |

## § curl
```bash
curl -s http://localhost:19000
```

## § ASCII流转图
```
curl → Gateway → search:19016 → Redis/ES/MySQL
```
