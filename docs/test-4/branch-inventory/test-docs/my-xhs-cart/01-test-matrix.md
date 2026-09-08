# my-xhs-cart 测试用例矩阵（L1-L4）

## L1 业务

| ID | 用例 | 请求/数据 | 预期 | 状态 |
|---|---|---|---|---|
| CA-L1-01 | 加购 | POST /api/cart/add | Redis+MQ+MySQL | ✅ |
| CA-L1-02 | 改数量 | PUT /quantity | 数量更新+同步 | ✅ |
| CA-L1-03 | 删除 | DELETE /{skuId} | 移除+同步 | ✅ |
| CA-L1-04 | 勾选/全选 | PUT /check /check-all?checked= | checked 状态 | ✅ 勾选200+全选query参数200 |
| CA-L1-05 | 购物车列表 | GET /list | 有效/失效/勾选/金额 | ✅ |
| CA-L1-06 | 匿名合并 | POST /merge | 匿名+登录合并 | ⬜ |
| CA-L1-07 | 清空 | DELETE /clear | 清空+marker | ✅ 200清空后列表空 |
| CA-L1-08 | 计数 | GET /count | 商品种类/数量 | ✅ count:2 |
| CA-L1-09 | 商品失效 | 下架 SKU 加购/展示 | 失效标记 | ⬜ |

## L2 数据

| ID | 验证点 | 证据 | 状态 |
|---|---|---|---|
| CA-L2-01 | Redis items/checked/sort | 三结构一致 | ✅ |
| CA-L2-02 | CART_TOPIC 消费 | 同步 MySQL | ✅ |
| CA-L2-03 | 事件流水 | t_cart_event | ✅ |
| CA-L2-04 | 清空屏障 | cleared marker | ⬜ |
| CA-L2-05 | Lua 原子性 | 6 个 Lua | ⬜ |

## L3 质量

| ID | 用例 | 预期 | 状态 |
|---|---|---|---|
| CA-L3-01 | 清空与加购并发 | marker 保护 | ⬜ |
| CA-L3-02 | 事件乱序 | CHECK/DELETE/ADD 同毫秒 | ⬜ |
| CA-L3-03 | Redis 丢失恢复 | cleared+对账修复 | ⬜ |
| CA-L3-04 | 对账并发 | 定时+手动 | ⬜ |
| CA-L3-05 | 超50项合并 | Feign/Lua/MQ 放大 | ⬜ |

## L4 可观测

| ID | 验证点 | 状态 |
|---|---|---|
| CA-L4-01 | 同步失败/重试/DLQ | ⬜ |
| CA-L4-02 | 对账差异指标 | ⬜ |
| CA-L4-03 | TraceId 跨 cart/MQ | ✅ |

## 已实测
- CA-L1-01/02/03/05、L2-01/02/03 ✅
- 勾选/合并/清空/商品失效/并发/Lua/对账 待专项
