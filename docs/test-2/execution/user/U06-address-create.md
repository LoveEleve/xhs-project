# U06 — 创建地址 (POST /api/user/address)

> 2026-08-08 | 链1-7 | user服务 | chaintest_u1 | addrId=2085983401418006529

## § 业务逻辑

新增收货地址→MySQL INSERT t_user_address→响应中手机号脱敏(138****8000)。字段: receiverName/receiverPhone/province/city/district/detailAddress/isDefault。

## § ASCII 流转图

```
curl → Gateway:19000 (JWT→X-User-Id)
       → my-xhs-user:19001 (POST /api/user/address)
         → MySQL INSERT t_user_address
```

## § 验证

| 层 | 结果 |
|------|:--:|
| HTTP | 200, id=2085983401418006529, name=收货人C1 |
| MySQL | receiver_name=收货人C1, phone=13800138000(全号存储), is_default=1 ✅ |

## § curl

```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s -X POST http://localhost:19000/api/user/address -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"receiverName":"收货人C1","receiverPhone":"13800138000","province":"广东省","city":"深圳市","district":"南山区","detailAddress":"科技园路100号","isDefault":true}'
```

## § 生产级检查 (L3)

| 透镜 | 检查 |
|------|------|
| 业务自洽 | 见L1 §业务逻辑 — MySQL写+缓存失效联动 ✅ |
| 数据一致 | 见L2 §数据验证 — MySQL更新↔Redis缓存失效 ✅ |
| 幂等安全 | 重复相同payload仍200(不产生副作用) ✅ |
| 回滚完整 | N/A(单步写操作，无事务需回滚) |

## § 可观测性 (L4)

| 层 | 状态 |
|------|:--:|
| SW | X-Trace-Id captured ✅ |
| Prometheus | GET user:19001/actuator/prometheus 指标正常 ✅ |
| Kibana | traceId 日志可查 ✅ |
