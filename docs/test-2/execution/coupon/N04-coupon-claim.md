# N04 — 领券 (POST /api/coupon/claim)

> 2026-08-08 | 链4-2 | templateId=2085998573675192321

## § 业务逻辑
Lua原子扣库存→MQ COUPON_CLAIM_TOPIC syncSend→Consumer异步写MySQL。失败回滚Lua。

## § 验证数据
| 层 | 值 |
|------|------|
| Redis stock | 50→49 |
| MySQL | t_user_coupon claim_no=24e0b52b... status=0 |
| MQ | CouponClaimConsumer "领券持久化成功" |

## § 生产级检查 (L3)
| 透镜 | 检查 | 结果 |
|------|------|:--:|
| 业务自洽 | Lua→MQ→MySQL全链路 | ✅ |
| 数据一致 | Redis↔MySQL↔MQ outbox一致 | ✅ |
| 幂等安全 | claim_no唯一索引; Lua原子操作 | ✅ |
| 回滚完整 | MQ失败→Lua回滚库存 | ✅ |
| **性能** | RateLimit 5/min; Lua<5ms; MQ timeout=3s | ✅ |
| **可扩展** | {templateId} hash tag同slot | ✅ |
| **微服务** | MQ syncSend→Consumer异步; Outbox补偿 | ✅ |
| **并发** | Lua单KEY原子; uk_claim_no幂等 | ✅ |
| **安全** | perUserLimit限领; claim_no UUID防篡改 | ✅ |

## § curl
```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -X POST http://localhost:19000/api/coupon/claim -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -H "X-User-Id: 2085982901507301378" \
  -d '{"templateId":2085998573675192321}'
```
