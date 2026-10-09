# 缓存失效台账（2026-09-27）

> 来源：第五轮"缓存失效时序"拷打（`distributed-semantics-grilling-20260927.md` §9）。
> 用途：一页看清**每个缓存的写方/读方/TTL/失效路径/兜底**，防止再出"幽灵缓存/兜底单点"类问题。
> 维护约定：新增缓存必须在本台账登记一行，并明确失效路径与兜底。

## 一、缓存型 Key

| Key | 服务 | 写方 | 读方 | TTL | 失效路径 | 兜底 |
|---|---|---|---|---|---|---|
| `myxhs:user:info:{userId}`（RedisKeyConstants.USER_INFO） | user | 更新用户信息（afterCommit） | getUserFromCache | 30min + 随机抖动（CacheHelper） | `delayDoubleDelete`（立即删 + 延迟二删） | 删除重试 3 次 → `CACHE_EVICT_TOPIC`（user 消费组） |
| `RedisKeyConstants.NOTE_DETAIL` 笔记详情 | content | 发布/编辑/删除/审核（afterCommit） | NoteService 详情读 | 30min + 抖动（CacheHelper 默认） | `delayDoubleDelete`（5 处） | 同上（content 消费组，2026-09-27 补齐） |
| `RedisKeyConstants.PRODUCT_SPU` 商品详情 | product | 更新 SPU / 上下架（afterCommit） | getSpuDetail（布隆→逻辑过期→DB 锁+双重检查→空值） | 逻辑过期 30min + 物理 TTL 2h | 立即删 + 1s 二次删 | **2026-09-27 补齐**：删除失败发 `CACHE_EVICT_TOPIC`（product 消费组） |
| `myxhs:product:category:tree` 类目树 | product | 冷启动/预热构建 | CategoryService | 2h | **无主动失效**（TTL-only） | 依赖 TTL + 预热 |
| `myxhs:search:suggest:cache:*` 搜索建议 | search | SuggestService | 建议接口 | 1h（`search.suggest.cache-ttl-seconds`） | **无主动失效**（TTL-only） | 依赖 TTL |
| 空值占位（`__CACHE_NULL__`） | user/content/product | CacheHelper/自研 | 同上 | 2min（防穿透） | 到期重查 | — |

## 二、权威数据（非缓存，勿当缓存失效处理）

| Key | 服务 | 性质 |
|---|---|---|
| `inventory:{sku}:total`、`inventory:{sku}:bucket:*`、`inventory:bucket:count:*` | inventory | **L1 权威库存**（写路径 preDeduct/release 依赖）；Canal 消费者**回声保护**：UPDATE/INSERT 跳过，仅 DELETE 清；out-of-band 改动走 `/api/inventory/reinit` |
| 购物车 Hash / `t_cart_event` | cart | Redis 为在线态、MySQL 事件表对账 |
| feed 收件箱 ZSet（7d）、发件箱 | home | 业务结构（写扩散），裁剪由 FeedCleanupJob |
| `myxhs:im:route:*`（90s）、未读 Hash | im | 业务状态（TTL 心跳续期） |
| `myxhs:notification:*` 未读/聚合 | notification | 业务状态 + reconcile 对账 |
| 幂等标记（24h）、版本 Lua、事件序号 | 多个 | 幂等/乱序防护，非缓存 |

## 三、幽灵键与已知登记

| Key | 状态 |
|---|---|
| `myxhs:order:info:{orderId}` | **只删不读的死键**（9 处 DEL、无写入方）——项目已有登记（T-031/T-066 同族"登记观察"，见 `docs/test-3/cases/G5-trade/G5-01-order.md`）；本台账复核确认，维持"登记观察"，未清理 |

## 四、兜底通道（CACHE_EVICT_TOPIC）消费组清单

| 消费组 | 服务 | 备注 |
|---|---|---|
| `user-cache-evict-consumer-group` | user | 首个兜底消费者 |
| `product-cache-evict-consumer-group` | product | 2026-09-27 新增 |
| `content-cache-evict-consumer-group` | content | 2026-09-27 新增（消除对 user 的单点依赖） |

> 约定：**新服务使用 CacheHelper 的 MQ 兜底（或自行发 CACHE_EVICT_TOPIC）时，必须在本表补一行**，
> 并在本服务注册消费者组（集群模式下各组全量消费，删除幂等）。
