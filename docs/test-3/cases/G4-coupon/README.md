# G4-coupon — 优惠券

> 服务：coupon(19010) + RocketMQ（COUPON_CLAIM_TOPIC）| 入口：**gateway(19000)**
> 依赖：G1 登录（testlib.new_user 得 token/hmacSecret/uid）
> 时间引用：矩阵 **#14**（券过期标记）、**#15**（过期即时校验）、D 节（模板缓存 30min）

## 回归记录（2026-08-15，Task9 后全量回归）

**G4 23/23 ✅ 全绿**（G4-01-01~23 逐用例执行）。测试用户 g4a_（管理）+ g4b_（领券）+ g4c_（售罄验证），数据已清理（三表 0）。

### 回归要点（对照 Task8 记录）
- 全链路：创建模板→缓存/stock→领券（Lua+Outbox+MQ）→我的券→用券/退券→xxl#15/#16 全过
- 错误码验证：30012/30013/30014/30015/30016、40002/40001/40003、40202、403 全部复现
- 观察项复测：status=2 无校验入库、下架详情仍 200、validStart 不检查（T-052）、幽灵模板空值缓存 TTL=60、-3 分支 initStockFromDb 恢复
- 并发用券乐观锁恰一成功、退券幂等（重复退 200 无副作用）
- xxl#16 过期标记（0→2）、xxl#15 对账三场景+作用域（status=0 不参与）
- **说明**：内部接口经 gateway 无 X-Internal-Call 时先被 gateway HMAC 拦截（403 签名校验），与 Task8 记录的"403 仅限内部服务调用"略有差异但语义正确（gateway 层先拦）；use 状态=2 券返回 30016"状态异常"（非 30015——expire job 先标记）
- **无新增缺陷**（T-109 影响 coupon？否——coupon 无 C-05 类秒级时间戳比较，t_coupon_template 时间列仅展示用途，不参与乱序保护；已核对）

## 业务范围
券模板管理（创建/上下线/详情/领券中心列表）+ 领券（Lua 原子扣库存 + MQ 异步落库 + Outbox 兜底）+ 我的券（列表/可用）+ 用券/退券（内部接口，order Feign 调用）+ 定时任务（couponExpireJob xxl#16 / couponReconcileJob xxl#15 / CouponOutboxSenderJob @Scheduled 5s）

## 归属定时/联动任务
- **couponExpireJob（xxl#16，job_group=10，cron `0 * * * * ?`=每分钟，trigger_status=1 已启用）**：t_user_coupon.status 0→2（JOIN 模板 valid_end<NOW()，LIMIT 1000 分批）
- **couponReconcileJob（xxl#15，job_group=10，cron 每分钟，已启用）**：Redis stock vs MySQL remain_count，**以 Redis 为准**修 MySQL；Redis 未初始化→从 MySQL 补
- **CouponOutboxSenderJob（@Scheduled 5s）**：t_coupon_outbox status=0 且 created_at<now-3s → 补发 MQ → markOutboxSent（Redisson 锁 4s lease）

## 用例文档
- **G4-01-coupon.md**：模板/领券/我的券/用券/退券/定时任务（23 用例，代码实证 + 深度 REVIEW）

## 关键数据关注矩阵（代码实证 2026-08-14）
| 用例域 | Redis key | MySQL | MQ |
|---|---|---|---|
| 模板缓存 | `myxhs:coupon:template:{id}`（JSON 30min；空值 "NULL" 60s） | `my_xhs_coupon.t_coupon_template` | — |
| 券库存 | `myxhs:coupon:{templateId}:stock`（String，**无 TTL**；字面花括号 hash tag） | t_coupon_template.remain_count | COUPON_CLAIM_TOPIC |
| 限领 | `myxhs:coupon:{templateId}:claimed:{userId}`（String，无 TTL） | t_user_coupon（uk_claim_no 唯一） | 同上（msgId 幂等 24h） |
| Outbox | `myxhs:lock:job:coupon:outbox`（锁） | t_coupon_outbox | 同上 |

## 执行纪律（G1/G2/G3 教训，先扫 pitfalls #79/#81）
- 服务重启必须带 INTERNAL_TOKEN/ADMIN_TOKEN（#79-1）
- 限流/幂等窗口跨用例共享——执行前 DEL 限流 key（#79-3）
- **coupon 用户端（claim/user/list/user/available）全部需 JWT+HMAC 签名**（无任何 gateway 白名单）；管理端点（template 写）JWT+X-Admin-Call、**免 HMAC**（hmac 白名单）；template/list 公开免签（JWT 白名单）；discount/use/return 内部接口 X-Internal-Call
- **Redis key 字面花括号**：`myxhs:coupon:{{{templateId}}}:stock` 转义（#81-1 教训）
- **claim 后 MySQL 落库 = MQ 消费 + 主从复制延迟**：user/list 立即查会查不到（Task2 链4 实证）——等 1-3s 再断言
- 分页 total 为字符串（R4）
- 管理写端点参数校验在 isAdminCall 之后——先带全 X-Admin-Call 再测负面（#80-4 同款）
- 写后读主从延迟：Redis 写后 sleep 1-2s 再断言
