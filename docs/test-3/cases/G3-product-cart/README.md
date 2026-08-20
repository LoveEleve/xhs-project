# G3-product-cart — 商品与购物车

> 服务：product(19006) + cart(19008) + inventory(19009) + search(19016) | 入口：**gateway(19000)**
> 依赖：G1 登录（token/hmacSecret）
> 时间引用：矩阵 #32（HotSkuDetector 30s）、#40（对账锁 600s）、D 节（购物车 TTL 30 天、产品缓存 1h）

## 回归记录（2026-08-15，Task9 后全量回归）

**G3-01（15/15 ✅）+ G3-02（17/17 ✅）= 32/32 全绿**。逐用例执行（禁止批量），测试用户 g3r_766027，数据已清理（t_spu/t_sku/t_cart_item/ES product_index 全 0）。

### 第二轮全量回归（2026-08-15，T-106 修复后重跑 32 用例）
- **G3-01 15/15 ✅ + G3-02 17/17 ✅ = 32/32 全绿**（测试用户 g3r_772917）
- **新发现并修复 T-109【P1】**：`t_cart_item` 时间列秒级 datetime 与纳秒级事件时间戳不匹配 → 秒边界内连续写时 C-05 乱序保护误判跳过 UPDATE → MySQL 丢更新（Redis=9/MySQL=4 实测复现）。修复：t_cart_item/t_cart_event 四列改 datetime(3)，3/3 轮秒边界验证通过（详见 ISSUES.md）
- 双限流层验证：gateway Sentinel 路由限流 429 毫秒返回零悬挂（T-106 修复实证）+ cart 自身 per-user 限流 40202
- T-107（add 已存在商品 MySQL checked 强制 1）/T-108（P2-7 恢复无 TTL）观察项复测确认仍存在，登记待处理

### 回归发现（补测后：1 项真实缺陷 + 3 项文档修正/确认）
| # | 内容 | 结论 |
|---|---|---|
| **T-106** | **gateway Sentinel 限流触发时请求悬挂 30s**（sentinel-spring-webflux-adapter 1.8.8 与 Spring 6 不兼容 NoSuchMethodError；项目漏配 WebFluxCallbackManager.setBlockHandler）| **已修复**：RateLimitFilter 补注册，80 并发 30×429 全 40ms 返回零悬挂（登记 ISSUES.md）|
| R1 | G3-02-06 check-all 参数是 **query**（`?checked=true`，@RequestParam），非 body——文档写 body 有误 | 文档修正 |
| R2 | G3-02-07 totalCount=**品种数**（itemsMap.size()=2），文档示例"totalCount=11"是数量合计混入 | 文档修正 |
| R3 | G3-02-17 **T-047 已增强**：cart valid 防御层现检查 spuStatus（CartService.java:407-410 注释）——SPU 下架 → 列表 valid=false"商品已下架"（文档记录"仍 valid=true"是旧行为，运行态实测已修） | 代码比文档新 |

### 补测记录（第二轮：代码实证对照，补 9 项遗漏 + 发现 2 项观察项）
| 补测项 | 结果 |
|---|---|
| createSku SPU 不存在 → 30001 | ✅ |
| 更新 name → ES 同步 | ✅ |
| 空值缓存路径（布隆含 id + DB 无 → null TTL 300s）| ✅ |
| listSpus?pageNum=0 钳制为 1 | ✅ |
| CHECK 事件 MySQL 无行 → 自动补建 1/1 | ✅ |
| 对账场景 2 checked 不一致修复 | ✅ |
| 加购已存在商品 ZADD NX 排序不变 | ✅ |
| T-047 spuStatus 透传（sku/list + batch）| ✅ |
| 下架 SPU 后 SKU 列表仍返回 | ✅ |
| **T-107【观察】** add 已存在商品 → MySQL checked 强制改 1（Redis 不变）——不一致源头 | 登记 ISSUES |
| **T-108【观察】** P2-7 恢复后三 key TTL=-1（无 expire）| 登记 ISSUES |

### 回归验证摘要（L0/L1/L2 关键实证）
- 幂等 40201（10s 窗口内同参数）、窗口过期后 200 建重复行（G3-01-14）
- 写端点限流 5/60s：createSpu 第 6 次 40202、updateSpuStatus 第 6 次 40202（ZCARD=5）
- 逻辑删除 T-042：ES status=-1 标记（非物理删）；SQL 直改 deleted 后详情需清缓存才 30001（缓存旧值属预期）
- 多级缓存：布隆拦截 30001 不写缓存、逻辑过期旧值、删缓存新值、TTL=7200s、分类树 2h 缓存一致性
- 对账 P2-8：场景1 补录 / 场景2 修复（999→8）/ 场景3 itemsKey 缺失跳过删除；锁互斥"已有实例执行中，跳过"（xxl#17 触发）
- P2-7：Redis 丢失后 list 从 MySQL 恢复三结构重建
- HotSku：preDeduct 内部接口 403 外部验证、未 init → 40002"库存未初始化"（完整扩容归 G5）
- 乱序防护：加购→改数量最终=5（非中间值）；DELETE→加购最终存在数量正确
- 幽灵 SKU：add 200 成功、列表 valid=false"商品信息获取失败"（登记观察：加购无存在性校验）

### 补测记录（首轮"全绿"后用户质疑复盘——4 处子场景遗漏补测）
| 补测项 | 结果 |
|---|---|
| G3-02-10 **纯 Redis 用户 SCAN 补录**（P2-8）：Redis 有 + MySQL 无 → 全量对账 xxl#17 → 补录 MySQL（日志"补录MySQL: qty=2"）| ✅ |
| G3-02-09 **merge 超限跳过**：满 50 种后 merge 新商品 → 200 跳过不加入（日志"合并跳过（已满或上限触发）"，HLEN 保持 50）| ✅ |
| G3-02-13 **list 第 61 次 40202 明确捕获**：修复 T-106 前 1 次 30s 悬挂（第 51 次）→ 定位缺陷；修复后 61 次中 60×200+1×40202 全毫秒级 | ✅ |
| G3-01-03 **库存未 init 响应**：实际 30002"库存记录不存在"（非文档"initialized=false"结构，语义一致）| 文档以运行态为准（R6）|

## 业务范围
SPU/SKU 管理 + 多级缓存（布隆/Redis 逻辑过期/DB）+ canal→ES（product_index）+ 购物车（P2-7 加购/勾选/合并）+ 对账（P2-8 cartReconcileJob）

## 归属定时/联动任务
- cartReconcileJob（xxl#17，**每小时** cron `0 0 * * * ?`，job_group=9；管理端点可手动触发全量/单用户）
- product 索引同步：canal product_instance（t_spu/t_sku）→ PRODUCT_INDEX_TOPIC → search ProductIndexSyncConsumer → ES product_index
- HotSkuDetector（inventory，30s 窗口/阈值100）
- **T-042 已修**：product 逻辑删除 ES 同步

## 用例文档
- **G3-01-product.md**：SPU/SKU CRUD + 多级缓存 + 布隆 + canal→ES + HotSkuDetector（**15 用例**，三轮深度 REVIEW：管理端点 X-Admin-Call、读接口 gateway 需 JWT/服务间公开、下架详情 30001、HotSku 窗口 10s、ES=SPU 粒度、T-042=status=-1 标记等）
- **G3-02-cart.md**：加购/购物车 P2-7 + 对账 P2-8 + 管理端点（**17 用例**，三轮 REVIEW：P2-7 恢复、valid 标记、add 99 截断、C-05 全事件保护、下架 SKU 加购、xxl#17 每小时 cron）

## 关键数据关注矩阵（代码实证 2026-08-13）
| 用例域 | Redis key | MySQL | MQ |
|---|---|---|---|
| SPU 详情 | `myxhs:product:spu:{id}`（逻辑过期30min+物理TTL 2h）、布隆 `myxhs:product:bloom:spu` | `my_xhs_product.t_spu` | PRODUCT_INDEX_TOPIC（canal）|
| SKU | — | `t_sku` | 同上（t_sku 也在 canal 监听）|
| 购物车 | `myxhs:cart:{uid}:items`(Hash)/`checked`(Set)/`sort`(ZSet)，TTL 30 天 | `my_xhs_cart.t_cart_item` | CART_TOPIC（ADD/UPDATE/DELETE/CHECK）|
| 对账 | `myxhs:lock:cart:reconcile`（600s） | t_cart_item（以 Redis 为准修复）| — |
| 热点 | `inventory:hot:window:{skuId}`（ZSet，窗口10s/阈值100/TTL30s） | — | — |

## 执行纪律（G1/G2 教训，先扫 pitfalls #79）
- 服务重启必须带 INTERNAL_TOKEN/ADMIN_TOKEN（#79-1）
- 限流/幂等窗口跨用例共享——执行前 DEL 限流 key（#79-3）
- **product 读接口需 JWT（不在 JWT white-list）、免 HMAC；写接口需 JWT+X-Admin-Call、免 HMAC；sku/batch 需 X-Internal-Call（内部接口）**
- **cart 全部端点需 JWT+HMAC 签名（无任何白名单）；管理端点再加 X-Admin-Call**
- 分页 total 为字符串（R4）
- IndexRebuildJob 跨库前缀 `my_xhs_product.t_spu`（#37 教训）
- 矩阵 #32 修正：HotSkuDetector 窗口 10s（非 30s）、触发点=preDeduct 内部接口
