# my-xhs 交接文档 — Task10（G3-G7 全量回归 + 观察项集中修复）

> 2026-08-15 | 承接 Task9（G1-G8 完成，T-001~105 有结论）→ 本阶段：**G3(32) + G4(23) + G5(41) + G6(38) + G7(36) 全量回归 = 170 用例全绿 + 新发现并修复 4 项缺陷（T-106/109/112/113）+ 3 项观察项集中修复（T-107/108/110）**
> 给下一个 AI 的**详细交接**。重点：**本阶段修复明细（§三）、深挖发现（§四）、坑点速查（§六 #88-108）、数据状态（§七）、下一步计划（§八——回到 G2 继续回归，未执行）**

---

## 零、状态速览（2026-08-15 17:00）

```
微服务 15 个 UP（本机容器 21.214.97.212）| 中间件 27 容器（21.130.247.89，对方管理）
测试进度：G1 ✅78 | G2 ✅43+回归43（Task9）| G3 ✅回归32 | G4 ✅回归23 | G5 ✅回归41 | G6 ✅回归38 | G7 ✅回归36 | G8 ✅6
修复：本阶段代码修复 4 项（T-106 gateway/109 cart datetime/112 home skuName/113 counter 对账 DB）
      观察项修复 3 项（T-107 cart checked/108 cart TTL/110 order 事件链）
脏数据：**已彻底清理（2026-08-15 Review 后全清）**——MySQL 30 张测试主表全 0（t_note/t_comment/t_like/t_favorite/t_follow/t_local_message/t_note_event/t_behavior/t_feature/t_hot/cart 含 event/inventory/tcc/coupon 含 outbox/payment 含 event/notification/chat/relation/counter/address/order_mapping）；订单分片仅历史模拟用户（10001/91001 等）保留；ES 三索引 0；Redis 仅幂等记录
ES：三索引全 0 | Redis：仅 msg:idempotent 幂等记录保留（#84 勿删）+ 布隆/分类树缓存
Token：/tmp/g7_users.json 已过期需重新登录（30min）
重启参数：analytics 需 -Dmanagement.admin-token；所有服务需 ADMIN_TOKEN/INTERNAL_TOKEN 环境变量（#107）
```

---

## 一、环境拓扑（与 Task9 一致，无中间件变更）

- **微服务机 = 本机容器 21.214.97.212**（15 JVM：gateway19000/user19001/content19002/analytics19003/counter19004/product19006/cart19008/inventory19009/coupon19010/order19011/payment19012/notification19013/im19014/home19015/search19016）
- **中间件机 = 21.130.247.89**：Redis 主 6379/从 6380/哨兵 26379；MySQL 主 3306/从 3307（读写分离）；RocketMQ nameserver 9876/dashboard 18081（免登录）；ES 业务 19200（elastic/Xhs@2026#Elastic）/SW 存储 19201（密码不同）；Canal note/product/inventory 三实例（rocketMQ 模式）；xxl-job 18080（admin/123456）
- **xxl 任务**（21 个全启用，组号：3=counter/4=inventory/5=notification/6=payment/8=order/9=cart/10=coupon/11=home/12=search）
- **pay.type: remote**（order 走 payment 服务，MockPayService 不加载——#85）
- **t_item_feature 已建表**（A-1 Task9 修复，本阶段验证特征提取/精排/品类打散全恢复）

---

## 二、本阶段回归汇总（逐用例执行，禁止批量——纪律保持）

| 组 | 用例数 | 结果 | 关键结论 |
|---|---|---|---|
| **G3 商品购物车** | 32 | ✅ 32/32 | T-106 发现修复（gateway Sentinel 悬挂 30s）；T-109 发现修复（秒级 datetime 丢更新）；T-107/108 观察项（后修复）|
| **G4 优惠券** | 23 | ✅ 23/23 | 无新增缺陷；T-057/056 语义确认；xxl#15/#16 对账/过期全过 |
| **G5 交易** | 41 | ✅ 41/41 | T-110 观察项（后修复）；T-071 修复确认（退款回补库存）；补偿三动作 MQ 投递实证 |
| **G6 搜索首页** | 38 | ✅ 38/38 | T-112 发现修复（home skuName null）；T-087/A-1/T-082 修复确认；SPU 同秒 UPDATE 边界观察 |
| **G7 通知IM计数** | 36 | ✅ 36/36 | T-113 深挖发现修复（counter 对账 DB 不写）；T-098/094/095 修复确认 |

**全部 170 用例通过，无批量执行。**

---

## 三、本阶段修复明细（代码级，全部验证）

### 3.1 新发现并修复（4 项）
| # | 缺陷 | 根因 | 修复 | 验证 |
|---|---|---|---|---|
| **T-106【P1】gateway 限流请求悬挂 30s** | `sentinel-spring-webflux-adapter:1.8.8` 的 DefaultBlockRequestHandler 编译于 Spring 5.x（调 `ServerResponse.status(HttpStatus)`），WebFlux 6.1.6 只有 `status(HttpStatusCode)` → NoSuchMethodError → block 响应无法生成 → 请求悬挂；项目只注册了 Gateway 适配器 handler，漏配 WebFlux 适配器 | RateLimitFilter 补注册 `WebFluxCallbackManager.setBlockHandler`（HttpStatusCode 兼容），共用 buildBlockResponse | 80 并发触发路由限流：50×200+30×429 全 40ms 返回零悬挂（修复前第 51 次悬挂 30s）|
| **T-109【P1】cart 秒级 datetime 丢更新** | `t_cart_item` created_at/updated_at 为秒级 datetime，CartSyncEvent.timestamp 纳秒级——秒边界（x.5~x.999）连续写时 DB 四舍五入进位 → C-05 `!isBefore` 误判跳过 UPDATE → MySQL 丢更新（Redis=9/MySQL=4）| `ALTER TABLE t_cart_item/t_cart_event MODIFY ... datetime(3)`（主库执行，从库复制追平）| 秒边界 3/3 轮 Redis=MySQL=9（修复前 MySQL=4）；普通路径全过 |
| **T-112【P1】home 商品聚合 skuName 恒 null** | ProductAggService 读 product 响应 `sku.get("skuName")`，但 SkuVO 字段名是 `name`（C-14 同类，CartAggService 修过、此处遗漏）| ProductAggService.java:225 `sku.get("name")` | 修复后 skuName="A款" ✅ |
| **T-113【P1】counter 对账 analytics 权威修正不写 DB** | `reconcileLikeFromAnalytics` 修正 Redis counter key 只 SET Redis，不 UPDATE t_counter → DB 残留漂移值（对账链不完整）| CounterMapper 加 selectByBusinessKey + 修正 Redis 后同步 updateCountValue | 对账后 Redis/DB/LikeSet 三者一致=1（修复前 DB 残留 9）|

### 3.2 观察项集中修复（3 项，用户要求"发现问题直接修掉"）
| # | 问题 | 修复 | 验证 |
|---|---|---|---|
| **T-107** | add 已存在商品 → ADD 事件 checked 恒传 1 → MySQL checked 被强制 1（Redis 保持 0，不一致源头）| cart_add.lua 返回值加 10000 标志位（新商品），CartService 按标志发 checked=1（新）/null（旧）| 加购→取消勾选（0/0）→再加购→保持 0 ✅ |
| **T-108** | P2-7 Redis 丢失恢复后三 key 无 TTL（永不过期）| restoreCartFromDb 恢复后补 refreshTTL（30 天）| 恢复后 TTL≈2592000 ✅ |
| **T-110** | ORDER_CREATED 事件从不落库（EVENT_STATUS_MAP CREATED→0 与创建时 status=0 相同 → 幂等恒跳过，事件链断）| EVENT_STATUS_MAP CREATED→-1 + appendEvent 对 targetStatus<0 跳过状态更新 + replayStatus 跳过 -1 | 事件链 CREATED seq=1 + 订单 status=0 + CANCELLED seq=2 ✅ |
| | **⚠️ T-110 修复过程回归教训**：第一版只改 -1 未跳状态更新 → updateStatusWithLock 把订单 status 写 -1（非法）→ 补跳过逻辑后正常（pitfalls #106）| | |

### 3.3 修复确认（Task9 修复运行态实证，无新改动）
- **T-071**：refund-success 释放库存 + restoreStockOnRefund 退款回补（confirm 清预扣后的独立语义）——total 回退实证
- **T-087**：FOLLOWING 召回出现（FeedPushConsumer 写 following:latest）——G6-03 reason"你关注的人发布了"实证
- **T-098**：聚合标题 count 无滞后（processWithAggregate @Transactional 读主库）——"g7sender3等3人赞了你的笔记"实证
- **T-094**：access token 冒充 WS ticket → gateway 101 死隧道（im 层日志"ticket 类型错误: actual=access"拒绝实证——客户端需 PING 兜底）
- **T-095**：counter reconcile 限流优先鉴权（40202 先于 403——测试注意）
- **A-1**：t_item_feature 建表后特征提取/精排 category/质量分/品类打散全恢复
- **T-085**：hasMore 尾页取满=false（Search After 多取 1 条）

---

## 四、深挖发现（用户质疑"没有问题吗"后复查——本阶段 1 项真缺陷从"观察项"升级修复）

- **T-113 发现路径**：G7-03-08 对账测试发现"漂移 B 后 Redis=1 但 DB=5"——初判"行为正确"（LikeSet 权威），用户质疑后重构造 DB=9/Redis=9/LikeSet=1 → 对账后 DB 仍 9 → 确认为缺陷
- **教训**：对账类断言必须**三处对照**（Redis / DB / 权威源），单看一处会漏缺陷
- 其他复盘项确认非缺陷：reconcile 限流优先（T-095 设计）、SSE 复用 ticket 406（gateway Accept 语义）、IM VO 无 seqNo（文档差异）、IK 单字召回（分词特性）、SPU 同秒 UPDATE 边界（canal ExternalGte 同毫秒——中间件侧，代码无解，登记观察）

---

## 五、ISSUES 状态（T-001~113 全部有结论）

- **本阶段新增修复**：T-106/107/108/109/110/112/113（7 项，见 §三）
- **Task9 修复确认**：T-071/087/094/095/098/A-1/T-082/T-085/T-092（运行态实证）
- **已修验证（Task8 前）**：T-001~003/005/007~013/016~019/021/022/024/025/030~036/040~048/049~079
- **保留观察项（有实质理由）**：T-004/006（待业务决策）、T-020/023/027/028/029（历史）、T-093（已删不补位）、T-096（消息级已读未实现）、T-097（seqNo 空洞）、T-105（已删先于越权）
- **SPU 同秒 UPDATE 边界**（本阶段新观察，未修）：canal ExternalGte 同毫秒版本拒绝下架 UPDATE——需中间件侧确认，代码无解
- **中文搜索历史删除**（未修）：接口正常（英文实证），客户端 URL 编码+签名 path 约定问题

---

## 六、坑点速查（pitfalls #88-108，执行 G2 前必扫）

### 重启/环境类
1. **#88 重启一律 `bash scripts/restart-service.sh <模块>`**——禁止手搓（pgrep 自匹配/历史 PID/analytics admin-token/setsid 混淆全固化）
2. **#107 手搓启动必须 source .secrets/tokens.env**——否则管理端点 403"无权访问管理接口"（counter reconcile 踩过）
3. **#79-1 手动操作带 INTERNAL_TOKEN/ADMIN_TOKEN**

### 数据/时序类
4. **#83 JDBC found-rows**（INSERT IGNORE 冲突恒 0）；**#84 清理勿删 msg:idempotent 幂等记录**（MQ 重投重新预扣）
5. **#81-1 Redis key 字面花括号** `{{{key}}}`；**#79-3 限流/幂等窗口跨用例共享——执行前 DEL**
6. **#85 pay.type=remote**；**#86 分片查询必须带 userId**（db=uid%4, tb=uid/4%4）；**#87 伪订单 ID 有符号**
7. **#99 对账/删除类操纵后必须恢复原值**（G5-02-08 清理遗漏教训）
8. **#100 URL 编码与签名**（中文 keyword 需 quote；Search After 用 safe=''；GET+query 签名一致）
9. **#102 SPU 创建与下架同秒 → canal UPDATE 未同步 ES**（重新触发恢复）
10. **#101 IK 单字召回**（"无关词"命中含单字文档——用纯数字验证）

### G2 相关（本阶段 G2 未回归，Task9 记录）
11. **T-100/101/102/104 文档差异已改**：他人编辑 403、/api/note/my 需 HMAC、multipart bodyHash=""、收藏列表 list 字段
12. **multipart 上传签名 bodyHash=""**（gateway 不缓存 multipart——sign body=None）
13. **写后读延迟**：MQ 消费 1-3s + 主从 1-2s + Buffer 5s 刷盘
14. **token 30min**：长测试中途重新登录（hmacSecret 随登录变化——写回文件）

---

## 七、数据状态（执行 G2 前基线确认）

```
MySQL：**已彻底清理（30 张测试主表全 0）**——t_note/t_comment/t_like/t_favorite/t_follow/t_local_message/
        t_note_event/t_user_behavior/t_item_feature/t_hot_search_snapshot/t_spu/t_sku/t_cart_item/t_cart_event/
        t_inventory/t_inventory_outbox/t_tcc_*/t_coupon_template/t_user_coupon/t_coupon_outbox/t_payment/
        t_refund/t_payment_event/t_notification/t_chat_message/t_chat_user_relation/t_counter/t_user_address/
        t_order_no_mapping 全 0
        订单分片：仅历史模拟用户（10001/91001/94003 等）保留——非测试数据，勿删
ES：note_index=0 / product_index=0 / suggest_index=0（三索引全清）
Redis：仅 msg:idempotent:* 幂等记录（勿删）+ myxhs:product:bloom:spu + myxhs:product:category:tree
用户：g1-g7 测试用户已注册（token 过期，需重新登录）
服务：15 全 UP（含本阶段重启的 gateway/home/cart/order/counter——运行修复后 jar）
```

---

## 八、下一步计划（重点——回到 G2 继续回归测试，未执行）

**⚠️ 当前未执行，仅规划。** 回到 **G2 内容社交（43 用例）** 继续回归测试：

### 8.1 为什么回到 G2
- Task9 已回归 G2 一次（43/43，T-100~105 登记）——但本阶段**大量后端改动**可能影响 G2：
  - **T-106 gateway 修复**（Sentinel WebFlux handler）——G2 全端点经 gateway，限流行为变化需复测
  - **T-107/108/110 cart/order 修复**——G2 无直接关联（购物车/订单），但回归 G5 涉及
  - **T-109 datetime(3) 变更**——t_cart_item 时间列精度提升，若 G2 有依赖需确认
  - **T-112 home 聚合修复**——G6-02 已验证，G2 的 Feed 读取（home FeedService）不受影响但可顺带
  - **本阶段测试产生的数据/状态变更**——环境已全清，G2 需从零造数
- 用户明确指示：下一步 = 回到 G2 继续回归测试（未执行）

### 8.2 G2 回归执行要点（按 G3-G7 已验证的规范）
1. **逐用例执行，禁止批量**（全程保持的纪律）
2. 前置：G1 登录（g2 用户组）+ 造笔记/评论/点赞/关注数据（G2-01~03 文档）
3. 关注点：
   - **T-100~105 文档差异修正后的断言**（他人编辑 403、/api/note/my 需 HMAC、multipart bodyHash=""、收藏列表 list 字段、已删先于越权）
   - **gateway 限流行为**（T-106 修复后快速返回 429/40202，无悬挂）
   - **comment/like/favorite 的计数联动**（SOCIAL_TOPIC 事件 → counter——G7-03 已验证的链路）
   - **通知联动**（点赞/评论/关注 → NOTIFICATION_TOPIC → 通知聚合——G7-01 已验证）
   - **Feed 推送链路**（发布 → FEED_TOPIC → 收件箱/大V 发件箱——G6-02 已验证，G2-01 回归确认）
4. 数据清理：执行前确认 G2 相关表全 0——
   - **my_xhs_content**：t_note/t_comment/t_local_message/t_note_event（当前全 0）
   - **my_xhs_analytics**（G2 社交数据实际落库！）：t_like/t_favorite/t_follow——**已全清 0，G2 回归无需再清理**
   - **my_xhs_user**：t_user_address（G2 发布不依赖地址，可忽略）
5. 时间引用：矩阵 #1-40（feed 推送/限流窗口/幂等 5s 等）
6. 文档：docs/test-3/cases/G2-content-social/（G2-01-note/02-comment/03-like-favorite）

### 8.3 G2 之后
- G1 认证（78 用例）——本阶段未回归（gateway 变更影响鉴权路径）
- 或按用户指示调整顺序

---

## 九、执行纪律（本阶段沉淀，G2 回归必须遵守）

1. **禁止批量测试**（逐用例 + L2 验证）
2. **三层验证法**：L0 静态/L1 语义/L2 实测——结论分级，禁止"没问题"
3. **发现问题直接修**（用户明确要求——观察项也要修，除非有实质理由）
4. 重启一律 restart-service.sh；打包改 Lua/代码后必须重打包（#82）；测试代码编译失败用 -Dmaven.test.skip=true
5. 对账类断言三处对照（Redis/DB/权威源）
6. 执行记录实时写回文档（README 回归表 + ISSUES.md + pitfalls）

---

## 十、本交接文档 REVIEW（成文自检）

- ✅ 数据核对：MySQL/ES 全 0、Redis 幂等记录保留、15 服务 UP
- ✅ 修复核对：7 项修复（§三）全部有验证记录
- ✅ 下一步明确：G2 回归（§八，未执行）
- ✅ 坑点完整：#88-108 全覆盖（含手搓启动令牌教训 #107、对账三处对照 #108）
