# H04: GET /api/home/user/{targetUserId}
## § 源码分析
- Controller: HomeController.java:111, X-User-Id可选
- Service: `UserProfileAggService.getUserProfile()` — 4路Feign: user(publicInfo), analytics(followerCount+followingCount+relation), content(userNotes)
## § 业务逻辑
并行Feign取用户信息+关注/粉丝计数+关系状态+作品列表→聚合
## § curl
```bash
curl -s "http://localhost:19000/api/home/user/2085927845755985922"
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
