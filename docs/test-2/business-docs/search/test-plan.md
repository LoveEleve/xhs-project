# my-xhs-search 测试执行计划

> 17端点 | 链2+链6依赖 | ES索引需有数据

---

## 一、前置准备

```bash
TOKEN=$(cat /tmp/test_token.txt)

# 确认 search 在线
curl -sf localhost:19016/actuator/health >/dev/null || echo "search DOWN"

# 确认 ES 连通
curl -sf -u elastic:Xhs@2026#Elastic http://21.130.247.89:19200/_cluster/health | python3 -c "import json,sys;print(json.load(sys.stdin)['status'])"

# 确认 ES 索引有数据 (链2 product + 链6 note 经 Canal 同步)
curl -sf -u elastic:Xhs@2026#Elastic "http://21.130.247.89:19200/_cat/indices?v" 2>/dev/null | grep -E "note_index|product_index|suggest_index"

# 确认 Canal 管道运行
curl -sf http://21.130.247.89:11111 >/dev/null 2>&1 && echo "Canal OK" || echo "WARNING: Canal DOWN — ES索引不同步"

# ADMIN_TOKEN for 管理端点
ADMIN_TOKEN="my-xhs-admin-token-2026"
```

## 二、执行顺序

| 顺序 | 端点 | 依赖 | 产出文件 | 管理 | 正常+异常 |
|:--:|------|------|------|:--:|:--:|
| 1 | S01-search-note | ES note_index 有数据 | execution/search/S01-search-note.md | — | ✅ 正常 |
| 2 | S02-search-product | ES product_index 有数据 | execution/search/S02-search-product.md | — | ✅ 正常 |
| 3 | S03-suggest | ES suggest_index 有数据 | execution/search/S03-suggest.md | — | ✅ 正常 |
| 4 | S04-history | Token | execution/search/S04-history.md | — | ✅ 正常 |
| 5 | S05-clear-history | Token + S04 | execution/search/S05-clear-history.md | — | ✅ 正常 |
| 6 | S06-delete-history | Token + S04 | execution/search/S06-delete-history.md | — | ✅ 正常 |
| 7 | S07-rebuild-index | ADMIN_TOKEN | execution/search/S07-rebuild-index.md | ✅ | ✅ 正常 |
| 8 | S08-hot-search | Token | execution/search/S08-hot-search.md | — | ✅ 正常 |
| 9 | S09-record-keyword | Token | execution/search/S09-record-keyword.md | — | ✅ 正常 |
| 10 | S10-pin | ADMIN_TOKEN | execution/search/S10-pin.md | ✅ | ✅ 正常 |
| 11 | S11-unpin | ADMIN_TOKEN + S10 | execution/search/S11-unpin.md | ✅ | ✅ 正常 |
| 12 | S12-block | ADMIN_TOKEN | execution/search/S12-block.md | ✅ | ✅ 正常 |
| 13 | S13-unblock | ADMIN_TOKEN + S12 | execution/search/S13-unblock.md | ✅ | ✅ 正常 |
| 14 | S14-snapshot | ADMIN_TOKEN | execution/search/S14-snapshot.md | ✅ | ✅ 正常 |
| 15 | R01-recommend-feed | Token + ItemCF数据 | execution/search/R01-recommend-feed.md | — | ✅ 正常 |
| 16 | R02-similar | noteId | execution/search/R02-similar.md | — | ✅ 正常 |
| 17 | R03-behavior | Token | execution/search/R03-behavior.md | — | ✅ 正常 |
| 18 | R04-compute | ADMIN_TOKEN | execution/search/R04-compute.md | ✅ | ✅ 正常 |

## 三、异常场景

| 场景 | 端点 | 预期 |
|------|------|------|
| ES 无数据搜索 | S01 | 结果可能为空 (非异常) |
| 缺少 JWT | S01/S02/S08 | 401 |
| 缺少 Admin-Call | S07/S10/S12 | 403 |
| Canal DOWN | S01 | 搜索不到最新数据 (旧数据仍可搜) |

---
## 测试要点补充（2026-08-10，实测修正）

- **认证**：走 gateway 19000。S09 记录/搜索历史(写)需 JWT+HMAC；S10-S13(置顶/屏蔽)需 JWT+**Admin**；读(搜索/热搜/建议)JWT 即可。
- **S03-suggest**：参数是 `prefix`（非 keyword）。
- **S09-record-keyword**：keyword 是 **query** 参数。
- **S14-snapshot**：参数是 `date=yyyy-MM-dd`。
- **S06-delete-history 中文路径+HMAC 有工具边界问题**（签名串对 URL 编码路径不友好）。
- **中文 keyword 需 URL 编码**（否则 400）。
- **S07-rebuild-index / R04-compute**：Admin；/api/search/index/rebuild 触发全量重建(曾修复跨库 t_spu 问题)。
- **R03-behavior**：body{noteId,behaviorType:1,duration}，behaviorType 是 int。

---
## L0-L4 逐端点核对清单

### S01/S02 搜索
- [ ] L0: ES 索引有数据(S07重建)
- [ ] L1: GET note/product → 200 total>0; 中文keyword需URL编码
- [ ] L2: ES `note_index`/`product_index` 命中
- [ ] L3: 性能(ES)

### S08-hot / S09-record / S10-13 热搜
- [ ] L1: record(query keyword)→200; hot→列表; pin/block(Admin)→200
- [ ] L2: Redis 热搜榜; 置顶优先
- [ ] L3: 一致性

### S07-rebuild-index
- [ ] L1: POST (Admin) → 200
- [ ] L2: ES 索引文档数>0
- [ ] L3: 跨库查询(t_spu→my_xhs_product)
