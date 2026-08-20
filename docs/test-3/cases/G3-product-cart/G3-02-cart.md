# G3-02 购物车用例（P2-7 加购/购物车 + P2-8 对账）

> 组：G3 商品与购物车 | 服务：cart(19008) + product(19006) + inventory(19009) | 入口：**gateway(19000)**
> 依赖：G1 登录 + G3-01 商品（SPU/SKU 存在）
> 时间引用：矩阵 **#40**（对账锁 600s）、D 节（购物车 key 30 天 TTL）
> 前提：cart 服务 UP；xxl 任务 17（cartReconcileJob）可手动触发（矩阵 A 节）

## 代码实证（2026-08-13，G3 梳理时核实）

### 端点与安全（gateway:19000 → cart:19008）
| 端点 | 鉴权 | HMAC | RateLimit（代码实证） |
|---|---|---|---|
| POST /api/cart/add | JWT | **必须签名** | 20 次/60s `myxhs:cart:add` |
| PUT /api/cart/quantity | JWT | 必须签名 | 20 次/60s `myxhs:cart:update` |
| DELETE /api/cart/{skuId} | JWT | 必须签名 | 20 次/60s `myxhs:cart:remove` |
| PUT /api/cart/check | JWT | 必须签名 | 30 次/60s `myxhs:cart:check` |
| PUT /api/cart/check-all | JWT | 必须签名 | 10 次/60s `myxhs:cart:checkAll` |
| GET /api/cart/list | JWT | 必须签名 | 60 次/60s `myxhs:cart:list` |
| POST /api/cart/merge | JWT | 必须签名 | 10 次/60s `myxhs:cart:merge` |
| DELETE /api/cart/clear | JWT | 必须签名 | 3 次/60s `myxhs:cart:clear` |
| GET /api/cart/count | JWT | 必须签名 | 120 次/60s `myxhs:cart:count` |
| POST /api/cart/internal/reconcile | JWT+HMAC+**X-Admin-Call**（**gateway 无白名单，三重鉴权**——与 product 管理端点免 HMAC 不同）| 2 次/60s |
| POST /api/cart/internal/reconcile/user?userId= | 同上 | 10 次/60s |

### 数据结构（CartService 代码实证，Redis 三结构协同）
- `myxhs:cart:{userId}:items`（**Hash**，field=skuId，value=quantity；`{userId}` 为 hash tag 保证 Cluster 同 slot）
- `myxhs:cart:{userId}:checked`（**Set**，member=skuId）
- `myxhs:cart:{userId}:sort`（**ZSet**，member=skuId，score=加购时间戳）
- 上限：MAX_CART_SIZE=50 种 / MAX_ITEM_QUANTITY=99；TTL 30 天
- Lua 原子脚本：加购=HEXISTS/HLEN/HSET+SADD+ZADD；**超限返回 CART_LIMIT_EXCEEDED(30007)**
- 落库：写 Redis 后发 **CART_TOPIC** 事件（ADD/UPDATE/DELETE/CHECK/CHECK_ALL）→ CartSyncConsumer 异步落 `my_xhs_cart.t_cart_item`（等 1-3s）

### 购物车对账（P2-8，CartReconcileJob 代码实证）
- 锁：`myxhs:lock:cart:reconcile` **600s**（矩阵 #40）；拿不到锁跳过
- 场景 1：Redis 有 + MySQL 无 → **INSERT MySQL**（MQ 丢消息兜底；补录 checked 状态）
- 场景 2：Redis 有 + MySQL 有 + 数量/勾选不一致 → **UPDATE MySQL**
- 场景 3：Redis 无 + MySQL 有 → **DELETE MySQL**（**仅 itemsKey 存在时执行**——key 不存在视为"可能 Redis 故障丢数据"，跳过防双份全丢）
- 纯 Redis 用户补充扫描：`SCAN myxhs:cart:*:items` 枚举（P2-8 新增，MQ 落库丢失的 Redis-only 用户也补录）
- 触发：**xxl#17（cartReconcileJob，cron `0 0 * * * ?` = 每小时——实际核对 xxl_job_info：id=17, job_group=9；交接文档/README 写"每天4点"有误）**/ 管理端点 `/internal/reconcile`（全量异步）/ `/internal/reconcile/user`（单用户同步）
- 对账**以 Redis 为准**（Redis 权威模型，与关注对账一致）

### 错误码
30006 购物车商品不存在 / 30007 购物车数量上限 / 40002 参数 / 40202 限流 / 403 无权访问管理接口

### CartListVO 结构（代码实证）
- items（CartItemVO：**skuId/spuId/name/price/originalPrice/quantity/checked/specs/image/valid/invalidReason/addedAt**）
- checkedCount（**选中且有效**的商品数量合计）/ checkedAmount（**sum(price*qty)，仅选中且有效**）
- totalCount（**itemsMap.size() 品种数**，含无效项）/ allChecked（**只看有效商品**：validCount>0 且全部勾选）
- **排序**：按 addedAt（sort ZSet score）**倒序**（最新加购在前）
- **价格/名称来源**：Feign 调 product `/api/product/sku/batch`（**X-Internal-Call**）批量取 SKU——**product 批量接口已过滤下架，下架 SKU 在 cart 列表 → valid=false + invalidReason"商品已下架"**
- **P2-7 恢复（关键新增）**：itemsMap 为空时 → **restoreCartFromDb（MySQL 恢复回写 Redis：items/checked/sort 三结构重建）**——Redis 丢失场景兜底
- **脏数据防御**：skuId/quantity 非数字 key 跳过并 warn

### merge 匿名购物车（CartMergeRequest）
- items List ≤50（@Size）；MergeItem：skuId/quantity
- Lua 幂等：已有商品取 max（**返回值≥10000 表示已有**，实际数量=返回值-10000）；新商品 SADD 默认勾选 + ADD 事件；超限跳过
- **已有商品发 UPDATE（checked=null 不改勾选）**——避免覆盖 Redis 勾选态

## 前置准备（执行时记录快照）
```python
from testlib import *
# 1. 注册用户 g3c_（购物车操作者）
# 2. G3-01 已建 SPU+2 SKU（记 {sku1}/{sku2}）
# 3. 基线：SELECT COUNT(*) FROM my_xhs_cart.t_cart_item（记数）
# 4. Redis：无该用户 cart key（DEL myxhs:cart:{uid}:* 清理）
```

## 用例清单

### G3-02-01 加购全链路（核心）
- **入口**：`POST /api/cart/add` body=`{"skuId":{sku1},"quantity":2}`（g3c_ 签名；cart 全部写操作需 HMAC 签名）
- **L1 断言**：200
- **L2 数据验证**：
  ```bash
  HGET myxhs:cart:{uid}:items {sku1}        # → "2"
  SISMEMBER myxhs:cart:{uid}:checked {sku1} # → 1（默认勾选）
  ZSCORE myxhs:cart:{uid}:sort {sku1}       # → 加购时间戳
  TTL myxhs:cart:{uid}:items                # ≈ 30 天（2592000，refreshTTL 实证）
  # MySQL 落库（等 1-3s MQ，CART_TOPIC → cart-sync-consumer-group）：
  SELECT sku_id,quantity,checked FROM my_xhs_cart.t_cart_item WHERE user_id={uid} AND sku_id={sku1}
  # → quantity=2 checked=1（UPSERT 语义，uk_user_sku 唯一索引）
  # 列表接口（需签名）：
  GET /api/cart/list → items 含 sku1 qty=2 name/price（Feign product 聚合）；checkedAmount=price*2；totalCount
  GET /api/cart/count → `{"count": N}`（**Map 结构，品种数=HLEN；与 list.totalCount 一致**）
  ```
- **🔍 人工观察**：RocketMQ CART_TOPIC 消费进度；SkyWalking cart→MQ→cart 消费链路
- **注意**：列表依赖 product `/api/product/sku/batch`（X-Internal-Call）——若 product 服务 INTERNAL_TOKEN 缺失 → 列表全部 valid=false"商品信息获取失败"（#79-1 同款坑）

### G3-02-02 加购参数校验（负面）
- ① 缺 skuId → **40002**；② quantity=0 → **40002**（@Min 1）；③ quantity=100 → **40002**（@Max 99）
- **L2**：t_cart_item 无新增；Redis items 无变化

### G3-02-03 加购已存在 SKU（累加）
- 再次 add 同 sku1 quantity=3 → HGET = 5（累加）；t_cart_item 仍 1 行 quantity=5（等 1-3s）

### G3-02-04 数量上限（99 截断 + 50 种）
- ① add 同 sku1 quantity=99（当前 5 → 期望 104）→ **200 成功，数量截断为 99**（cart_add.lua 实证：HINCRBY 后 >99 → HSET 截断，**非报错**）；HGET=99；t_cart_item quantity=99（等 1-3s）
- ② 加购 51 种不同 SKU（循环 add，需 51 个 sku——G3-01 批量建或用同一 spu 多 sku）→ 第 51 种新商品 → **30007"购物车商品数量已达上限"**（cart_add.lua：exists==0 且 HLEN≥50 → 返回 -1）
- **注意**：50 种上限用例会污染购物车——测完 clearCart 清理；② 中已存在商品继续加不触发上限（只新商品检查）

### G3-02-05 修改数量
- `PUT /api/cart/quantity` body=`{"skuId":{sku1},"quantity":10}` → 200；HGET=10；t_cart_item quantity=10
- 改不存在 sku → **30006**（购物车商品不存在；cart_update_quantity.lua HEXISTS 校验）
- **参数校验**：quantity=0 → **40002**（@Min 1）；quantity=100 → **40002**（@Max 99——**updateQuantity 是校验拒绝，与 add 的 Lua 截断不同**）；缺 skuId → 40002

### G3-02-06 勾选/取消勾选
- `PUT /api/cart/check` body=`{"skuId":{sku1},"checked":false}` → 200；SISMEMBER=0；t_cart_item checked=0（等 1-3s，CHECK 事件）
- `PUT /api/cart/check-all` body=`{"checked":true}` → 200；全部勾选；再 false → 全部取消（Lua 原子 DEL+SADD，防并发竞态）
- **L2 细节（代码实证）**：CHECK 事件消费时 MySQL 无该行 → **自动补建 quantity=1 + checked 状态**（CartSyncConsumer:225）；CHECK_ALL 按 cutoffTime 只更新事件前修改的行（C-05 保护，防旧 CHECK_ALL 覆盖新 CHECK）
- **checkItem 不存在 sku** → **30006**（cart_check_item.lua HEXISTS 校验）

### G3-02-07 购物车列表聚合（L2 关键）
- 前置：2 个 sku（sku1 qty=10 勾选、sku2 qty=1 取消）
- `GET /api/cart/list` → 断言：
  ```
  totalCount = 11（品种数量合计 = itemsMap.size()）
  checkedCount = 10（选中且有效）
  checkedAmount = 10 × price1（sum(price*qty)，仅选中有效）
  allChecked = false（sku2 未勾选）
  items 顺序：sku1 在前（最新加购在前，sort ZSet 倒序）
  含 sku 详情：name/price/specs/image（Feign product 批量聚合）
  ```
- **下架商品在列表**：将 sku1 下架（product status=0）→ `GET /api/cart/list` → **sku1 valid=false + invalidReason="商品已下架"**（product 批量接口过滤后 cart 标记失效——**代码实证 CartItemVO.valid 逻辑**）；checkedAmount 不再含 sku1；**恢复上架后 valid=true**
- **Redis 丢失恢复（P2-7）**：`DEL myxhs:cart:{uid}:*`（items/checked/sort 全删，模拟 Redis 丢失）→ `GET /api/cart/list` → **从 MySQL 恢复**（restoreCartFromDb 回写三结构，代码实证）→ **L2 验证**：items/checked/sort 重建 + 列表仍正确 + **后续对账场景 3 不再误删**（key 已重建）

### G3-02-08 删除商品 + 清空
- `DELETE /api/cart/{sku1}` → 200；HEXISTS=0；SISMEMBER=0；t_cart_item 行删除（等 1-3s，DELETE 事件）
- 删不存在 sku → **200（Lua SREM 幂等，无报错——代码实证 removeFromCart 无存在性校验）**（记录：与 updateQuantity 的 30006 不同）
- `DELETE /api/cart/clear` → 200；三 key 均不存在；t_cart_item 该用户行删除（等 1-3s，CLEAR 事件）
- **注意**：clear 后 itemsKey 不存在——**后续对账场景 3 会跳过删除**（防误删，代码实证）

### G3-02-09 匿名购物车合并（merge）
- **代码实证**：**无独立匿名 key**——merge 的 items 来自请求体（CartMergeRequest），匿名侧由前端暂存（C-13 注释"合并后匿名购物车由前端清除"）
- `POST /api/cart/merge` body=`{"items":[{"skuId":{sku2},"quantity":2}]}` → 200
- **L2**：HGET sku2=2；t_cart_item 补录（ADD 事件，checked=1）
- 已有商品 merge（qty 更小）→ **保持原值（max 语义）**；qty 更大 → 更新
- **幂等**：重复 merge 同 items → 结果一致（max 幂等实证）
- **参数校验**：items 超 50 条 → **40002**（@Size max=50）；items 空 → **200 无操作**（代码实证：null/empty return）；**MergeItem 校验**：skuId=0 → 40002（@Min 1）、quantity=0 → 40002、quantity=100 → 40002（@Max 99）
- 超限项（购物车满 50）→ 跳过（日志"[购物车] 合并跳过（已满或上限触发）"）

### G3-02-10 对账修复（P2-8，xxl#17 + 管理端点）
- **前置**：构造不一致：
  ```
  # 场景1：Redis 有 + MySQL 无（模拟 MQ 丢消息）——直删 MySQL 行
  DELETE FROM my_xhs_cart.t_cart_item WHERE user_id={uid}
  # 场景2：数量不一致
  UPDATE my_xhs_cart.t_cart_item SET quantity=999 WHERE user_id={uid} AND sku_id={sku1}
  # 场景3：Redis 无 + MySQL 有（模拟清空但 MQ 丢 DELETE）
  DEL myxhs:cart:{uid}:items / checked / sort
  ```
- **触发**：xxl admin 手动触发 id=17（**jobGroup=9**；矩阵 A 节：login cookie → jobinfo/trigger）
- **L2 断言**（以 Redis 为准）：
  ```
  # 场景1 → INSERT（t_cart_item 恢复 Redis 中的行）
  # 场景2 → UPDATE（quantity 恢复 Redis 值）
  # 场景3 → 若 itemsKey 存在才 DELETE；itemsKey 被 DEL 后 → **跳过**（防误删实证——**保留 MySQL 行**）
  # 日志：[购物车对账] 补录MySQL / 修复 / 删除MySQL残留
  # 纯 Redis 用户：SCAN myxhs:cart:*:items 枚举补录（P2-8 新增——构造"无 MySQL 行的纯 Redis 用户"验证）
  ```
- **锁验证**：触发瞬间 EXISTS myxhs:lock:cart:reconcile=1（600s TTL），结束后=0
- **替代**：管理端点 `/internal/reconcile`（X-Admin-Call：`tokens.env ADMIN_TOKEN`，**异步**返回"已触发"）或 `/internal/reconcile/user?userId=`（单用户，同步）
- **注意**：场景 3 用 clear 前快照——先建场景 1/2 的不一致，**对账后**再清空购物车做场景 3（避免 key 重建干扰）

### G3-02-11 对账锁并发（矩阵 #40 审查）
- **操纵**：`SET myxhs:lock:cart:reconcile 1 EX 600` → 触发 xxl#17 → 日志"已有实例执行中，跳过"（锁互斥实证）
- **恢复**：DEL 锁

### G3-02-12 管理端点鉴权（X-Admin-Call）
- **代码实证**：`/api/cart/internal/reconcile` 及 `/reconcile/user` **不在 gateway 任何白名单**（JWT/hmac 均无）→ 走 gateway 需 **JWT + HMAC 签名 + X-Admin-Call** 三重（与 product 管理端点免 HMAC 不同——对照）
- ① 无 X-Admin-Call → **403"无权访问管理接口"**
- ② 错误 token → 403；正确 token（ADMIN_TOKEN）→ 200（reconcile 异步返回"已触发"；reconcile/user 同步）
- ③ **负向**：无 JWT/无签名 → 401/403（gateway 层先拦）
- ④ **直连 cart 服务(19008)**：绕过 gateway 带 X-Admin-Call → 200（端口信任模型——对照 G1-06-09）
- **旧 token（P-B4 后）** → 403（若适用）

### G3-02-13 限流（每个端点 60s 窗口）
- ① add 21 次（不同 sku 或同 sku 快速）→ 第 21 次 **40202**；`ZCARD myxhs:cart:add:CartController:addToCart:{uid}`=20
- ② list 61 次 → 第 61 次 40202（60 上限）
- **清理**：DEL 对应限流 key（每个端点独立）

### G3-02-14 无签名购物车操作 → 403
- add 带 JWT 无签名头 → **403**（对照 G2-01-05 机理）

### G3-02-15 不存在的 SKU 加购（T-120 已修）
- **代码实证（T-120 修复后）**：CartService.addToCart 加购前 `skuExists` 校验（Feign getSkuDetail，product 不可用降级放行）→ add 不存在 skuId（999999999999）→ **30001"商品不存在或未上架"**（原行为：200 成功幽灵条目 + 落 MySQL）
- **L2**：Redis items 无幽灵条目；MySQL 无幽灵行；merge 幽灵同理跳过
- **登记历史**：修复前（2026-08-16 前）幽灵条目 valid=false"商品信息获取失败"由列表层兜底——已升级为入口拦截

### G3-02-17 下架 SKU 加购行为（T-120 后：保持 200）
- **前置**：G3-01 中 sku2 已下架（status=0）或本用例先下架
- **入口**：add 下架 sku2 → **200 成功**（T-120 校验走 getSkuDetail 不过滤 status——只拦"不存在"，下架 SKU 可加购）
- **L2（运行态实证）**：列表 sku valid=false + invalidReason"商品已下架"/"商品信息获取失败"（product batch 过滤 SKU.status 后 cart 标记失效）；checkedAmount 不含；G5 下单层有 SPU 下架拦截（T-060 30003）

### G3-02-16 乱序事件防护（C-05，代码审查级）
- **代码实证**：CartSyncConsumer **全部事件均有时间戳乱序保护**——upsertCartItem/updateCheckedStatus/deleteCartItem 用 `!isBefore` 边界（dbTime >= eventTime 跳过旧事件）；clearCartItems 按 cutoffTime 删除；checkAllItems 只更新事件前修改的行
- **投递乱序构造不实做**（Broker 侧操纵复杂）——审查结论：C-05 时间戳 + uk_user_sku 唯一索引 UPSERT 双保险（注释实证）
- **运行态验证**：加购 → 立即修改数量 → 等 1-3s → t_cart_item quantity = 最终值（非中间值）
- **DELETE 乱序边界**：先 DELETE 再加购同 sku（快速连续）→ t_cart_item 最终存在且数量正确（旧 DELETE 被时间戳保护跳过）

## 执行记录
| 用例 | 时间 | 结果 | 问题/备注 |
|---|---|---|---|
| G3-02-01~17 | 2026-08-15 12:07~12:15 | ✅ 17/17 | 回归（Task9 后）；对账三场景/锁互斥/P2-7恢复/T-047增强(见README R3)/乱序防护 全部验证 |

## 断言关键词速查
- 200 成功 / 30006 购物车商品不存在 / 30007 数量上限 / 40002 参数 / 40202 限流 / 403 管理鉴权
- 关键 L2：items/checked/sort 三 key、t_cart_item（等 MQ 1-3s）、TTL 30 天、对账三场景、锁 600s

## 深度 REVIEW 补充（2026-08-13 第三轮，二次全量核对）
### 本轮新增 L0/L1 已核
- ✅ **xxl#17 cron=每小时**（`0 0 * * * ?`，job_group=9）——交接/README"每天4点"有误（本轮修正）
- ✅ **ES product_index=SPU 粒度**（t_sku 变更消费者忽略；mapping 含 status——下架同步可验证；price=首 SKU 价非最低价，登记观察）
- ✅ **T-042 deleted 语义=ES status=-1 标记**（非物理删文档；搜索时过滤）
- ✅ **product 服务无鉴权拦截器**：服务间 Feign 直连公开（search→product）；仅 gateway 入口强 JWT——端口信任模型（G3-01-11⑥）
- ✅ **search→product getSpuDetail 无 X-Internal-Call 配置**（product 该端点无内部校验，正常）
- ✅ **CartSyncConsumer 全事件 C-05 时间戳保护**（upsert/updateChecked/delete/clear/checkAll 全覆盖）；CHECK 消费时 MySQL 无行 → 自动补建 quantity=1
- ✅ **MergeItem @Min/@Max 校验**（skuId≥1、quantity 1-99）
- ✅ **count 接口返回 `{"count": N}`**（HLEN 品种数）
- ✅ **Redis 序列化安全**：activateDefaultTyping(NON_FINAL)——RedisCacheData<SpuDetailVO> 带 @class 类型信息，无 T-045 同款风险（L1 推断，L2 运行时观察）
- ✅ **cart 无任何 gateway 白名单**（JWT+HMAC 全要）；管理端点三重鉴权

### 新增用例（第三轮）
- **G3-02-17 下架 SKU 加购**：add 无 status 校验 → 200；列表 valid=false"商品已下架"（登记观察：加购无状态拦截）

### 待 L2 运行态确认（收敛后）
- [ ] product_index 文档实际结构（SPU 粒度 + status=-1 标记语义）
- [ ] 布隆过滤器未加载（降级）时空值缓存行为（TTL 5min）
- [ ] cart 列表 Feign product 超时降级（fallback → valid=false"商品信息获取失败"）——可用停 product 模拟（高风险，慎做）
- [ ] 对账场景 3 在 itemsKey 缺失时跳过（代码已实证，运行态验证）

### 已知风险
- 列表依赖 product batch（X-Internal-Call）——product 未带 token 时列表全失效（#79-1）
- 对账以 Redis 为准——构造不一致后立即触发，避免**每小时**定时任务（cron 每小时整点）竞争
- 50 种上限用例污染购物车——clear 清理后再继续
