# my-xhs-notification 业务逻辑分析

## 一、通知生命周期

```
1. 生产者(外部服务) → MQ/Feign → NotificationService.createNotification
2. 写MySQL + 聚合 → SELECT 已有同 type + 同 user_id + 同 notify_date
   → 存在: UPDATE content + update_time
   → 不存在: INSERT
3. SSE推送 → SseEmitterManager.sendToUser → 在线则实时推送
4. 用户阅读 → POST /read/{id} → UPDATE is_read=1
   → 全部已读 → POST /read-all → UPDATE is_read=1 WHERE user_id=?
```

## 二、聚合通知逻辑

```
notify_date 生成列: DATE(create_time)

同一用户在同一天收到同类通知(LIKE/COMMENT/FOLLOW):
  → 不创建新记录 → UPDATE content 追加新消息
  → 前端展示: "3人赞了你的笔记" 而非 3条独立记录

SQL伪代码:
  INSERT INTO t_notification (user_id, type, content) VALUES (?, ?, ?)
  ON DUPLICATE KEY (user_id, type, notify_date)
    UPDATE content = CONCAT(content, ',', VALUES(content))
```

## 三、未读计数

```
getUnreadCount(userId):
  SELECT COUNT(*) FROM t_notification WHERE user_id=? AND is_read=0
  GROUP BY type → 返回 { total, like, comment, follow }

呈现: 角标数字 ← SS E实时推送更新
```

## 四、全部已读

```
markAllAsRead(userId):
  UPDATE t_notification SET is_read=1 WHERE user_id=? AND is_read=0
  → 无需 type 过滤 → 全部刷新

read-by-type:
  UPDATE t_notification SET is_read=1 WHERE user_id=? AND type=? AND is_read=0
```

## 五、SSE 推送格式

```
event: notification
data: {"id": 123, "type": 1, "content": "张三赞了你的笔记", "createdAt": "..."}

event: heartbeat
data: ping
```
