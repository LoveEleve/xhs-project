# my-xhs 交接文档 v5

> 2026-08-10 | 全部7链代码深审完成 | 46项P0修复 | 12模块文档就绪
> 新AI任务：**继续维护 + engineering-docs + 全链路测试**

---

## 零、当前状态

### 部署
```
22容器 | MySQL 1主1从(3306/3307 GTID) + Redis 1主1从1Sentinel(6379/6380/26379)
+ RocketMQ 3 + ES×2 + Canal + Nacos + Sentinel Dash + XXL-Job + SkyWalking×2
+ VM + Prometheus + Grafana + Logstash + Filebeat + Kibana
```
> config/docker-compose.yml | 启动命令: `docker-compose up -d`

### 代码修复: 46项全部完成

| 链 | 修复数 | 关键项 |
|:--|:--:|------|
| 1 user | 7 | 验证码GETDEL原子化 / 地址锁 / @Transactional×3 / adminToken / blacklist / Redis降级 |
| 2 product | 4 | 延迟双删 / 布隆降级 / Canal flatMessage=false / @Idempotent |
| 3 cart | 5 | SADD条件化 / 对账锁 / CLEAR updatedAt / CHECK UPSERT / 事件序列号 |
| 4 coupon | 6 | 折扣计算(1.5折→正确) / 0元购 / Outbox回滚 / INTERNAL_TOKEN统一 / returnCoupon事务 / Consumer非原子 |
| 5 order+pay | 6 | delayLevel配置化 / refund归属 / 补偿action / 对账游标 / nextRetryTime / 支付去TTL |
| 6 content | 6 | SSE channel统一 / Feed游标开区间 / Feed500全取 / XFF Gateway覆盖 / Like+收藏MQ策略 / es版本优先 |
| 7 notif+im | 4 | 幂等removeMark / WS白名单 / jwt.secret / 聚合窗口动态化 |
| 架构级统一 | 8 | Like/Favorite消费者catch删除versionKey / 补偿Job notifiedKey去重 / onPaymentSuccess竞态静默 / 聚合窗口当天剩余秒 / NOTE_DELETE消费者 / ES版本统一 / adminToken×12 / INTERNAL_TOKEN×10 |

### 文档结构

```
docs/test-2/
├── plans/
│   ├── FULL-CHAIN-RETEST-PLAN.md          ← 总规划(7链+执行顺序+前置依赖)
│   └── DEPLOYMENT-ARCHITECTURE.md         ← 部署架构(22容器)
├── business-docs/                          ← 12模块167端点
│   ├── user/product/cart/coupon/order/     ← 每模块: README + architecture + business-logic + failures + test-plan + 端点文件
│   ├── content-social/inventory/           ← 端点文件含7章模板(源码/业务/前置/ASCII/L2/L3/curl)
│   ├── counter/home/search/
│   └── notification/im/
├── engineering-docs/
│   ├── distributed-transactions.md         ← 四套自实现分布式事务(TCC+事务消息+Outbox+本地消息表)
│   └── fix-plan.md                         ← 8项架构级P0修复方案(已全部执行)
├── execution/
│   └── TEMPLATE.md                         ← 测试执行模板(L1→L4分层验证)
├── methodology/
│   └── TEST-METHODOLOGY.md                 ← 测试方法论(638行)
└── HANDOFF-NEW-AI.md                      ← 本文件
```

---

## 一、文档快速索引

| 优先级 | 文档 | 说明 |
|:--:|------|------|
| 1 | `business-docs/{module}/README.md` | 模块概述(端点清单+架构+Redis Key+MySQL表) |
| 2 | `business-docs/{module}/architecture.md` | 架构分析(调用链路/依赖/端口/数据流/安全/Redis Key) |
| 3 | `business-docs/{module}/business-logic.md` | 业务分析(状态机/异常路径/边界/生命周期) |
| 4 | `business-docs/{module}/failures.md` | **故障分析(已知bug+陷阱+兼容性+全部46项代码级缺陷)** |
| 5 | `business-docs/{module}/test-plan.md` | 测试执行指南(顺序+前置+异常矩阵+数据速查) |
| 6 | `engineering-docs/fix-plan.md` | 8项架构级P0修复的详细方案设计 |

---

## 二、逐模块代码审查方法论（必须遵循）

### 2.1 审查5维度

每个模块审查必须覆盖：
1. **并发安全**: 锁粒度/乐观锁/Lua原子/竞态窗口
2. **数据一致性**: MQ顺序/读写分离/缓存一致性/主从延迟
3. **安全模型**: JWT/HMAC/AdminToken/InternalToken/X-User-Id伪造
4. **故障场景**: Redis不可用降级/MySQL不可用/Feign降级/MQ死信
5. **业务逻辑**: 状态机/异常路径/边界条件/幂等设计

### 2.2 审查流程（教训固化）

1. **先对照链1逐维度检查** — 不能只看自身行数/章节数，必须跨模块对照已验证的标准维度
2. **代码深审必须两层**: 格式层(7章模板+分析文档) → 代码层(5维度实际缺陷)
3. **修复必须全量**: 严重+中等问题全部立即修复，不能以"风险评估"推后
4. **架构级修复需预先设计方案**: 涉及状态机/数据流/DB schema的修复必须写入fix-plan.md并对照代码验证

### 2.3 已知缺陷模式（跨链复发）

| 模式 | 实例 | 检测方法 |
|------|------|------|
| MQ幂等标记阻塞重试 | NotificationEventConsumer + OrderCompensationConsumer | 检查catch中是否removeMark |
| 验证码get+delete非原子 | CaptchaService | 检查Redis操作是否单命令原子 |
| 写后读走从库 | register→login, updateSpu→getSpuDetail | 检查@Transactional覆盖 |
| 锁覆盖不全 | deleteAddress/setDefaultAddress无锁 | 对照同类方法锁覆盖 |
| 时间戳碰撞 | C-05 !isBefore同毫秒跳过 | 检查事件时间戳是否单调 |
| switch-case返回语义不一致 | 折扣券case 2返回折后价vs case 1/3返回减免 | 检查所有分支返回值语义 |

### 2.4 本会话关键教训（新AI必须内化）

| # | 教训 | 实例 |
|:--:|------|------|
| 1 | **按微服务模块拆分，不按业务链** | 初始按7链拆分→遗漏inventory/counter/home/search四个独立服务(41端点) |
| 2 | **审查必须对照链1逐维度** | 单看product architecture 63行觉得"比user简单所以OK"→实际缺Controller表+数据流+RedisKey清单 |
| 3 | **格式全对≠代码没问题** | 链1-5全部7章模板通过→代码深审发现46项实际缺陷 |
| 4 | **grep-only审查=假审查** | grep统计章节数全绿→用户两次"不要糊弄我"纠正→逐行Read发现内容问题 |
| 5 | **修复方案需两轮验证** | fix-plan.md初版3处方案错误(调不存在Bean/加不必要API/依赖不存在代码)→用户纠正执行前对照代码验证 |
| 6 | **INTERNAL_TOKEN不能改空默认** | adminToken改了空默认(fail-closed)是正确的，但INTERNAL_TOKEN空默认会导致全部Feign调用403 |
| 7 | **多Agent并行写文档产生系统性缺陷** | 分析文档空洞(1行/0节占位符)、端点文件缺章节、重复文件——单Agent顺序写更可靠 |
| 8 | **"内部端点"≠不列出** | MQ Consumer是内部触发逻辑(不独立列)，但独立REST服务(如inventory有10个@Mapping端点)必须完全制档 |

---

## 三、环境速查

### 3.1 微服务端口 + 特殊JVM参数

| 服务 | 端口 | 特殊JVM参数 | 原因 |
|------|:--:|------|------|
| gateway | 19000 | — | 唯一入口 |
| user | 19001 | — | |
| content | 19002 | — | |
| analytics | 19003 | **`-Dmanagement.admin-token=my-xhs-admin-token-2026`** | 缺参数启不来/端点403 |
| counter | 19004 | — | |
| order | 19005 | — | |
| product | 19006 | — | |
| coupon | 19007 | — | |
| cart | 19008 | — | |
| payment | 19009 | — | pay.type=mock(默认) |
| inventory | 19010 | — | |
| notification | 19013 | **`--spring.profiles.active=dev`** | 缺参数T09 404 |
| im | 19014 | **`-Djwt.secret=MyXhs@2026#JwtSecretKey!ForTokenSign`** (≥256位) | 缺参数W01 500 |
| home | 19015 | — | |
| search | 19016 | — | |

### 3.2 中间件端口

```
MySQL:    3306(Master) 3307(Slave,GTID)
Redis:    6379(Master) 6380(Slave) 26379(Sentinel)
RocketMQ: 9876(NS) 11911(Broker) 18081(Dashboard)
ES:       19200(业务,IK) 19201(SkyWalking存储)
Nacos:    18848  |  Sentinel Dashboard: 8858  |  XXL-Job: 18080
SkyWalking:11800(gRPC) 12800(OAP-HTTP) 8080(UI)
Prometheus:19090 | Grafana: 13000 | VictoriaMetrics: 8428
Logstash: 15044(tcp) 15045(beats) | Kibana: 15601
```

### 3.3 测试数据

| 数据 | 值 | 用途 |
|------|------|------|
| 测试用户 | chaintest_u1 / Test@123456 | 链1注册 |
| 第二用户 | chaintest_u2 / Test@123456 | block/关注测试 |
| Token获取 | `cat /tmp/test_token.txt` (30min过期) | 链1 U03登录 |
| ADMIN_TOKEN | `my-xhs-admin-token-2026`(环境变量注入) | 管理端点 |
| INTERNAL_TOKEN | `my-xhs-internal-token-2026` | 内部端点 |

### 3.4 全局配置值

| 配置 | 值 | 位置 |
|------|------|------|
| ADMIN_TOKEN默认 | `${ADMIN_TOKEN:}` (空=fail-closed) | 12个application.yml |
| INTERNAL_TOKEN默认 | `my-xhs-internal-token-2026` | 10个application.yml(全部统一) |
| JWT secret | `MyXhs@2026#JwtSecretKey!ForTokenSign` (36字节=288位) | user/gateway/im |
| delayLevel | `${order.close.delay-level:16}` (16=30min, 5=1min测试) | OrderService |
| flatMessage | `false` | canal.properties |

### 3.5 部署后验证清单

```bash
# 1. Nacos 15服务
for s in gateway user content analytics counter product cart coupon inventory order payment notification im home search; do
  curl -s "http://21.130.247.89:18848/nacos/v1/ns/instance/list?serviceName=my-xhs-$s&namespaceId=my-xhs" | python3 -c "import json,sys;print('$s:',len(json.load(sys.stdin)['hosts']))"
done

# 2. MySQL 主从
mysql -h 21.130.247.89 -P 3307 -u root -p'Xhs@2026#MySQL' -e "SHOW SLAVE STATUS\G" | grep -E "Running|Error"
# 预期: Slave_IO_Running: Yes, Slave_SQL_Running: Yes

# 3. Redis Sentinel
python3 -c "import redis; r=redis.Redis(host='21.130.247.89',port=26379,password='Xhs@2026#Redis'); m=r.sentinel_master('mymaster'); print(f'Master: {m[b\"ip\"]}:{m[b\"port\"]} slaves={m[b\"num-slaves\"]}')"
# 预期: Master: 21.130.247.89:6379 slaves=1

# 4. Gateway health
curl -s http://21.214.97.212:19000/actuator/health | python3 -c "import json,sys;d=json.load(sys.stdin);print('status:',d['status'],'redis:',d['components']['redis']['status'])"
# 预期: status: UP redis: UP
```

---

## 四、关键编码规范

### 4.1 `@Value` 精确路径匹配
Spring Boot `@Value("${spring.data.redis.business.port:16381}")` 只找 `business.port` 这个精确路径。父属性 `port:6379` 不会自动填充子属性。必须显式配置 `business.port` 和 `cache.port`。

### 4.2 `@Transactional` 强制主库读
所有写后需要立即读最新数据的业务方法必须加 `@Transactional`，防止 `ReadWriteRoutingInterceptor` 将读请求路由到从库（主从延迟窗口内返回旧数据）。

### 4.3 MQ幂等标记时机
MQ消费者中幂等标记必须在业务执行成功后写入，异常时必须removeMark再throw。先标记后业务=重试窗口归零。

### 4.4 延迟双删 vs 逻辑过期
- 延迟双删(用户模块): afterCommit立即删 + 1s后二次删
- 逻辑过期(产品模块): 返回旧值 + 异步重建 + 互斥锁防击穿
- 不存在"哪种更好"—取决于是否可接受短暂脏读

---

## 五、剩余工作

### 5.1 engineering-docs (7篇)

| 文档 | 说明 |
|------|------|
| service-dependency-map.md | 16微服务Feign调用+MQ订阅关系图 |
| cache-strategy.md | 各模块Redis策略(布隆/逻辑过期/延迟双删/CacheAside) |
| failover-scenarios.md | 各中间件故障时的系统行为+恢复步骤 |
| monitoring-pipeline.md | SkyWalking→ELK→Prometheus→Grafana完整管道 |
| middleware-topology.md | 22中间件角色/端口/依赖关系 |
| security-model.md | JWT/HMAC/AdminToken/InternalToken/白名单 |
| deployment-guide.md | 部署流程+初始化+验证清单 |

### 5.2 全链路测试（Task 2）
- 按 `business-docs/{module}/test-plan.md` 执行
- 使用 `execution/TEMPLATE.md` 格式记录
- 测试顺序: 链1→链2→链3→链4→链5→链6→链7
- 一curl→一文件→L1→L4验证

### 5.3 后续维护
- 新模块: 必须按5维度代码审查 → 4分析文档(README+architecture+business-logic+failures) → test-plan
- 新部署: 必须更新 deploy-verification.md + 运行验证清单
- 新Bug: 写入对应模块 failures.md §五 代码级缺陷
