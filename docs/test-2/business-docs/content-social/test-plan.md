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

| 顺序 | 端点 | 依赖 | 产出 | 异常 |
|:--:|------|------|------|:--:|
| 1 | NC01-publish-note | Token | execution/content-social/NC01-publish-note.md | ⚠️ @RateLimit 5/min |
| 2 | NC09-upload-image | Token | execution/content-social/NC09-upload-image.md | ⚠️ @RateLimit 20/min |
| 3 | NC05-note-detail | NC01 | execution/content-social/NC05-note-detail.md | ⚠️ 不存在/下架 |
| 4 | NC07-my-notes | Token | execution/content-social/NC07-my-notes.md | — |
| 5 | CM01-create-comment | NC01+Token | execution/content-social/CM01-create-comment.md | ⚠️ @RateLimit 10/min |
| 6 | CM03-comment-list | NC05 | execution/content-social/CM03-comment-list.md | 游标分页 |
| 7 | CM02-delete-comment | CM01 | execution/content-social/CM02-delete-comment.md | — |
| 8 | NC08-publish-draft | Token+草稿 | execution/content-social/NC08-publish-draft.md | MQ异步 |

### 社交(Favorite+Like+Follow) — 18端点

| 顺序 | 端点 | 依赖 | 产出 | 异常 |
|:--:|------|------|------|:--:|
| 9 | FA01-favorite | NC01+Token | execution/content-social/FA01-favorite.md | @Idempotent 5s |
| 10 | FA03-fav-status | FA01 | execution/content-social/FA03-fav-status.md | — |
| 11 | LK01-like | NC01+Token | execution/content-social/LK01-like.md | @Idempotent 5s |
| 12 | LK03-like-status | LK01 | execution/content-social/LK03-like-status.md | — |
| 13 | LK04-batch-status | LK01 | execution/content-social/LK04-batch-status.md | Pipeline SISMEMBER |
| 14 | FW01-follow | secondUser+Token | execution/content-social/FW01-follow.md | ⚠️ 不能关注自己 |
| 15 | FW06-relation | FW01 | execution/content-social/FW06-relation.md | 双向SISMEMBER |
| 16 | FW03-following | FW01 | execution/content-social/FW03-following.md | 公开接口 |
| 17 | FW04-followers | FW01 | execution/content-social/FW04-followers.md | — |
| 18 | FW02-unfollow | FW01 | execution/content-social/FW02-unfollow.md | — |

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

---
## 测试要点补充（2026-08-10，实测修正）

- **认证**：写操作(发笔记/评论/点赞/收藏/关注/删评论/草稿/传图)均需 JWT+HMAC；读(详情/评论列表/关注列表)公开。
- **LK01-like**：bizType 是 **int(1笔记/2评论)**，非字符串。
- **FA01-favorite**：body 用 **noteId**（非 bizId）。
- **NC09-upload-image**：multipart `file` 字段，需**真实图片文件**（假字节返回40002）。
- **NC08-publish-draft**：POST /api/note/draft。
- **CM01-create-comment**：body{noteId,content}，空content返回40002。
- 事件流：like/favorite/comment/follow 会生成通知（供链7 notification 测试用）。

---
## L0-L4 逐端点核对清单

### NC01-publish-note
- [ ] L0: token+HMAC
- [ ] L1正常: 200 noteId; 异常: @RateLimit 5/min→429
- [ ] L2: MySQL `t_note` 新增; 发 FEED/SOCIAL 事件
- [ ] L3: RateLimit / 异步(MQ)

### LK01-like / FA01-favorite
- [ ] L1: 200; 重复5s内→幂等拦截
- [ ] L2: Redis `myxhs:like:set:{type}:{id}` / counter
- [ ] L3: 幂等(5s) / 并发

### CM01-create-comment
- [ ] L1: 200; 空内容→40002
- [ ] L2: MySQL `t_comment`
- [ ] L3: RateLimit 10/min

### FW01-follow
- [ ] L1: 200; 关注自己→拒绝
- [ ] L2: Redis 关注关系 + counter
- [ ] L3: 双向关系
