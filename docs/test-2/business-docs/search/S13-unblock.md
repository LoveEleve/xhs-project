# S13: 取消屏蔽 — DELETE /api/search/hot/block
## § 源码分析
- Controller: SearchController.java:203, X-Admin-Call
- Service: SREM myxhs:search:hot:blocked

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
