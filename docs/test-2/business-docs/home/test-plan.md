# my-xhs-home 测试执行计划

> 7端点 | 链6 | BFF聚合层 (9路 Feign) + Feed已预制

---

## 一、前置准备

```bash
TOKEN=$(cat /tmp/test_token.txt)

# 确认 home 在线 (需 dev profile 以启用 H06/H07)
curl -sf localhost:19015/actuator/health >/dev/null || echo "home DOWN"

# 确认 Feed 已预制 (pre-test-init Step 8 已做)
python3 -c "
import redis; r=redis.Redis(host='21.130.247.89',port=6379,password='Xhs@2026#Redis')
print(f'inbox 10001: {r.zcard(\"myxhs:feed:inbox:10001\")}')
print(f'inbox 10002: {r.zcard(\"myxhs:feed:inbox:10002\")}')
print(f'outbox 10001: {r.zcard(\"myxhs:feed:outbox:10001\")}')
"

# 确认 product 有数据 (H03 需要)
SKU_COUNT=$(mysql -h 21.130.247.89 -P 3306 -u root -p'Xhs@2026#MySQL' -N -e "USE my_xhs_product; SELECT COUNT(*) FROM t_sku WHERE status=1;" 2>/dev/null)
echo "SKU count: $SKU_COUNT"
```

## 二、执行顺序

| 顺序 | 端点 | 依赖 | 产出文件 | 正常+异常 |
|:--:|------|------|------|:--:|
| 1 | H01-feed | Token + Feed 已预制 | execution/home/H01-feed.md | ✅ 正常 |
| 2 | H02-note-detail | 链6 已有笔记 | execution/home/H02-note-detail.md | ✅ 正常 |
| 3 | H03-product | product 有 SPU | execution/home/H03-product.md | ✅ 正常 |
| 4 | H04-user-profile | 链1 已注册 u1/u2 | execution/home/H04-user-profile.md | ✅ 正常 |
| 5 | H05-cart-agg | Token + 链3 有购物车 | execution/home/H05-cart-agg.md | ✅ 正常 |
| 6 | H06-push-inbox | Token + dev profile | execution/home/H06-push-inbox.md | ✅ 正常 |
| 7 | H07-push-outbox | Token + dev profile | execution/home/H07-push-outbox.md | ✅ 正常 |

> H06/H07 是 dev-only 测试端点，需 `-Dspring.profiles.active=dev`

## 三、异常场景

| 场景 | 端点 | 预期 |
|------|------|------|
| Feed 无关注/无数据 | H01 | `notes:[]` (非异常) |
| 不存在的 noteId | H02 | "笔记不存在" |
| 缺少 JWT | H01-H05 | 401 |
| dev profile 未激活 | H06/H07 | 404 |

---
## 测试要点补充（2026-08-10，实测修正）

- **认证**：走 gateway 19000，JWT+HMAC。
- **H06-push-inbox / H07-push-outbox**：参数是 **query**(?userId=&noteId=&publishTime= / ?authorId=&noteId=&publishTime=)；需 **dev profile**（已加入 start-all.sh）；publishTime 传当前毫秒时间戳(否则 score=0 被 feed 过滤)。
- **⚠️ H01-feed 疑似 bug**：user 10001（预置30条）与 u1 均返回空，`reverseRangeByScoreWithScores(minScore,0)` 参数可能有问题，待前端确认。
- 预置 feed 数据(user 10001/10002)引用的 noteId(10001-10030) 在 content 中**不存在**，会导致 feed 空——测试需用真实存在的笔记构造。

---
## L0-L4 逐端点核对清单

### H01-home-feed
- [ ] L0: 有 feed 数据(真实存在的笔记, 勿用预置不存在的noteId)
- [ ] L1: GET feed → 200
- [ ] L2: Redis `myxhs:feed:inbox:{userId}` → 聚合笔记详情
- [ ] L3: 一致性; ⚠️疑似bug(返回空)待确认
### H06/H07-push
- [ ] L0: dev profile
- [ ] L1: POST `?userId=&noteId=&publishTime=` → 200
- [ ] L2: Redis inbox/outbox zset 写入(zcard)
- [ ] L3: score=当前时间戳
