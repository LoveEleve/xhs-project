# H07: 测试推发件箱 — POST /api/home/test/push-outbox (dev)
## § 源码分析
- Controller: FeedTestController.java:49, @Profile("dev")
- 直接 Redis ZADD `myxhs:feed:outbox:{authorId}` score=时间戳
## § 业务逻辑
测试用→直接写大V发件箱, 模拟大V发布后Feed拉取流程
## § curl
```bash
curl -s -X POST "http://localhost:19000/api/home/test/push-outbox?authorId=2085927845755985922&noteId=123"
```
EOF
## § 数据验证 L2
| 层 | 验证命令 | 预期 |
|------|------|------|
| HTTP | curl -s | 200 |

## § 生产级检查 L3
| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | 并行Feign | ✅ |

## § 前置检查
| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 服务运行 | Nacos | 503 |

## § ASCII流转图
```
curl → Gateway → home:19015 → Feign×N → 聚合返回
```
