# my-xhs 交接文档 — Task 3（全项目 Review 交接）

> 2026-08-10 | 承接 Task2 测试 + 多轮深度 Review 的成果。给下一个 AI 的全量交接。

---

## 零、当前状态速览

```
15 服务 UP (Sentinel模式) | 远程中间件正常 | Token→/tmp/test_token.txt
已修复BUG: #39-52 (共14项) | 待修复P0: P0-1~P0-8 (方案已定,未实施)
运维缺口: O1-O4 (方案已定,未实施) | 文档栈齐全(见§八)
```

### 关键环境
- 服务端口: gateway 19000, user 19001, content 19002, analytics 19003, counter 19004, product 19006, cart 19008, inventory 19009, coupon 19010, order 19011, payment 19012, notification 19013, im 19014, home 19015, search 19016
- 远程中间件 21.130.247.89: MySQL 3306/3307, Redis 6379, Nacos 18848, ES 19200, RocketMQ 9876, SkyWalking 11800/8080, SentinelDash 8858, Canal 11110, Kibana 15601
- 凭据: chaintest_u1/u2 / Test@123456; ADMIN_TOKEN=my-xhs-admin-token-2026; INTERNAL_TOKEN=my-xhs-internal-token-2026

### 信任模型（关键）
- **前端只走 gateway(19000)**，服务端口仅供内部 Feign（X-Internal-Call）。
- 写操作需 HMAC 签名（前端签名），内部端点需 X-Internal-Call。
- gateway 鉴权路径已用 set() 覆盖 X-User-Id 防伪造。

---

## 一、已修复的问题（#39-#52，全部验证闭环）

| # | 问题 | 说明 |
|:--:|------|------|
| 39 | HMAC 写接口未强制（白名单污染） | gateway 白名单裁剪，写操作强制签名 |
| 40 | 订单 skuImage null | SkuVO/SkuInfoDTO 加 image，SPU 继承 |
| 41 | 订单地址 Mock | OrderService 取用户真实地址 |
| 44 | 缺可领券模板列表接口 | 新增 GET /api/coupon/template/list |
| 45 | mvn clean 删 jar 致懒加载失败 | 重打包+重启 |
| 46 | spu-detail skuList.image null（双 toSkuVO） | SpuService.toSkuVO 补 image |
| 48 | 通知消费者崩溃（selectByType deleted+大小写） | 修 mapper，通知可生成 |
| 49 | WS 握手被 HMAC 拦 | /api/im/ws/** 加回 HMAC 白名单 |
| 50 | dev 端点 404 | start-all.sh 加 JAVA_OPTS_DEV |
| 51/52 | product prometheus 67s → 指标缺失 | DlqMetrics 改缓存，Gauge 只读缓存 |

**其他**: 搜索索引重建、Sentinel 模式恢复（replica-announce-ip）、coupon 测试日期格式等。

---

## 二、待修复 P0（方案已定，见 FIX-PLAN-V2-FULL.md，未实施）

| P0 | 问题 | 风险 | 批次 |
|:--:|------|:--:|:--:|
| 1 | 补偿消费者库存泄漏 | 低 | 1 |
| 2 | 已取消订单可支付/竞态无退款 | 中高 | 2 |
| 3 | release.lua 参数缺失 | 低 | 1 |
| 4 | coupon batchExpire 语法错 | 低 | 1 |
| 5 | search 补偿漏跨库前缀 | 低 | 1 |
| 6a | HMAC 不签 body（**破坏性，需前端协同**） | 高 | 3 |
| 6b | HMAC Redis 故障500 | 低 | 1 |
| 7a | 网关白名单不清 X-User-Id | 低 | 1 |
| 7b/7c | token 默认值/明文密码（**部署同步**） | 中 | 3 |
| 8 | 事务消息+本地消息表双投递 | 中 | 1 |

> REVIEW 结论: P0-6a 必须做签名过渡(双签名), P0-1 需先加载 Order 取 orderNo, P0-2 需 pay 回查降级+幂等退款, P0-7b/7c 需全量重启且环境变量已设。
> 第一批(安全): P0-1/3/4/5/6b/7a/8; 第二批: P0-2; 第三批(协同): P0-6a/7b/7c

---

## 三、运维缺口（方案已定，见 REVIEW-V2 十一，未实施）

| # | 缺口 | 修复方法 |
|:--:|------|------|
| O1 | MDC userId 恒空 | HTTP 层写 userId 到 MDC |
| O2 | product SPU_ASYNC_EXECUTOR 裸池丢 traceId | 改 MdcAwareExecutorService |
| O3 | cart 裸单线程池(minor) | 同 O2 |
| O4 | 自定义业务指标少 | 核心链路加 Counter/Timer |

---

## 四、执行规范（沿用）

- 前端只走 gateway(19000)，写操作 HMAC，内部端点 X-Internal-Call 直连服务端口。
- 一curl一文件，L0→L4 逐层验证（禁只验 HTTP）。
- 改码后必须 `mvn package -pl {module} -am` + **重启对应服务**（mvn clean 会删运行中 jar → 懒加载失败，#45）。
- 服务重启用 start-all.sh（setsid 可靠）；`pgrep -f` 用 `[m]` 括号防自匹配。
- 验证分片数据先 `SHOW DATABASES` 定位物理库。
- 修复后更新 pitfalls.md。

---

## 五、测试数据/环境注意
- 通知前提构造：让 u2 对 u1 关注/点赞/评论生成通知（已修 #48）。
- 领券/下单异步落库，需等消费。
- 预置 feed(user 10001) 引用不存在的 noteId → feed 空；H01-feed 有 `reverseRangeByScoreWithScores` 参数颠倒 bug（REVIEW-V2 七）。
- 库存桶被 flush 需重 init（§5.1）。

---

## 六、启动/运维命令速查
- 重启单服务：kill + `setsid ./start-all.sh`（start-all.sh 用 PID 文件跳过已运行）
- 健康检查：`for p in 19000..19016; curl /actuator/health`
- Prometheus job 名：`my-xhs-services`（非服务名）
- 全量重启（改 common 后）：kill 全部 + start-all.sh

---

## 七、文档地图（docs/test-2/）

| 文档 | 内容 |
|------|------|
| **HANDOFF-TASK3.md** | 本交接 |
| HANDOFF-TASK2-TEST.md | 原测试交接（七链） |
| HANDOFF-NEW-AI.md | 代码审查方法论(5维度/缺陷模式) |
| REVIEW-V2.md | 8模块深审 + P0清单 + 运维Review(O1-O4) |
| FIX-PLAN-V2-FULL.md | P0 修复方案 + 深度REVIEW结论 |
| TEST-REFERENCE-V2.md | 149端点(内部/gateway) + 正确格式 |
| METHODOLOGY-L2L3-SUPPLEMENT.md | L2数据验证 + L3九透镜 + 真实限流参数 |
| ALIAS-MAPPING.md | 端点ID映射 |
| DATA-RECONCILIATION.md | 数据对账 |
| business-docs/{module}/ | 12模块分析 + test-plan(L0-L4清单) |
| engineering-docs/ | 9篇工程文档 |
| execution/ | 每端点测试结果 |
| execution/pitfalls.md | 52+项踩坑 |

---

## 八、下一步（待执行）
1. **P0 第一批修复**（P0-1/3/4/5/6b/7a/8）→ 第二批 P0-2 → 第三批 P0-6a/7b/7c（协同）
2. **O1-O4 运维专项**
3. **全项目手把手 REVIEW**（本人亲自，非子代理）
4. 修复后逐项验证 + 更新 pitfalls

---

## 九、本次交接后要求
> 新 AI 接到本文档后：① 先复核文档与实际环境一致；② 按 §八 计划推进，优先 P0 第一批；③ 亲自逐模块 Review（不批量委派子代理）；④ 每项修复走"改码→重打包→重启→验证→记录"闭环。
