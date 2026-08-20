# my-xhs 交接文档 — Task12（G2-G7 多轮回归完成 + 18 项缺陷修复 + AI 团队需求答复）

> 2026-08-16 | 承接 Task11（297 用例全绿）→ 本阶段：**G2-G7 各 2~4 轮回归全绿** + **修复 18 项缺陷**（T-120~T-130 系列 + O-P2-7-1 + P2-7 上限 + T-092 补修）+ **AI 团队需求（造数/Gateway 集成）探索确认与答复**
> 给下一个 AI 的**详细交接**。重点：**本阶段修复明细（§三）、深挖发现（§四）、AI 需求状态（§六）、数据状态（§九）、下一步计划（§十）**

---

## 零、状态速览（2026-08-16 20:30）

```
微服务 16 个 UP（本机容器 21.214.97.212；AI 诊断台 app:19020 也在跑）| 中间件机 21.130.247.89
回归进度：G2 ✅43×4轮 | G3 ✅32×4轮 | G4 ✅23×4轮 | G5 ✅41×4轮 | G6 ✅38×4轮 | G7 ✅36×4轮
         （G1、G8 本阶段未回归——下一步补）
修复：本阶段 18 项（T-120~T-130 系列 + O-P2-7-1 + P2-7 上限 + T-092 补修）——涉及 9 个服务重启
服务重启：cart/coupon/user/gateway/order/inventory/home/search/notification（全部修复后 jar）
数据：业务表全 0（t_user 5 历史用户）；**AI seed 已执行**（订单 8 单 4 分片、支付 6、加购 18、笔记事件 20、浏览 60）
      历史残留：t_cart_event=213（含 seed 18 + 历史 195）、t_note_event=91（含 seed 20 + 历史 71）
ES：note/suggest/product 三索引全 0
Redis：业务 key 全清（仅 gateway nonce 7 个 + msg:idempotent 保留）
Token：/tmp/g*_users.json 全部过期，需重新登录
```

---

## 一、环境拓扑（与 Task11 一致，无中间件变更）

- **微服务机 = 本机容器 21.214.97.212**（16 JVM：gateway19000/user19001/content19002/analytics19003/counter19004/product19006/cart19008/inventory19009/coupon19010/order19011/payment19012/notification19013/im19014/home19015/search19016 + **ai-app19020**）
- **中间件机 = 21.130.247.89**：Redis 6379 主/6380 从/26379 哨兵；MySQL 3306 主/3307 从；RocketMQ 9876/dashboard 18081；ES 19200；Canal note/product/inventory 三实例；xxl-job 18080（admin/123456）；Prometheus 19090；Grafana 13000；Kibana 15601；Sentinel 8858；Nacos 18848
- **AI 域**：my-xhs-ai-app:19020（诊断台）、my-xhs-ai-mcp:19021（MCP，内网）、my-xhs-ai（AI 团队代码/文档）
- xxl 任务：组 3=counter/4=inventory/5=notification/6=payment/8=order/9=cart/10=coupon/11=home/12=search（沿用）
- **pay.type: remote**；t_item_feature 已建表（A-1）

---

## 二、本阶段回归汇总（逐用例执行，禁止批量——纪律保持）

| 组 | 轮次 | 结果 | 关键结论 |
|---|---|---|---|
| **G2 内容社交** | 2/3/4 轮 | ✅ 43/43 ×3 | T-114/T-116 修复稳定；**O-Comment-5 修复后行为确认**（回复→通知被回复者）；T-092 补修后点赞→ES likeCount 稳定 |
| **G3 商品购物车** | 2/3/4 轮 | ✅ 32/32 ×3 | T-120 幽灵拦截/O-P2-7-1 恢复响应/P2-7 上限/T-124 hash tag/T-128 最低价 全部稳定 |
| **G4 优惠券** | 2/3/4 轮 | ✅ 23/23 ×3 | T-121/status 校验/-3 分支/use 文案/孤儿券/T-129 幂等 全部稳定 |
| **G5 交易** | 2/3/4 轮 | ✅ 41/41 ×3 | T-060/062/068/071/T-123/T-124/P1-1 全部稳定 |
| **G6 搜索首页** | 2/3/4 轮 | ✅ 38/38 ×3 | T-125 hasMore/T-126 已删标记/T-127 following:latest/T-119/T-092 全部稳定 |
| **G7 通知IM计数** | 2/3/4 轮 | ✅ 36/36 ×3 | T-098/T-113/T-094/T-130 全部稳定；SSE/WS/对账全链路 |

> 每轮均逐用例执行（无批量）；G2 共跑 4 轮（含会话开头 1 轮）、G3-G7 各 4 轮（含 Task11 后的第 1 轮）。

---

## 三、本阶段修复明细（代码级，全部验证 + 打包重启）

### 3.1 各服务修复清单

| # | 缺陷 | 服务 | 根因与修复 | 验证 |
|---|---|---|---|---|
| **T-120【P2】** | 加购幽灵 SKU | cart | add/merge 无存在性校验 → 幽灵条目进 Redis+MySQL。修复：skuExists 校验（Feign getSkuDetail，product 不可用降级放行）→ 不存在 30001"商品不存在或未上架" | 幽灵 add/merge 30001 + 无写入 ✅；下架 SKU 保持 200 ✅ |
| **O-P2-7-1【P1】** | P2-7 恢复首次响应 stale | cart | restore 后 checkedSet/sortMap 用恢复前空结果。修复：restore 后重新 pipeline 读三结构 | DEL 三 key → 首次 list checkedCount 立即正确 ✅ |
| **P2-7 恢复上限** | restore 可超 50 种 | cart | restoreCartFromDb 无 LIMIT。修复：orderByDesc(createdAt)+LIMIT 50 | MySQL 55 条→恢复 50 ✅ |
| **T-121【P2】** | available 含未生效券 | coupon | getAvailableCoupons 不过滤 validStart。修复：UserCouponVO 补 validStart + 过滤 validStart≤now | 未生效剔除/恢复后含 ✅ |
| **status 校验** | updateTemplateStatus 非法 status 入库 | coupon | 加 status∈{0,1} 校验 → 40002"优惠券状态无效" | status=2 → 40002 + DB 未变 ✅ |
| **-3 分支语义** | 限领被转报售罄 | coupon | 重试 -2 → 30013（原粗糙转 30014）| 已限领用户 -3 分支 → 30013 ✅ |
| **use 文案** | 重复 use 文案混淆 | coupon | status==1 → "优惠券已被使用" | 重复 use → 30016 已被使用 ✅ |
| **孤儿券** | name=null 脏条目 | coupon | batchToVO 模板缺失跳过 + warn | list/available 无孤儿 ✅ |
| **T-129【P3】** | createTemplate 无幂等 | coupon | @Idempotent(key="template:create:name:type", 10s) | 同参 40201 + 仅 1 行 ✅ |
| **T-122【P2】** | 删号后旧 token 有效 | user+gateway | 新增管理端点 DELETE /api/user/internal/delete/{id}（X-Admin-Call）→ 逻辑删+revokeAllTokens+清缓存；gateway hmac-white-list 加 /api/user/internal/** | 删号后旧 token → 401"Token 已被注销" ✅ |
| **T-123【P2】** | appendEvent 幂等跳过不收敛状态 | order | 重复事件但状态不一致 → 乐观锁收敛（不重复插事件）| 重置 status=1+补偿 → 1→5 收敛+事件 1 行 ✅ |
| **T-124【P3】** | bucket:count 无 hash tag | inventory | 统一 `inventory:bucket:count:{%d}`（3 处）| init 后带花括号、预扣/释放正常 ✅ |
| **T-125【P2】** | Feed hasMore 恒 false | home | mergeAndSort limit(size+1)（T-085 多取 1 条被截断）| P1/P2 True 尾页 False、2+2+1 完整分页 ✅ |
| **T-126【P3】** | 已删笔记被晚到推送写回 outbox | home | NoteDeleteConsumer 设 5min 已删标记 + FeedPushConsumer 检查跳过 | 删除→晚到推送被拦+outbox None ✅ |
| **T-127【P3】** | following:latest 残留已删笔记 | home | NOTE_DELETE SCAN 全量 ZREM | followingCleaned=1 ✅ |
| **T-092【P2】+补修** | 点赞后 ES likeCount 不更新 | search | 新增 LikeCountSyncConsumer（LIKE/UNLIKE→ES partial update）；**补修**：改读 analytics 权威 Set `myxhs:like:note:{id}` SCARD（原读 counter key 与 counter 消费竞争读旧值）| 点赞→1/二人→2/取消→1 稳定 ✅ |
| **T-128【P3】** | ES price 取首 SKU 非最低价 | search | 遍历 skuList 取 min | 99.9 先建 9.9 后建 → ES price=9.9 ✅ |
| **T-130【P3】** | 通知模板缓存无 TTL | notification | 聚合标题缓存加 5min TTL（空值也缓存）| 改模板缓存期内旧值（TTL 生效）✅ |

### 3.2 文档差异/行为确认（不改代码，断言按运行态）
- **O-Comment-5 已修复（2026-08-13）**：回复评论通知被回复者（G2-02 文档观察项已过时）
- updateSpuStatus 限流 key 前缀=`myxhs:product:updateStatus`（文档未标注）
- inventory init/stock 经 gateway 需 JWT（仅 hmac 免签）
- IM CHAT 字段是 `to`（非 receiverId）
- analytics 权威 Set 是 `myxhs:like:note:{id}`（非 counter like:set）
- searchAfter 是 String 直接 quote（#117）
- 中文搜索历史删除需 URL 编码+签名 path 一致（观察项）
- #102 SPU 同秒 UPDATE canal 边界（中间件侧，代码无解）

---

## 四、深挖发现（每轮回归的发现路径）

- **T-092 时序缺陷（重要）**：search 与 counter **并行消费同一 LIKE 消息**——search 读 counter key 时 counter 未写入 → ES likeCount 更新为旧值 0。改读 analytics 权威 Set（like 接口同步 SADD，无竞争）。**教训：跨服务读异步写入的 key 有竞争，读同步权威源**
- **T-125 发现路径**：P1 分页 hasMore 恒 False，追 mergeAndSort `.limit(size)` 截断 T-085 多取的 1 条
- **T-126 发现路径**：G6-02-07 删除后 outbox 残留复现（15:55/15:56 两次消费同 noteId）
- **T-127 设计纠错**：following:latest 按粉丝维度分 key，初版按 authorId ZREM 无效（日志 followingRemoved=0）→ 改 SCAN 全量
- **T-128 注释与行为不符**：ProductIndexSyncConsumer 注释"最低售价"但取 get(0)
- **#102 复现**：SPU 创建+下架同毫秒 → canal ts 相同 → INSERT 后写覆盖 status=1（不同毫秒 UPDATE 正常）
- **T-120 行为影响**：50 种购物车上限测试需真实 SKU（幽灵被拦无法造数）

---

## 五、观察项状态（保留，有实质理由）

| # | 观察项 | 说明 |
|---|---|---|
| T-092 | hot 排序 likeCount | **已修复**（点赞→ES 同步）|
| T-094 | access token 冒充 WS ticket | gateway 101 死隧道，im 层拒绝（T-094 行为确认，非漏洞）|
| T-095 | counter reconcile 限流优先鉴权 | 设计 |
| 下架模板详情 200 | coupon getTemplate 不过滤 status | 管理员查看下线模板所需 |
| claimed/stock key 无 TTL | coupon | 防重领/对账依赖 |
| createTemplate 幂等 | coupon | **已修复（T-129）** |
| 模板缓存无 TTL | notification | **已修复（T-130）** |
| SPU 同秒 UPDATE | canal ExternalGte 同毫秒 | 中间件侧，代码无解 |
| 中文搜索历史删除编码 | 客户端约定 | 接口正常 |
| t_user_behavior 无幂等 | 重复投递重复插 | 观察 |
| recommend:seen 注释 HyperLogLog 实际 Set | 按实现 Set | 观察 |

---

## 六、AI 团队需求状态（重要，下一步工作重点）

### 6.1 需求一：业务数据造数（已交付 seed SQL，已执行，复核+浏览段补跑已完成 2026-08-17）

- **AI 团队澄清确认**：分片查询已实现（OrderMetricsTool 16 节点跨库扫描）；t_note_event 0 行=清理窗口差异；权限无问题
- **我方交付**：`docs/test-3/seed-ai-diagnosis.sql`（分片路由 4 用户覆盖 4 分片、订单 8 单覆盖状态 0-5、支付 6、加购 18、笔记事件 20、浏览 60——漏斗 60:18:8≈10:3:1.3、前后 7 天对比、幂等 ID 段、含自验 SQL）
- **已执行**：seed 数据已落入库（订单 8 单 4 分片：1_0:3/2_0:2/3_0:1/0_1:2；支付 6；加购 18；笔记事件 20）
- **⚠️ 浏览段口径修正（2026-08-17 复核发现）**：AI 漏斗（EventAnalyticsTool.funnelConversion）读的是 `my_xhs_product.t_product_behavior`（behavior_type=1，按 event_time 窗口）**而非 t_counter**；且 t_counter 有唯一键 `uk_target_count(target_type,target_id,count_type)`，同 target 多次采样必然冲突 → **原 `seed-ai-diagnosis-counter-only.sql` 方案废弃**
- **✅ 补跑完成**：`docs/test-3/seed-ai-diagnosis-browse-fix.sql`（60 条 t_product_behavior，ID 9000000000000000501-0560，前 7 天 15/后 7 天 45，user 10001-10004；501/502 用 13~9 DAY、516-520 用 6~1 DAY 避开边界 7 天竞争），已执行，漏斗口径自验通过：browse 前7天=15 / 后7天=82（45 seed+37 历史残留）/ cartAdd=18 / order=8 / pay_success=5
- **AI 团队下一步**：执行 seed 自验（分片行数>0、窗口对比、漏斗量级）——漏斗口径按 `t_product_behavior` 校验

### 6.2 需求二：Gateway 集成（我方部分已实施并部署 2026-08-17；AI 侧 2 项待办）

- **AI 团队确认**：角色用 t_user.role（默认 OPERATOR）；AI 前端是主前端一部分（frontend/src/pages/ai/）；SSE 31min 双方对齐；conversations 经网关；HMAC 免签范围确认；MCP 内网
- **探索确认（我方）**：
  - ✅ gateway 机制：lb:// 路由 + metadata（response-timeout/rate-limit-qps）+ Sentinel 限流 + JWT 鉴权（C-07 注入 X-User-Id）+ hmac-white-list
  - ❌ **无 TokenRelay/OAuth2**（方案假设不存在）→ 身份用 X-User-Id header（AI 后端需改读 header，他们已承诺 1 天内完成）
  - ❌ **无 X-User-Role**（t_user 无 role，gateway 无角色注入）→ 需新增 t_user.role + gateway 注入
  - ⚠️ **路径映射不一致**：前端 baseURL=`/ai-api`（src/api/ai.ts），请求 `/ai-api/api/runs` 等；vite dev 代理 rewrite 去 `/ai-api` 前缀。**AI 方案 §2 的 Path=/api/ai/**,/api/runs/** 与前端不匹配** → 正确配置 `Path=/ai-api/**` + `RewritePath=/ai-api/(?<seg>.*), /$seg`
  - ⚠️ ai-app 数据源 URL 默认 my_xhs_order_0（仅分片 0）——但工具层全限定名跨库，无影响
- **实施内容（2026-08-17 已完成我方部分并部署）**：
  1. ✅ t_user 加 role 字段（默认 OPERATOR，DDL 已执行）+ 登录/注册/刷新 JWT 带 role claim + gateway 注入 X-User-Role（GatewayAuthFilter，JWT role→header，set() 覆盖防伪造）
  2. ✅ gateway yml：路由 `Path=/ai-api/**` + RewritePath（uri lb://my-xhs-ai-app，metadata response-timeout=1860000(31min)/connect-timeout=3000/rate-limit-qps=100）
  3. ✅ hmac-white-list 加 `/ai-api/**`（前端免签名，登录态 JWT 保证；JWT 白名单不加，保证 401）
  4. ✅ 服务重启：user(19001)/gateway(19000)/ai-app(19020) 全部新 jar 已上线（清理了 2 个占 19000 的僵尸 gateway 进程）
- **⚠️ AI 团队待办（2 项，我方不动 AI 代码，移交 AI 团队）**：
  1. **RunController DELETE /api/runs/{id} 无角色校验**——验收项"L1/L2 角色（403/200）"需要：读 X-User-Role header，非 TECH → 403（当前任何登录用户均可取消，L1 验收必挂）
  2. **ConversationController list() 只认 query userId（可伪造）**——建议 X-User-Id header 优先（gateway 注入、经网关防伪），query 兜底兼容直连
- **联调验收（401/userId 传递/角色 403/SSE/429）**：待 AI 侧 2 项完成后再执行
- **交付物**：`docs/test-3/REPLY-AI-TEAM-20260816.md`（含 §4.2 路径映射修正）、`docs/test-3/REPLY-TO-AI-TEAM-20260816.md`（AI 团队回复）

---

## 七、ISSUES 状态（T-120~T-130 全部有结论）

- **本阶段新增修复 18 项**（§三）
- 保留观察项：T-094/095、下架模板详情 200、claimed/stock 无 TTL、SPU 同秒 UPDATE、中文搜索历史编码、t_user_behavior 无幂等
- 历史观察项（Task11 遗留）：T-004/006/020/023/027/028/029/092(已修)/093/096/097/105/115(文档差异) 等沿用

---

## 八、坑点速查（pitfalls 已覆盖 #88-120，本阶段补充）

### 新增坑点（本阶段）
1. **searchAfter 是 String 直接 quote**（勿 json.dumps 二次序列化——parseArray 失败翻页空）
2. **跨服务并行消费读 key 竞争**：读异步写入的 key（counter key）会读到旧值——读同步权威源（analytics like:note Set）
3. **50 种上限测试需真实 SKU**（T-120 拦幽灵造数）
4. **发布需在关注后**（推模式按粉丝列表，先发布后关注收件箱空）
5. **P2-7 恢复断言等首次响应**（O-P2-7-1 修复后首次即正确）
6. **IM CHAT 字段是 to**（非 receiverId）
7. **analytics 权威 Set 是 like:note**（非 counter like:set）
8. **createTemplate 无幂等→T-129 后有 10s 幂等**（测试注意 40201）
9. **中文 query 需 URL 编码**（urllib UnicodeEncodeError）
10. **refund-success 对非 1 状态幂等跳过**（退款只对 status=1 订单）

### 沿用坑点（Task11）
- #88 重启用 restart-service.sh（timeout≥180s）；#79-1 带 INTERNAL/ADMIN_TOKEN
- #79-3 限流窗口跨用例共享；#81 字面花括号 key
- #108 对账三处对照；#117 searchAfter；#119 token 30min 过期
- 分片查询带 userId（db=uid%4, tb=uid/4%4，表名带后缀）

---

## 九、数据状态（最终基线 2026-08-16 20:30）

```
MySQL：
  t_user=5（g3d_772478/g3r_766027/g3r_772917/g5b_776055/t107_781439）
  t_note=0、t_comment=0、t_like=0、t_favorite=0、t_follow=0、t_notification=0
  t_order=0（4 分片全 0）、t_payment=6（AI seed）、t_inventory=0、t_tcc=0
  t_coupon_template=0（deleted=0）、t_user_coupon=0、t_coupon_outbox=0
  t_cart_event=213（含 AI seed 18 + 历史残留 195——G2/G3/G5 历史测试事件未清理）
  t_note_event=91（含 AI seed 20 + 历史残留 71——G6/G7 历史测试事件未清理）
  t_counter=0、t_item_feature=0、t_push_template=5（完整）
  AI seed（已执行，2026-08-16）：订单 8 单（my_xhs_order_1.t_order_0:3/2_0:2/3_0:1/0_1:2）、支付 6、加购 18（user 10001-10004）、笔记事件 20
  **浏览段（2026-08-17 补跑完成）**：t_product_behavior 60 条（ID 9000000000000000501-0560，前 7 天 15/后 7 天 45）——AI 漏斗口径表，
    t_counter 仍全表 0（唯一键冲突，勿再补）
ES：note_index=0 / product_index=0 / suggest_index=0
Redis：业务 key 全清（仅 gateway nonce 7 个 + msg:idempotent 保留）
Token：/tmp/g*_users.json 全部过期（30min）
```

> ⚠️ **已确认（2026-08-17 复核完成）**：浏览段口径修正——AI 漏斗读 `t_product_behavior` 而非 t_counter，原 `seed-ai-diagnosis-counter-only.sql` **废弃**（t_counter 唯一键冲突+口径错误）。
> **补跑已执行**：`docs/test-3/seed-ai-diagnosis-browse-fix.sql`（t_product_behavior 60 条，前 7 天 15/后 7 天 45）。
> **漏斗口径自验**：browse 前7天=15 / 后7天=82（45 seed + 37 历史残留）/ cartAdd=18 / order=8 / pay_success=5 ✅

---

## 十、下一步计划（重点）

### 10.1 G1、G8 回归（本阶段未做）
- **G1 认证用户**（78 用例）：验证码/注册/登录/token/HMAC/用户信息/关注/地址/拉黑——gateway 鉴权基线，所有端点依赖
- **G8 可观测性**（6 用例）：SkyWalking/Prometheus/Grafana/日志链路——快速组
- 前置：重新登录用户（token 全过期）

### 10.2 AI 需求二：Gateway 集成（我方部分已部署 ✅；剩余在 AI 团队）
- ✅ 已上线：t_user.role + X-User-Role 注入、/ai-api 路由 + RewritePath + hmac-white-list + 限流 metadata + SSE 31min 超时（user/gateway/ai-app 已重启）
- **AI 团队待办（§六.2）**：① DELETE /api/runs/{id} 角色校验（非 TECH→403）② ConversationController X-User-Id header 优先
- AI 侧完成后：联调验收（401/userId/角色 403/SSE/429）

### 10.3 AI 需求一：seed 复核（已完成 ✅）
- 复核完成：订单 4 分片 8 单/支付 6/加购 18/笔记事件 20 ✅
- **浏览段已补跑**：`seed-ai-diagnosis-browse-fix.sql`（t_product_behavior 60 条，前 7 天 15/后 7 天 45）；原 counter-only.sql 废弃（口径+唯一键）
- 待与 AI 团队确认造数自验结果（漏斗量级 browse 60:cartAdd 18:order 8）

### 10.4 执行要点（沿用纪律）
1. 逐用例执行，禁止批量；三层验证法（L0/L1/L2）
2. 前置：重新登录；每轮前确认基线（§九）
3. 对账三处对照（#108）；限流窗口执行前 DEL（#79-3）
4. 发现缺陷直接修（用户要求）+ restart-service.sh + 复测
5. 执行记录实时写回 execution/；ISSUES.md/pitfalls 同步更新

---

## 十一、执行纪律（沿用 Task11 + 本阶段补充）

1. 禁止批量测试（逐用例 + L2 验证）
2. 三层验证法；结论分级禁止"没问题"
3. 发现问题直接修（观察项也修，除非有实质理由）
4. 重启一律 restart-service.sh（timeout≥180s）；改代码后重打包（-Dmaven.test.skip=true）
5. 对账三处对照（Redis/DB/权威源）
6. 执行记录实时写回文档
7. 文档差异登记不改代码，断言按运行态
8. **本阶段补充**：跨服务并行消费读 key 竞争要读同步权威源；seed 数据与测试数据区分（AI seed 段 9000000000000xxx 勿当测试残留清理）

---

## 十二、本交接文档 REVIEW

- ✅ 回归汇总：G2-G7 各 2-4 轮全绿（逐用例）
- ✅ 修复核对：18 项修复全部有验证记录 + 9 服务重启
- ✅ 深挖发现（§四）：每项修复的发现路径与教训
- ✅ AI 需求状态（§六）：造数已交付已执行 + Gateway 方案确认待实施
- ✅ 坑点完整：本阶段新增 10 条 + 沿用
- ✅ 数据状态（§九）：seed 已执行 + 历史残留 + 待复核项（t_counter 浏览段）
- ✅ 下一步明确：G1/G8 回归 + Gateway 集成 + seed 复核
