# my-xhs-content-social 测试执行计划

> 34端点 | 链6 | 5服务(content/analytics/counter/home/search) | 参照 TEMPLATE.md

---

## 一、前置准备

```bash
TOKEN=$(cat /tmp/test_token.txt)
NOTE_ID=""  # 从内容服务创建后填写
# 确认服务在线
for s in content analytics counter home search; do
  curl -s "http://21.130.247.89:18848/nacos/v1/ns/instance/list?serviceName=my-xhs-$s&namespaceId=my-xhs" | python3 -c "import json,sys;print('$s:',len(json.load(sys.stdin)['hosts']))"
done
```

---

## 二、执行顺序

### 内容(Note + Comment) — 8端点

| 顺序 | 端点 | 依赖 | 异常 |
|:--:|------|------|:--:|
| 1 | NC01-publish-note | Token | ⚠️ @RateLimit 5/min |
| 2 | NC09-upload-image | Token | ⚠️ @RateLimit 20/min |
| 3 | NC05-note-detail | NC01 | ⚠️ 不存在/下架 |
| 4 | NC07-my-notes | Token | — |
| 5 | CM01-create-comment | NC01+Token | ⚠️ @RateLimit 10/min |
| 6 | CM03-comment-list | NC05 | 游标分页 |
| 7 | CM02-delete-comment | CM01 | — |
| 8 | NC08-publish-draft | Token+草稿 | MQ异步 |

### 社交(Favorite+Like+Follow) — 18端点

| 顺序 | 端点 | 依赖 | 异常 |
|:--:|------|------|:--:|
| 9 | FA01-favorite | NC01+Token | @Idempotent 5s |
| 10 | FA03-fav-status | FA01 | — |
| 11 | LK01-like | NC01+Token | @Idempotent 5s |
| 12 | LK03-like-status | LK01 | — |
| 13 | LK04-batch-status | LK01 | Pipeline SISMEMBER |
| 14 | FW01-follow | secondUser+Token | ⚠️ 不能关注自己 |
| 15 | FW06-relation | FW01 | 双向SISMEMBER |
| 16 | FW03-following | FW01 | 公开接口 |
| 17 | FW04-followers | FW01 | — |
| 18 | FW02-unfollow | FW01 | — |

---

## 三、关键异常场景

| 场景 | 端点 | 预期 |
|------|------|------|
| @RateLimit超限 | NC01连续6次 | 429 "操作过于频繁" |
| @Idempotent防重 | FA01 5s内POST两次 | 第二次拦截 |
| 关注自己 | FW01(targetUserId=自己) | 拒绝 |
| 游标分页漂移 | CM03→新增1条→翻页 | 不重复(lastId锚定) |
| SSE跨实例推送(多机) | NC01发笔记→通知粉丝 | notification cross-instance |
| ES搜索延迟 | NC01→等3s→search | Canal同步后可见 |

---

## 四、测试数据速查

| 数据 | 值 | 来源 |
|------|------|------|
| TOKEN | `cat /tmp/test_token.txt` | 链1 U03 |
| NOTE_ID | NC01 返回 | NC01 创建 |
| SECOND_USER_TOKEN | chaintest_u2 | 链1 U03 |
| COUNTER_SHARD | `myxhs:counter:{noteId}` | CounterEventConsumer |
