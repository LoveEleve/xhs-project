# my-xhs-inventory 测试执行计划

> 10端点 | 链5依赖 | 需管理员权限 + Redis桶已初始化

---

## 一、前置准备

```bash
TOKEN=$(cat /tmp/test_token.txt)

# 确认 inventory 在线
curl -sf localhost:19009/actuator/health >/dev/null || echo "inventory DOWN"

# 确认 Redis 库存桶已初始化 (pre-test-init.sh Step 6 已做)
python3 -c "
import redis; r=redis.Redis(host='21.130.247.89',port=6379,password='Xhs@2026#Redis')
for sku in [1,2,3,4,5,6,7,8,9,10]:
    t=r.get(f'inventory:{sku}:total')
    if t: print(f'SKU {sku}: total={t.decode()}')
    else: print(f'SKU {sku}: NOT INITIALIZED')
"

# 获取可用 SKU (需 product 已有)
SKU_ID=$(mysql -h 21.130.247.89 -P 3306 -u root -p'Xhs@2026#MySQL' -N -e "USE my_xhs_product; SELECT id FROM t_sku WHERE status=1 ORDER BY id LIMIT 1;" 2>/dev/null)
echo "SKU_ID=$SKU_ID"

# ADMIN_TOKEN — 管理端点需要 X-Admin-Call header
ADMIN_TOKEN="my-xhs-admin-token-2026"
```

## 二、执行顺序

| 顺序 | 端点 | 依赖 | 产出文件 | 管理 | 正常+异常 |
|:--:|------|------|------|:--:|:--:|
| 1 | I01-init-stock | SKU_ID + ADMIN_TOKEN | execution/inventory/I01-init-stock.md | ✅ | ✅ 正常 |
| 2 | I06-stock-query | I01 | execution/inventory/I06-stock-query.md | — | ✅ 正常 |
| 3 | I08-tcc-try | SKU_ID + Token | execution/inventory/I08-tcc-try.md | — | ✅ 正常 |
| 4 | I09-tcc-confirm | I08 | execution/inventory/I09-tcc-confirm.md | — | ✅ 正常 |
| 5 | I10-tcc-cancel | 先Try另一个SKU | execution/inventory/I10-tcc-cancel.md | — | ⚠️ 异常 |
| 6 | I02-pre-deduct | SKU_ID + Token | execution/inventory/I02-pre-deduct.md | — | ✅ 正常 |
| 7 | I03-confirm | I02 | execution/inventory/I03-confirm.md | — | ✅ 正常 |
| 8 | I04-release | 先Pre-deduct另一个 | execution/inventory/I04-release.md | — | ⚠️ 异常 |
| 9 | I05-reinit | ADMIN_TOKEN | execution/inventory/I05-reinit.md | ✅ | ✅ 正常 |
| 10 | I07-reconcile | ADMIN_TOKEN | execution/inventory/I07-reconcile.md | ✅ | ✅ 正常 |

> I01 已在 pre-test-init Step 6 批量执行过，链5 测试时可跳过直接测 I02+

## 三、异常场景

| 场景 | 端点 | 预期 |
|------|------|------|
| 未初始化 SKU 预扣 | I02 | "库存未初始化" |
| 库存不足 | I02 | "库存不足" |
| 重复 Try | I08 | 幂等 — 返回已有 xid |
| Cancel 不存在的 Try | I10 | "记录不存在" |
| 缺少 Admin-Call | I01/I05/I07 | 403 |

---
## 测试要点补充（2026-08-10，实测修正）

- **内部端点**（X-Internal-Call 直连 19009，不走 gateway）：
  - POST /api/inventory/preDeduct、/confirm、/release（预扣/确认/释放）
  - POST /api/inventory/tcc/try、/tcc/confirm、/tcc/cancel
  - GET /api/inventory/stock/{skuId}（库存查询）
- **管理端点**（X-Admin-Call）：
  - POST /api/inventory/init?skuId=&totalStock=&bucketCount=、/reinit（需限流间隔≥15s）
  - POST /api/inventory/internal/reconcile
- **Redis 库存桶**：`inventory:{skuId}:total`，若被 flush 需重新 init（见交接 §5.1）。
- 测试前置：链5 下单依赖库存桶存在。

---
## L0-L4 逐端点核对清单

### I01-init / I02-preDeduct / I03-confirm / I04-release
- [ ] L0: Admin/Internal token
- [ ] L1: init(preDeduct→200; 限流5/60s间隔≥15s)
- [ ] L2: Redis `inventory:{skuId}:total` 桶; 预扣/确认/释放后数量正确
- [ ] L3: 分布式(TCC) / @RateLimit
### I08-tcc-try / I09-tcc-confirm / I10-tcc-cancel
- [ ] L1: TCC三阶段 → 200
- [ ] L2: MySQL `t_tcc_fence` / `t_tcc_freeze_detail`
- [ ] L3: 幂等/悬挂/空回滚
### MQ
- [ ] L4: `INVENTORY_TOPIC` 消费无积压
