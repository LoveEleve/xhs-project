# R02: 相似 — GET /api/recommend/similar/{noteId}
## § 源码分析
- Controller: RecommendController.java:65
- Service: RecommendService — Redis ZSet myxhs:recommend:itemcf:{noteId} Top20 24h TTL

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
