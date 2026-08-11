# my-xhs-notification 测试执行计划

> 9端点 | 链7 | dev profile 需激活以启用 N09

---

## 一、前置准备

```bash
TOKEN=$(cat /tmp/test_token.txt)

# 确认 notification 在线 (需 dev profile 以启用 N09)
curl -sf localhost:19013/actuator/health >/dev/null || echo "notification DOWN"

# 确认推送模板存在
mysql -h 21.130.247.89 -P 3306 -u root -p'Xhs@2026#MySQL' -N -e "USE my_xhs_notification; SELECT COUNT(*) FROM t_push_template WHERE status=1;" 2>/dev/null

# 确认 SSE 管理端点可达 (用于 N09 测试发送)
curl -sf localhost:19013/actuator/health >/dev/null && echo "notification OK"

# chaintest_u2 用于接收通知 (需链1 已注册)
echo "接收通知用户ID: 从链1 U14 注册后查看 MySQL my_xhs_user.t_user"
```

> **测试前提构造（关键）**：N04/N05/N06 需**先有通知**才能测。通知源=让 u2 对 u1 做社交动作，事件流经 NotificationEventConsumer 异步生成：
> ```bash
> # 1. 登录 u2 拿 token+hmacSecret, 用 u2 身份(带HMAC签名)执行:
> #    u2 关注 u1:  POST /api/social/follow/{u1Id}        → FOLLOW 通知
> #    u2 点赞 u1笔记: POST /api/social/like {bizType:1,bizId:noteId} → LIKE 通知
> #    u2 评论 u1笔记: POST /api/comment {noteId,content} → COMMENT 通知
> # 2. 等 3-5s 让 MQ 消费者落库 (日志: "[优惠券MQ] 收到通知消息"/"通知...成功")
> # 3. 再用 u1 的 token 查 GET /api/notification/list 确认通知已生成
> ```
> ⚠️ 若通知列表为空，先查 notification 日志是否有 "通知消费失败 / PushTemplateMapper.selectByType" 报错——曾因 selectByType 引用不存在的 `deleted` 列 + 大小写不匹配导致消费者崩溃(见 pitfalls #48)，已修复。

## 二、执行顺序

| 顺序 | 端点 | 依赖 | 产出文件 | dev | 正常+异常 |
|:--:|------|------|------|:--:|:--:|
| 1 | N01-sse-ticket | Token | execution/notification/N01-sse-ticket.md | — | ✅ 正常 |
| 2 | N02-sse-connect | N01 ticket | execution/notification/N02-sse-connect.md | — | ✅ 正常 |
| 3 | N03-list | Token | execution/notification/N03-list.md | — | ✅ 正常 |
| 4 | N04-unread-count | Token | execution/notification/N04-unread-count.md | — | ✅ 正常 |
| 5 | N05-mark-read | Token + 有通知 | execution/notification/N05-mark-read.md | — | ✅ 正常 |
| 6 | N06-read-by-type | Token + 有通知 | execution/notification/N06-read-by-type.md | — | ✅ 正常 |
| 7 | N07-read-all | Token + 有通知 | execution/notification/N07-read-all.md | — | ✅ 正常 |
| 8 | N08-online-count | Token | execution/notification/N08-online-count.md | — | ✅ 正常 |
| 9 | N09-test-send | Token + u2 ID + dev | execution/notification/N09-test-send.md | ✅ | ✅ 正常 |

> N02 是 SSE EventSource 长连接 — 需浏览器或 `curl -N` 流式读取
> N05/N06/N07 依赖已存在通知 — 可先 N09 发送测试通知再测
> N09 需 `@Profile("dev")` 激活，pre-test-init Step 4 已重启 notification 为 dev

## 三、异常场景

| 场景 | 端点 | 预期 |
|------|------|------|
| 缺少 JWT | N01 | 401 |
| 通知列表空 | N03 | `data:[]` (非异常) |
| dev profile 未激活 | N09 | 404 |
| SSE 连接无 ticket | N02 | SSE 推送不到 |

---
## 测试要点补充（2026-08-10，实测修正）

- **认证**：走 gateway 19000，JWT+HMAC。
- **⚠️ 通知前提必须构造**：N03-N07 需先有通知，方法=让 u2 对 u1 关注/点赞/评论，事件流经 NotificationEventConsumer 生成（曾因 selectByType 引用不存在 deleted 列+大小写不匹配导致消费者崩溃，#48 已修）。
- **N09-test-send**：需 **dev profile**（已加入 start-all.sh）；POST /api/notification/test/send，body{type,senderId,targetUserId,content}。
- **N02-sse-connect**：GET /api/notification/sse?ticket= （SSE 长连接，用 curl -N 或 SSE 客户端）。
- **N08-online-count**：需 `X-Admin-Call`。
- 通知列表 N03 返回分页 dict(records/total)。

---
## L0-L4 逐端点核对清单

### 通知前提构造
- [ ] L0: 让 u2 对 u1 关注/点赞/评论 生成通知(等MQ消费)
### N03-list / N04-unread / N05-read / N06-bytype
- [ ] L1: 列表/未读/标记已读 → 200
- [ ] L2: MySQL `t_notification` 行; is_read 0→1; Redis未读数
- [ ] L3: 幂等(msgId去重)
### N09-test-send
- [ ] L0: dev profile
- [ ] L1: POST → 200
- [ ] L2: 目标用户收到通知
