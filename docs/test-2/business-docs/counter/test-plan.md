# my-xhs-counter 测试执行计划

> 3端点 | 链6依赖 | 纯消费者模块

---

## 一、前置准备

```bash
# Token (链1产出)
TOKEN=$(cat /tmp/test_token.txt)

# 确认 counter 服务在线
curl -sf localhost:19004/actuator/health >/dev/null || echo "counter DOWN"

# ⚠️ counter 是纯消费者 — 需要链6 先产生 like/fav/comment 事件才有计数数据
#   如果 Redis myxhs:counter:1:{noteId}:1 不存在，CT01 返回 0 是正常行为
#   完整测试需在链6 content-social 完成后进行
```

## 二、执行顺序

| 顺序 | 端点 | 依赖 | 产出文件 | 正常+异常 |
|:--:|------|------|------|:--:|
| 1 | CT01-counter-get | 链6 已产生计数 | execution/counter/CT01-counter-get.md | ✅ 正常 |
| 2 | CT02-batch-get | CT01 | execution/counter/CT02-batch-get.md | ✅ 正常 |
| 3 | CT03-reconcile | 管理端点 | execution/counter/CT03-reconcile.md | ✅ 正常 |

## 三、异常场景

| 场景 | 端点 | 预期 |
|------|------|------|
| 查询不存在的计数 | CT01 | `{"count":0}` 或 `null` (非异常) |
| 缺少 JWT | CT01 | 401 |

---
## 测试要点补充（2026-08-10，实测修正）

- **CT01-counter-get**：query 用 `targetType=1&targetId=&countType=1`（非 bizType/bizId）。
- **CT02-batch-get**：是 **POST**，body `{queries:[{targetType,targetId,countTypes:[1,2,3]}]}`。
- **CT03-reconcile**：管理端点，`X-Admin-Call` 直连 19004。
- 前置：需先有 like/favorite/comment 等社交事件才有计数。

---
## L0-L4 逐端点核对清单

### CT01-counter-get / CT02-batch-get
- [ ] L0: 有社交事件(like/fav/comment)产生计数
- [ ] L1: GET(targetType/targetId/countType) / POST(batch,queries) → 200
- [ ] L2: Redis `myxhs:counter:{targetId}` 计数 = 实际操作数
- [ ] L3: 幂等去重 / 一致性
