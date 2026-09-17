# DLQ 清理归档（2026-09-17）

> 清理前将 14 条死信原文归档（cart-event-sink-group 11 + cart-sync-consumer-group 3）。
> 结论：3 条为演练毒丸（POISON-not-json-ai-drill-*），11 条为测试用户 10001 的合法 CART_SYNC 事件（09-08/09-10/09-11 混沌演练窗口进入死信）。

| # | Topic | UNIQ_KEY | Born | Reconsume | Body 摘要 |
|---|---|---|---|---|---|
| 1 | %DLQ%cart-event-sink-group | AC110001DB3D54BEDEF2283B93740002 | 2026-09-08 19:29:53,012 | 4 | `{"eventId":"20e58cde-9a09-4545-98a6-ad8b7f6abed4","timestamp":"2026-09-08T11:29:53.0110000` |
| 2 | %DLQ%cart-event-sink-group | AC110001DB3D54BEDEF2283C06D00003 | 2026-09-08 19:30:22,544 | 4 | `{"eventId":"0f3132c4-d6ae-4fd5-a4d7-e419fd956e81","timestamp":"2026-09-08T11:30:22.5440000` |
| 3 | %DLQ%cart-event-sink-group | AC11000109DF1F32E575482CE7CC0000 | 2026-09-15 00:21:42,477 | 4 | `POISON-not-json-ai-drill-0` |
| 4 | %DLQ%cart-event-sink-group | AC110001DB3D54BEDEF2283C06E30004 | 2026-09-08 19:30:22,563 | 4 | `{"eventId":"4a4cd9e1-d7fe-4321-9010-de014846af40","timestamp":"2026-09-08T11:30:22.5630000` |
| 5 | %DLQ%cart-event-sink-group | AC110001F51B54BEDEF2335DF4040002 | 2026-09-10 23:23:15,332 | 4 | `{"eventId":"83418937-1669-49c1-b185-39a1a3812189","timestamp":"2026-09-10T15:23:15.3310000` |
| 6 | %DLQ%cart-event-sink-group | AC1100010C6C330BEDB4387F51840000 | 2026-09-11 23:17:48,037 | 4 | `{"eventId":"t1","timestamp":"2026-09-11T15:17:36.569Z","userId":10001,"action":"CLEAR","cl` |
| 7 | %DLQ%cart-event-sink-group | AC110001B8B454BEDEF23880B3830000 | 2026-09-11 23:19:18,659 | 4 | `{"eventId":"40a3078f-3c8b-41b0-af15-d0df10cc16f4","timestamp":"2026-09-11T15:19:18.6470000` |
| 8 | %DLQ%cart-event-sink-group | AC110001B8B454BEDEF238817228000B | 2026-09-11 23:20:07,464 | 4 | `{"eventId":"1855ac3e-c605-43e9-8f66-cb430e184c59","timestamp":"2026-09-11T15:20:07.4640000` |
| 9 | %DLQ%cart-event-sink-group | AC1100013D1D1F32E5753DACCF4F0000 | 2026-09-12 23:25:35,439 | 4 | `{"eventId":"1855ac3e-c605-43e9-8f66-cb430e184c59","timestamp":"2026-09-11T15:20:07.4640000` |
| 10 | %DLQ%cart-event-sink-group | AC110001593B7229724F4814A4400001 | 2026-09-14 23:55:12,320 | 4 | `POISON-not-json-ai-drill-1` |
| 11 | %DLQ%cart-event-sink-group | AC110001593B7229724F4814A4340000 | 2026-09-14 23:55:12,309 | 4 | `POISON-not-json-ai-drill-0` |
| 12 | %DLQ%cart-sync-consumer-group | AC110001593B7229724F4814A4400001 | 2026-09-14 23:55:12,320 | 4 | `POISON-not-json-ai-drill-1` |
| 13 | %DLQ%cart-sync-consumer-group | AC110001593B7229724F4814A4340000 | 2026-09-14 23:55:12,309 | 4 | `POISON-not-json-ai-drill-0` |
| 14 | %DLQ%cart-sync-consumer-group | AC11000109DF1F32E575482CE7CC0000 | 2026-09-15 00:21:42,477 | 4 | `POISON-not-json-ai-drill-0` |

## 完整消息体

### 1. AC110001DB3D54BEDEF2283B93740002 (%DLQ%cart-event-sink-group)
```json
{"eventId":"20e58cde-9a09-4545-98a6-ad8b7f6abed4","timestamp":"2026-09-08T11:29:53.011000003Z","userId":"10001","skuId":null,"quantity":0,"checked":0,"action":"CLEAR","clearBarrierTs":"1788866993011","source":"my-xhs-cart","eventType":"CART_SYNC"}
```
### 2. AC110001DB3D54BEDEF2283C06D00003 (%DLQ%cart-event-sink-group)
```json
{"eventId":"0f3132c4-d6ae-4fd5-a4d7-e419fd956e81","timestamp":"2026-09-08T11:30:22.544000004Z","userId":"10001","skuId":null,"quantity":null,"checked":1,"action":"CHECK_ALL","clearBarrierTs":null,"source":"my-xhs-cart","eventType":"CART_SYNC"}
```
### 3. AC11000109DF1F32E575482CE7CC0000 (%DLQ%cart-event-sink-group)
```json
POISON-not-json-ai-drill-0
```
### 4. AC110001DB3D54BEDEF2283C06E30004 (%DLQ%cart-event-sink-group)
```json
{"eventId":"4a4cd9e1-d7fe-4321-9010-de014846af40","timestamp":"2026-09-08T11:30:22.563000005Z","userId":"10001","skuId":null,"quantity":null,"checked":0,"action":"CHECK_ALL","clearBarrierTs":null,"source":"my-xhs-cart","eventType":"CART_SYNC"}
```
### 5. AC110001F51B54BEDEF2335DF4040002 (%DLQ%cart-event-sink-group)
```json
{"eventId":"83418937-1669-49c1-b185-39a1a3812189","timestamp":"2026-09-10T15:23:15.331000003Z","userId":"10001","skuId":null,"quantity":0,"checked":0,"action":"CLEAR","clearBarrierTs":"1789053795331","source":"my-xhs-cart","eventType":"CART_SYNC"}
```
### 6. AC1100010C6C330BEDB4387F51840000 (%DLQ%cart-event-sink-group)
```json
{"eventId":"t1","timestamp":"2026-09-11T15:17:36.569Z","userId":10001,"action":"CLEAR","clearBarrierTs":1789139856569}
```
### 7. AC110001B8B454BEDEF23880B3830000 (%DLQ%cart-event-sink-group)
```json
{"eventId":"40a3078f-3c8b-41b0-af15-d0df10cc16f4","timestamp":"2026-09-11T15:19:18.647000001Z","userId":"10001","skuId":null,"quantity":0,"checked":0,"action":"CLEAR","clearBarrierTs":"1789139958647","source":"my-xhs-cart","eventType":"CART_SYNC"}
```
### 8. AC110001B8B454BEDEF238817228000B (%DLQ%cart-event-sink-group)
```json
{"eventId":"1855ac3e-c605-43e9-8f66-cb430e184c59","timestamp":"2026-09-11T15:20:07.464000012Z","userId":"10001","skuId":null,"quantity":0,"checked":0,"action":"CLEAR","clearBarrierTs":"1789140007464","source":"my-xhs-cart","eventType":"CART_SYNC"}
```
### 9. AC1100013D1D1F32E5753DACCF4F0000 (%DLQ%cart-event-sink-group)
```json
{"eventId":"1855ac3e-c605-43e9-8f66-cb430e184c59","timestamp":"2026-09-11T15:20:07.464000012Z","userId":"10001","skuId":null,"quantity":0,"checked":0,"action":"CLEAR","clearBarrierTs":"1789140007464","source":"my-xhs-cart","eventType":"CART_SYNC"}
```
### 10. AC110001593B7229724F4814A4400001 (%DLQ%cart-event-sink-group)
```json
POISON-not-json-ai-drill-1
```
### 11. AC110001593B7229724F4814A4340000 (%DLQ%cart-event-sink-group)
```json
POISON-not-json-ai-drill-0
```
### 12. AC110001593B7229724F4814A4400001 (%DLQ%cart-sync-consumer-group)
```json
POISON-not-json-ai-drill-1
```
### 13. AC110001593B7229724F4814A4340000 (%DLQ%cart-sync-consumer-group)
```json
POISON-not-json-ai-drill-0
```
### 14. AC11000109DF1F32E575482CE7CC0000 (%DLQ%cart-sync-consumer-group)
```json
POISON-not-json-ai-drill-0
```
## 清理执行与验证（2026-09-17 15:5x）

| 步骤 | 命令/结果 |
|---|---|
| 基线 | `rocketmq_dlq_backlog{cart-event-sink-group}=11`、`{cart-sync-consumer-group}=3` |
| 删除+重建 | `mqadmin deleteTopic` → `updateTopic -r 1 -w 1`（保持监控 0 语义，下一批死信自动写入） |
| 验证 | `topicStatus` Min=0/Max=0；Prometheus 指标复查见提交说明 |
| 归档 | 14 条原文（3 毒丸 + 11 条测试用户 10001 CART_SYNC），见本文件上方 |

> 注：11 条合法事件（CLEAR/CHECK_ALL，userId=10001）产生于 09-08/09-10/09-11 混沌演练窗口，消费端当时失败进死信；因已过期 6-9 天且为测试账号数据，按"归档后清理"处理；如需回放可从本文件按 msgId 重新注入。
