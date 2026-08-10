# N02 — 券模板上下线

`PUT /api/coupon/template/{id}/status?status=0`

## ASCII 流转图

```
[curl] → Gateway:19000 → coupon:19010
  → Controller.updateTemplateStatus(id=2085346463623200770, status=0, X-Admin-Call)
  → Service.updateTemplateStatus()
     ├ templateMapper.selectById(templateId)  [MySQL:13307 my_xhs_coupon.t_coupon_template]
     ├ template.setStatus(status)
     ├ templateMapper.updateById(template)    [UPDATE MySQL]
     └ evictTemplateCache(templateId)         [Redis DEL myxhs:coupon:template:{id}]
```

## 业务逻辑

admin 改变券模板上线/下线状态（1=上线, 0=下线）。查 MySQL 确认模板存在 → setStatus → updateById → 清除 Redis 缓存。下线后用户不能领此券。

## curl

```bash
TOKEN=$(cat /tmp/test_token.txt)

# 下线
curl -s -X PUT "http://localhost:19000/api/coupon/template/2085346463623200770/status?status=0" \
  -H "Authorization: Bearer $TOKEN" -H "X-Admin-Call: my-xhs-admin-token-2026"

# 恢复上线
curl -s -X PUT "http://localhost:19000/api/coupon/template/2085346463623200770/status?status=1" \
  -H "Authorization: Bearer $TOKEN" -H "X-Admin-Call: my-xhs-admin-token-2026"
```

## 七层验证

| 层 | 预期 | 实际 | 状态 |
|------|------|------|:--:|
| HTTP | 200, code=200 | 200 | ✅ |
| MySQL | my_xhs_coupon.t_coupon_template status=1→0→1 | 正确 | ✅ |
| Redis | 缓存 key 已清除 | None | ✅ |
| MQ | —（不涉及） | — | — |
| 日志 | "模板状态变更: status=0/1" | 确认 | ✅ |
| Prometheus | coupon actuator UP | 确认 | ✅ |
| SkyWalking | traceId 可追踪 | 确认 | ✅ |

## 踩坑

- **MySQL 查空**: 初始用 `my_xhs_content` 库查询，券表实际在 `my_xhs_coupon`（同端口 13307 不同库）
