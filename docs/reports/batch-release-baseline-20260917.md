# 批量发布与容量新基线（2026-09-17，A2 修复全量生效）

## 一、批量发布结果
15 个服务全部重建（clean package）并经发布通道（release-service.sh）发布成功，健康检查 15/15=200。
- 携带修复：common 的 TraceContextHolder 静态桥接（消除每请求 Class.forName 类加载锁）
- 发布记录：product/home/cart（19:22-19:47）+ user/content/analytics/counter（20:00）+ coupon/inventory/order/payment（20:00）+ notification/im/search/gateway（20:01）
- 网关发布后 Nacos 规则正常到达（突发 200 → 50×200 + 150×429 ✅）

## 二、容量新基线（ab 20000/c=64；wrk 15s，本地单机）

| 接口 | 修复前 RPS | **修复后 RPS** | 提升 | P99(新) | 备注 |
|---|---:|---:|---:|---:|---|
| product 详情 | 1,074 | **4,871** | 4.5x | 50ms | 类加载锁修复（**无本地缓存**；27,442 为曾试装 L1 的临时值，已移除） |
| user info | 958 | **5,846** | 6.1x | 40ms | |
| note 详情 | 1,048 | **4,171** | 4.0x | 71ms | |
| comment 列表 | 1,064 | **~1,267（有评论）** | 1.2x | 189ms | **无本地缓存**（DB-bound） |
| gateway→note 详情（放行后） | 1,058 | **4,640** | 4.4x | 27ms | 代理≈0（>直连） |
| home 聚合 | 430 | **1,055** | 2.5x | 407ms | 池隔离+类加载修复；P99 略超 SLO 400ms（观察项） |
| search（8 命中） | ~392-575 | **1,448** | **2.9x** | 61ms | 调优：ES 容器 CPU 配额 2→6 核（压测中打满 202%）+ 客户端 IO 线程 4→16/连接 400 + recall 池 32/64 |

## 二·补 2：comment 调优尝试与决策（2026-09-17 晚）
- 曾试引入 Caffeine L1 首屏缓存（3s TTL+写失效）：空页 7,913 / 有评论 16,527 RPS；
- **技术决策：本地缓存不引入，已全部移除**（一致性/多实例失效/内存水位坑）。当前 comment 无本地缓存：**~1,267 RPS（有评论，DB-bound）**；
- 后续方向（不引入本地缓存）：SQL/索引优化（根评论+窗口函数两查）、共享 Redis L2（CacheHelper 既有通道）、或读写分离分担。

## 二·补：search 专项调优（2026-09-17 晚）
- 定位：服务侧线程全部 WAITING 在 ES 客户端 `BasicFuture.get`，而 **ES 容器 CPU 打满 202%（配额 2 核）**；
- 动作：① `docker update --cpus=6`（并写入 compose）；② ES 客户端 `io-thread-count 4→16`、`max-conn-total 400`、`per-route 200`；③ `recallExecutor 10/20 → 32/64`（可配）；
- 结果：**508 → 1,448 RPS（2.9x）**，P99 191 → 61ms。

## 三、结论与遗留
1. 平台原真实吞吐被类加载锁压在 ~1k RPS；修复后 **product 达 2.7 万 RPS**，全链路为原基线的 2-25 倍；
2. 新的瓶颈层浮出：~~search（ES）~~（已调优至 1,448 RPS）、**comment（DB）~2.5k RPS**、**home 聚合 P99 433ms**；
3. A1 报告中的所有数字（除 search 外）已作废，以本报告为准；SLO 基线同步更新；
4. 待办：搜索的 ES 调优（分片/refresh/查询精简）、comment 的 DB 索引/JOIN 优化、home 聚合超时与扇出收敛。

## 四、口径更新索引
- A1 容量报告：`docs/reports/capacity-20260917.md`（数字已过时，保留作"修复前下界"）
- A2 调优报告：`docs/reports/a2-jvm-tuning-20260917.md`（修复细节）
- SLO：`docs/slo/slo-and-degradation-matrix.md`（基线更新为本报告）
