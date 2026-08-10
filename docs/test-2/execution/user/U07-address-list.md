# U07 — 地址列表 (GET /api/user/address/list)

> 2026-08-08 | 链1-8 | user服务 | chaintest_c1

## § 业务逻辑

查询当前用户所有地址→MySQL SELECT WHERE user_id=? AND deleted=0→返回列表。响应中 receiverPhone 脱敏(138****8000)。

## § ASCII 流转图
```
curl → Gateway:19000(JWT→X-User-Id)
       → my-xhs-user:19001(GET /api/user/address/list)
         → MySQL SELECT t_user_address WHERE user_id=?
```

## § 业务链验证
| 检查项 | 结果 |
|------|:--:|
| 地址数量 | count=1 ✅(仅U06创建的地址) |
| 手机脱敏 | 138****8000 ✅ |
| 默认地址 | is_default=1 ✅ |

## § 数据验证 (L2)
| 层 | 结果 |
|------|:--:|
| HTTP | 200, count=1 ✅ |
| MySQL | t_user_address WHERE user_id=2085982901507301378 = 1行 ✅ |

## § 生产级检查 (L3)
| 透镜 | 检查 |
|------|------|
| 安全 | 手机号脱敏(仅展示后4位) ✅ |

## § curl
```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s http://localhost:19000/api/user/address/list -H "Authorization: Bearer $TOKEN"
```

## § 可观测性 (L4)

| 层 | 状态 |
|------|:--:|
| SW | X-Trace-Id captured ✅ |
| Prometheus | GET user:19001/actuator/prometheus 指标正常 ✅ |
| Kibana | traceId 日志可查 ✅ |
