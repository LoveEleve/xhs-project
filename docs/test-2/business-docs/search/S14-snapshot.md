# S14: 快照 — GET /api/search/hot/snapshot
## § 源码分析
- Controller: SearchController.java:216
- Service: HotSearchService — MySQL t_hot_search_snapshot WHERE snapshot_time范围查询

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
