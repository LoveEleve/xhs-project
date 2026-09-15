# 平台服务级深挖（第四轮，2026-09-15）

> 来源：各服务 02-source-deep-analysis / test-matrix / test-2 service-analysis；只收"设计权衡 + 量化 + 经典坑"
> 口径提醒：test-2 旧文档与 test-4 当前源码存在多处相反结论，以 test-4 为准

## payment
- 超时支付单只置 status=2 不通知 order：支付超时与关单窗口同为 30min + orderCloseJob 每分钟兜底 → 真实终态一致（02:114）
- 退款失败不通知 order：钱未退，订单保持已支付即正确终态，由退款单状态+流水+日志可观测（02:117）
- 已修资损：RefundNotifyCompensateJob 原扫所有 status=1 退款单（含部分退款）→ 改 JOIN t_payment 仅补 status=3（02:111）
- 权衡：支付超时检测弃 Redis Lua（成死代码已删）改 DB 扫描 30s 周期；通知线程池 1/2+队列 500+DiscardPolicy 保主流程（02:55,85）

## coupon
- 业务风险（设计意图存疑）：useCoupon 不扣 remain 但退券回补 → `remain+已发>total` 超发敞口，可"领→用→退→再领"绕过限领（02:22-26）
- 幽灵券：syncSend 超时但 broker 已投递/Outbox 补发成功 → 用户实际有券却提示失败（02:37-38）
- 已修：Redis 库存丢失时用 30 分钟陈旧模板初始化 → 改 DB 实时 remain_count（02:34）
- Outbox 锁无续期（200×3s 可能超 4s 租约）→ 靠 claimNo 消费去重兜底（02:44）

## cart
- updateQuantity 先 hasKey 再 HSET（check-then-act）→ 并发删除后字段重建、商品"复活"（06/00-review:26）
- 清空非原子（多 key delete+marker+CLEAR 三步，无 Lua）→ 并发交错可覆盖；建议单 Lua（02:46-48）
- Product fallback 语义不一致：明确失败 false 拒绝，异常 fallback true（fail-open）→ 可写幽灵 SKU（02:62-64）
- 对账锁三坑：无随机 token 无条件 DEL / TTL 600s 无续租 / 单用户入口绕过全量锁（02:56-60）

## product
- Canal 监听 t_spu,t_sku 但 Search Consumer 忽略 t_sku → SKU 价格/状态变化不进 ES（02:98）
- Bloom 固定 100 万/1%/只增不减/无重建方案（约 1.14MB，超容量误判率上升）（02:88）
- SPU 创建幂等键=名称+类目，不含用户/请求号 → 同名同类目合法商品误判（02:47）
- 部署 SQL 缺 t_product_behavior 建表（仅历史 DDL 有）（02:94）

## user（简历不写用户模块，但安全素材可用）
- 注销静默成功：非 `Bearer ` 前缀直接 R.ok()，refresh-only 不可达（05-deep:15-23）
- 登录失败计数：仅密码错误计数，IP 20 锁/账号 5 次且 IP≥2 才锁；clear 不清 IP 计数（:62）
- 验证码写入失败仍返回 200+captchaKey（RedisOperator 只 log 不抛）（test-2 02:106）

## content
- 审核状态机不成立：DFA 通过直接 PUBLISHED/APPROVED，AUDITING/REJECTED 枚举无真实流转（02:17）
- 已发布笔记更新不重审、不通知 Feed/搜索；详情/Feed/ES 版本可漂移（02:38）
- 经典坑：草稿 NULL_PLACEHOLDER 2min，发布未清详情缓存 → 发布后 404 至过期（02-test:729）
- 量化 SLA：发布延迟 11ms（DFA 0.1ms/2 万字 ~2ms）；搜索可见 3-6s（Canal+refresh）（01-full-chain:45）

## common
- 读写分离**未真正生效**：determineCurrentLookupKey 注释称 SQL 前缀分析但未实现，默认全走主库、从库闲置（02:19-21）——功能正确但性能未达预期
- AOP 顺序：@DistributedLock(50) 先于 @Idempotent(100)，先锁后判幂等防并发穿透；Redis 不可用降级放行（03-annotations:164）
- Hikari master20/slave10；事务超时 30s；从库不可用自动降主 + 30s 探测（02:62）

## im
- 路由 TTL 90s + 实例崩溃后 Pub/Sub 订阅消失 → 最长 90s 内消息丢失（设计标注"可接受"）（03:160,257）
- 离线两层存储：Redis ZSet 只存 msgId（1000 条≈40KB/用户/7 天），内容留 MySQL（05:262-277）
- 150 虚拟节点定量理由：50 太少倾斜、300 查找 ~log(300)≈9 次、再增收益有限（04:271-278）；FNV-1a 5 行无依赖

## home
- 降级量化：counter 6s 延迟 → feed 200 且 2.04s 返回（层 1 保留/counts 降级空）；停 cart → 503（01-test-matrix:26-27）
- 断点续推实测：cursor=2 只推剩余，终态 cursor=502/completed（:29）

## search
- 版本防乱序实测：001 后 000 被拒、002 可更新；DELETE 物理清除（01-test-matrix:22）
- 索引规模：note 4/product 5 文档；全量重建 17 条（note9/product9）；脏 SPU 不阻塞整批（:23,29）

## analytics
- 关注 Lua 拆 A/B 因 Cluster 不同 slot；B 失败不回滚 A 靠对账（02:66）
- 可用性优先边界：UserFeign 拉黑查询失败放行（02:82-83）
- 防大 V OOM：列表 pageSize≤50、共同关注≤5000、Pipeline 防 N+1（02:92）

## counter
- 实测 TTL：dedup 6564s≈2h、计数 2591386s≈30d（01-test-matrix:21）
- 刷盘故障恢复实测：rename table 注入 → 重试 3 次全败 → 回写缓冲 → 表恢复自动补刷 DB=1（:34）
- 懒迁移：counter Set 空但计数>0 → 从 analytics 权威 Set 同步（T-113）（02:34）

## inventory
- 预扣超时 score=创建时刻+1800s，`rangeByScore(0,now+60s)` 只命中 ~29 分钟前记录=提前 1 分钟释放（合理设计非 bug）（02:114）
- 缓存失效只处理 DELETE，UPDATE/INSERT 跳过（外部直改 DB 不失效）（02:43）
- 缺口：SKU 不存在时消费者直接确认，可能"订单提交但库存未扣"（02:41）

## order
- 已修 P1 资损：退款去掉 releaseInventory，改 confirmInventoryDeductSync + 幂等 refundRestore，消除双回补虚增（02:106）
- 已修双投：本地消息从不标已投递 → 每 30s 补发再投一次（靠库存幂等兜）；加 markSuccessByTransactionId（02:83,111）
- 已修安全坑：Feign 内部令牌硬编码默认值（公开常量可伪造）→ 环境变量（02:88）
- 已知权衡：无 user_id 全分片 LIMIT 50 广播截断致批序失真，评估"至少一次低频兜底"（02:117）

## gateway
- 经典坑：空 body 的 Flux join 返回 empty → 链挂起空响应；加 defaultIfEmpty（T-130）（06:392）
- HMAC 时序缺陷：先占 nonce 再验签，错误签名可抢占合法 nonce（本轮不修）（06:162-164）
- 性能坑：WebFlux 内用同步 StringRedisTemplate → Redis 慢阻塞 EventLoop（未修）（06:351-355）
- 路由事实：16 条显式路由；AI 31min 超时仅在 metadata，实际走全局 HttpClient timeout（SSE 可能 ~10s 断开）（06:220-225）

## notification
- SSE 跨实例实测：双实例共同消费，聚合计数共享（6→9）（01-test-matrix:33）
- 对账周期 10min；聚合窗口 Redis SETNX 5min（:25）
- 并发 read-all：未读 2→0 不超减；msgId 幂等 24h + 自通知跳过（:32）

## 跨文档观察
- 多服务共同模式：Redis 与 DB/MQ 无原子性，统一"幂等+对账/补偿"收敛，但补偿锁普遍缺 token/续租（cart600s/coupon4s/order10s/payment3-10s）
