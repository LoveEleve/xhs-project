# my-xhs 全链路测试方法论

> v2.0 | 2026-08-08 | 从94→106端点 + 全链路重测规划中提炼
> 核心思想：**业务逻辑正确性为基底，再逐层验证数据/性能/分布式/微服务**

---

## 零、分层验证模型

测试不是"curl 200 + 查数据"——是分层递进的系统验证：

```
                    ┌──────────────────────────────┐
                    │  Layer 4: 可观测性            │
                    │  SW trace + Prometheus + Kibana│
                    ├──────────────────────────────┤
                    │  Layer 3: 生产级质量           │
                    │  性能/可扩展/分布式/微服务/并发 │
                    ├──────────────────────────────┤
                    │  Layer 2: 数据正确性           │
                    │  Redis + MySQL + MQ + ES      │
                    ├──────────────────────────────┤
                    │  Layer 1: 业务逻辑正确性       │
                    │  业务流转+状态机+边界场景       │
                    └──────────────────────────────┘
```

**不可越层**: Layer 1 未通过 → 不谈 Layer 2。Layer 2 未通过 → 不谈 Layer 3。

---

## 一、Layer 1: 业务逻辑正确性（基底，必须先通过）

### 1.1 每个端点必须回答三个问题

| 问题 | 方法 | 输出位置 |
|------|------|------|
| **这个端点做什么？** | 读 Controller + Service 源码 → 3-5句业务描述 | 执行文件 §业务逻辑 |
| **它触发了哪些下游？** | 读 Feign/MQ/Consumer/XXL-Job 源码 → 画 ASCII 流转图 | 执行文件 §ASCII 流转图 |
| **它的完整业务流程通吗？** | 按状态机走完 → 验证每步的预期状态转换 | 执行文件 §业务链验证 |

### 1.2 业务逻辑验证模板

每端点执行文件必须包含：

```
§ 业务逻辑（3-5句）
  - 这个接口的核心功能是什么？
  - 输入什么？产出什么？
  - 触发哪些下游操作？（Feign/MQ/定时任务）

§ ASCII 流转图
  curl → Gateway → 微服务 → MySQL/Redis/MQ/ES

§ 业务链验证（业务逻辑是否正确）
  - 状态转换: 下单(status=0) → 支付(status=0→1) → 发货(1→2) → 收货(2→3)
  - 数据流: 订单表 → 库存预扣 → 支付表 → 库存确认 → 完成
  - 边界: 重复下单(幂等)、库存不足(拒绝)、未支付关单(超时)
  - 回滚: 取消订单 → 释放库存 → 退还优惠券
```

### 1.3 业务逻辑审查9透镜

| 透镜 | 每端点检查 | 发现示例 |
|------|------|------|
| **1. 业务自洽** | 业务流程是否完整？状态机是否闭环？ | D01→I02→M01→D10→I03 全链路是否通？ |
| **2. 数据一致** | Redis ↔ MySQL ↔ ES 是否一致？ | Redis preDeduct 扣了但 MySQL locked_stock 没写？ |
| **3. 幂等安全** | 重复请求是否幂等？分布式锁是否正确？ | 重复下单 → 40201 拒绝还是双扣？ |
| **4. 回滚完整** | 失败/取消是否完整回退？ | 关单 → 库存释放 + 券退还 是否都执行？ |

#### 深层五透镜（L3逐项检查，每个端点必须有实际检查项，禁止✅占位）

##### 5. 性能

| 检查项 | 方法 | 示例 |
|------|------|------|
| RateLimit生效？ | 连续调用触发限流→应返回限流错误 | P01 5次/60s → 第6次应429 |
| 响应时间合理？ | X-Trace-Id → SkyWalking UI 查实际RT | D01下单 <200ms正常 |
| 缓存命中？ | 查Redis TTL变化确认缓存生效 | P03 Cache Aside 30min → 第二次访问TTL<原始值 |
| 连接池状态？ | grep HikariCP active connections | active < max pool size |

##### 6. 可扩展

| 检查项 | 方法 | 示例 |
|------|------|------|
| 分片路由正确？ | userId%4 → 确认数据在正确分片 | ORDER shard = my_xhs_order_{userId%4} |
| Redis Cluster对齐？ | 检查key的hash tag是否同slot | cart:{userId}:items/checked/sort 用{userId}对齐 |
| MQ消费者可扩展？ | consumer group 可多实例 | inventory-order-transaction-consumer-group |

##### 7. 微服务

| 检查项 | 方法 | 示例 |
|------|------|------|
| Feign调用成功？ | grep目标服务日志确认跨服务调用到达 | cart→product GET /api/product/sku/batch |
| Feign降级有效？ | 停目标服务→检查返回降级值而非抛异常 | inventory宕机→cart列表"商品信息获取失败" |
| Sentinel限流？ | Gateway yml rate-limit-qps 配置 | cart-service qps=50 |
| Nacos健康？ | curl Nacos instance list ≥1 | my-xhs-{service} healthy=true |

##### 8. 并发

| 检查项 | 方法 | 示例 |
|------|------|------|
| 分布式锁？ | 查Redisson lock key+锁超时<fixedRate | PreDeductTimeoutJob leaseTime=40s < 60s |
| Lua原子操作？ | 读Lua脚本确认多key同slot | claim_coupon.lua KEYS[1..2]同{templateId} |
| @Idempotent有效？ | 同参数5s连调2次→第1次200第2次拒绝 | A01点赞 @Idempotent 5s防重复 |
| 线程池合理？ | grep executor pool size | searchExecutor pool-size=200 |

##### 9. 安全

| 检查项 | 方法 | 示例 |
|------|------|------|
| JWT鉴权？ | 不带token → 401 | curl without Authorization header |
| X-Admin-Call校验？ | 不带或错误token → 403 | admin端点不带X-Admin-Call returns 403 |
| X-Internal-Call校验？ | 检查fail-open/fail-closed | INTERNAL_TOKEN空→coupon所有内部端点fail-closed永久403 |
| X-User-Id防伪造？ | Gateway set()覆盖客户端传入值 | Header传X-User-Id:999→JWT注入覆盖 |
| 敏感数据脱敏？ | 响应中手机号/身份证脱敏 | receiverPhone显示138****8000 |
| 密码安全？ | MySQL存储BCrypt hash | $2a$10$... 格式，非明文 |

### 1.4 禁止

- 禁止不读源码凭直觉写业务逻辑
- 禁止跳过边界场景（幂等/回滚/超时/并发）
- 禁止不画完整流转图就开始 curl
- 禁止用"这很简单"跳过业务逻辑分析
- **禁止批量 curl 多个端点** — `curl A && curl B`、`for ... curl`、连续测试不写执行文件一律禁止
- **业务链可连续但必须先声明** — `D01→I02→D08→I03` 这种下游自动触发的链可以说"此链需连续测试"，简单端点不得连续
- **禁止空结果不验证数据源** — API返回0/[]/{}时必须查MySQL/Redis确认是**真0**还是**查询失败**；W03 messages返回0但t_chat_message表在另一个库是典型案例
- **禁止无前置数据测依赖链路** — 端点依赖上游数据时必须先创建前置条件

### 1.5 业务链依赖顺序（测试前必查）

| 后置端点 | 前置条件 | 不满足后果 |
|------|------|------|
| D01下单 | I01库存初始化 + B01加购 + U06地址 | 库存未初始化→下单拒绝 |
| D05取消订单 | D01已下单(status=0) | 无订单可取消 |
| M01支付 | D01已下单 | 无订单可支付 |
| M03退款 | M01已支付 | 无支付记录可退 |
| C07 Feed推送 | 有粉丝关注作者 | 只测空推送分支 |
| A14共同关注 | 两人都关注同一目标 | 交集为空 |
| A10关注 | 目标用户存在 | 关注失败 |
| B01加购 | SKU已创建(P06) | 加购失败 |
| N04领券 | 模板已创建+上架(N01+N02) | 领券失败 |

> ⚠️ **本会话累计被纠正 20+ 次**！

---

## 二、Layer 2: 数据正确性

### 2.1 七层数据验证

| 层 | 方法 | 禁止 |
|------|------|------|
| HTTP | curl -i → 状态码 + X-Trace-Id + key字段值 | 禁止只查 200 |
| Redis | python3 → key名 + 值 + TTL | 禁止凭猜测写 key 格式 |
| MySQL | mysql -e → 行增/改/删 + 字段值 | 禁止"确认✅"占位 |
| MQ | grep Consumer日志 → topic + 消费关键词 | 禁止跳过 |
| ES | curl ES/_search → 文档数 | 禁止跳过（Canal有延迟） |
| SkyWalking | traceId + UI http://21.130.247.89:8080 | 禁止只记不用 |
| Prometheus | curl actuator/prometheus → 指标计数 | 禁止省略 |
| Kibana | ES myxhs-logs-* → traceId日志 | 禁止省略 |

### 2.2 下游覆盖原则

**内部端点 = HTTP 端点的下游效果，不独立测试。**

```
D01下单 → [MQ事务消息] → I02预扣（验证Redis库存变化）
D01下单 → [MQ延时消息] → OrderCloseConsumer（验证关单日志）
M01支付 → [Feign回调]  → D10 pay-success（验证订单状态变更）
```

---

## 三、Layer 3: 生产级质量（Layer 1+2 通过后）

### 3.1 5/6/7/8/9 透镜审查

| 透镜 | 检查 |
|------|------|
| **5. 性能** | 响应时间 < 预期？RateLimit 是否生效？缓存是否命中？ |
| **6. 可扩展** | 分库分片是否命中？Redis Cluster hash tag 是否正确？ |
| **7. 微服务/SCA** | Feign 降级是否生效？Sentinel 限流是否配置？ |
| **8. 并发** | Lua 原子操作是否正确？分布式锁是否防并发？ |
| **9. 安全** | 鉴权(JWT)是否生效？X-Admin-Call 是否校验？X-Internal-Call fail-open/fail-closed？ |

### 3.2 后台保障

每链结束后必须检查：
- XXL-Job 注册状态 + 触发日志（全部 1min cron）
- 对账端点（F03 counter reconcile, coupon reconcile, inventory reconcile）
- MQ 消息重试/死信处理日志
- @Scheduled 补偿任务执行日志（Outbox/Timeout/Compensation）

### 3.3 可观测性

每链结束后必须验证：
- SkyWalking UI: traceId 完整跨服务调用链
- Prometheus: 各服务 actuator 指标
- Kibana/ES: myxhs-logs-* 集中日志

---

## 四、环境配置

### 4.1 定时任务全部 ≤1 分钟

```bash
# XXL-Job
curl -b /tmp/xxl_cookie "http://21.130.247.89:18080/xxl-job-admin/jobinfo/pageList?jobGroup=1"
# 全部应为: "scheduleConf":"0 * * * * ?"

# @Scheduled (Redisson锁的 leaseTime 必须 < fixedRate)
grep -rn "@Scheduled" */src/main/java/
```

| 本会话已修改 | 修改前 | 修改后 |
|------|--------|--------|
| HotSearchService | 300s | 60s |
| PreDeductTimeoutJob | 300s+leaseTime 240s | 60s+leaseTime 40s |
| IncrementalIndexSyncJob | 300s | 60s |

### 4.2 基础设施速查

- Redis: Sentinel 主=16379，`python3 import redis; r=redis.Redis(host='21.130.247.89',port=16379,...)`
- MySQL: 远程 root 无 CREATE 权限；分库 `my_xhs_order_{userId%4}`
- Admin Token: `X-Admin-Call: my-xhs-admin-token-2026`
- ⚠️ **INTERNAL_TOKEN**: coupon/cart/inventory 默认空 → Feign 403

---

## 五、文档规范

### 5.1 文档体系

```
docs/test-2/
├── METHODOLOGY.md              ← 本文件：测试方法论（为什么这样做）
├── FULL-CHAIN-RETEST-PLAN.md   ← 执行命令（curl + 验证 + 155端点覆盖映射）
├── HANDOFF-20260808.md          ← 交接入口（基础设施 + 覆盖表 + 启动流程）
└── execution/
    ├── README.md                ← 执行索引（文件清单 + 进度）
    ├── pitfalls.md              ← 踩坑记录（22项 + 预防措施）
    ├── D01-order-create.md      ← 每端点独立执行文件
    ├── A01-social-like.md
    └── ...（30+ 个独立端点文件）
```

### 5.2 每端点独立执行文件

文件名: `{端点ID}-{描述}.md`

必须含:
1. **§ 业务逻辑** — 3-5 句 + 状态机 + 边界场景
2. **§ ASCII 流转图** — Gateway→服务→中间件 完整路径
3. **§ 业务链验证** — 这条链是否完整通？状态转换是否正确？
4. **§ 数据验证** — Layer 2 七层验证表（每层有实际数值）
5. **§ 生产级检查** — Layer 3 9透镜（至少检 1-4 透镜）
6. **§ 隐藏/内部链路** — 该端点自动触发的下游（Feign/MQ/Job）验证结果
7. **§ curl 命令** — 可复制执行
8. **§ 踩坑/修复** — 仅当有异常时

**禁止**: 合并多端点为一个文件

### 5.3 覆盖表同步更新

每端点测试后**立即**更新:
- HANDOFF §二 覆盖表
- execution/README.md 索引

### 5.4 踩坑即时记录

每发现新问题**立即**写入 pitfalls.md（不等"汇总"）

---

## 六、已知陷阱速查

| # | 陷阱 | 解决 |
|:--:|------|------|
| 1 | Token 30min 过期 | 重新登录 |
| 2 | 中文参数 curl 400 | `--data-urlencode` |
| 3 | mvn -am 污染 common | 单独 -pl |
| 4 | Redis key 含花括号 {} | 带花括号 hash tag |
| 5 | 订单分库 userId%4 | `my_xhs_order_$((userId%4))` |
| 6 | ES Canal 同步延迟 | 等 5-10s |
| 7 | notification @Profile("dev") | `--spring.profiles.active=dev` |
| 8 | Gateway HMAC 白名单不全 | grep hmac-white-list |
| 9 | INTERNAL_TOKEN 空值 | 业务链覆盖 |
| 10 | PayCallbackSimulator 不工作 | payType=99 |
| 11 | A02/A07 DELETE+JSON body | curl -X DELETE -d |
| 12 | A10/A11 目标在PATH | `/follow/{targetUserId}` |

---

## 七、执行检查清单

**每个端点前**:
- [ ] 已读 Controller 源码确认路径/参数/DTO字段
- [ ] 已读 Service/Consumer 源码确认下游调用链
- [ ] 已画出完整 ASCII 流转图
- [ ] 已写出 3-5 句业务逻辑描述

**每个端点后（Layer 1）**:
- [ ] 业务链是否完整通？状态转换是否正确？
- [ ] 边界场景是否验证？（幂等/回滚/超时）
- [ ] 业务逻辑描述 + ASCII图已写入执行文件

**每个端点后（Layer 2）**:
- [ ] HTTP 状态码 + X-Trace-Id + key字段值 已验证
- [ ] Redis key/值/TTL 已验证
- [ ] MySQL 增/改/删 已验证
- [ ] MQ Consumer 消费日志已验证
- [ ] ES 文档同步已验证（如有 Canal）

**每个端点后（Layer 3）**:
- [ ] 性能(RateLimit/响应时间)是否正常？
- [ ] 分布式(Lua原子/分布式锁)是否正确？
- [ ] 微服务(Feign降级/Sentinel)是否生效？
- [ ] 安全(JWT/Admin/Internal token)是否校验？

**每链完成后（Layer 3+4）**:
- [ ] 该链涉及的 XXL-Job 日志已 grep
- [ ] 该链的对账端点已验证
- [ ] Prometheus actuator 指标已查
- [ ] Kibana traceId 日志已查
- [ ] SkyWalking UI 全链路 trace 已确认
- [ ] MQ 补偿/重试/死信日志已查
- [ ] HANDOFF §二 覆盖表已更新

---

## 六、失败诊断与修复协议

curl 返回非200时报错≠直接写"未通过"跳过——必须按以下步骤修复：

```
1. 读完整响应 → 确认错误码和错误消息
2. 查服务日志 → grep -i "error\|exception" /tmp/r_{service}.log
3. 定位根因 → 读源码(Controller/Service/配置)找精确原因
4. 修复 → 改代码/改配置/加白名单/启服务
5. 重编 → mvn package -pl my-xhs-{service} -DskipTests (不用-am)
6. 重启 → fuser -k {port}/tcp && nohup java ... &
7. 验证 → 等Nacos注册(≥1实例) → 重跑 curl 确认修复
8. 全链重测 → 修复后从头重走整条业务链
```

### 常见失败速查

| 错误码 | 根因 | 修复 |
|------|------|------|
| 401 | Token过期/无效 | 重登录: curl captcha→登录→token > /tmp/test_token.txt |
| 403 HMAC签名 | Gateway白名单缺失 | grep hmac-white-list → 加路径 → 重编Gateway |
| 403 管理员 | X-Admin-Call缺/错 | Header: X-Admin-Call: my-xhs-admin-token-2026 |
| 404 | 服务未注册Nacos | 查进程→查Nacos→重启服务 |
| 500 WeakKeyException | JWT密钥<256位 | -Djwt.secret=...32+字节 → 重启IM |
| Could not resolve placeholder | 缺失JVM参数 | 如 -Dmanagement.admin-token=... → 重启analytics |
| Unable to access jarfile | JAR未编译 | mvn package -pl {service} |

## 七、新链复位陷阱

**每条新链的第一个文件初版必然缺少L3+L4**。这是跨链持久模式——即使前链刚被审计修复，神经系统在新任务开始时重置为默认模板。

防御措施：
- **每链首文件Write后立即grep审计**: `grep -c "生产级\|Prometheus" "$f"` → L3=0或L4=0 →立即cat >>补全
- **不依赖"记住了"**——chain2/3/4/5/6/7全部复发此模式

## 八、数据库/端口交叉索引

| 数据库 | 端口 | 表(示例) |
|------|:--:|------|
| my_xhs_user | 13306 | t_user, t_user_address |
| my_xhs_im | 13306 | t_chat_message, t_chat_user_relation |
| my_xhs_content | 13307 | t_note, t_comment, t_like, t_favorite, t_follow |
| my_xhs_coupon | 13307 | t_coupon_template, t_user_coupon, t_coupon_outbox |
| my_xhs_cart | 13307 | t_cart_item |
| my_xhs_product | 13307 | t_spu, t_sku |
| my_xhs_order_{0..3} | 13308 | t_order, t_order_item, t_order_event, t_local_message |
| my_xhs_payment | 13308 | t_payment, t_refund |
| my_xhs_inventory | 13309 | t_inventory, t_inventory_outbox |

> ⚠️ IM表在13306非13307！首次查错库导致空输出误判COUNT=0

## 参考

- 执行命令: `../plans/FULL-CHAIN-RETEST-PLAN.md`
- 交接入口: `../HANDOFF-20260808.md`
- 踩坑记录: `../execution/pitfalls.md`

---

## 九、测试前工程检查清单

每次测试会话开始前执行，一项失败即停止：

```bash
# 1. 16服务进程 + Nacos注册
ps aux | grep "my-xhs-" | grep java | grep -v grep | wc -l  # 应≈16
for s in user content analytics counter product cart coupon inventory order payment notification im home search gateway; do
  c=$(curl -s "http://21.130.247.89:18848/nacos/v1/ns/instance/list?serviceName=my-xhs-$s&namespaceId=my-xhs" | python3 -c "import json,sys;print(len(json.load(sys.stdin).get('hosts',[])))")
  [ "$c" -eq 0 ] && echo "❌ my-xhs-$s: 0 instances"
done

# 2. Gateway响应
curl -s -o /dev/null -w "%{http_code}" http://localhost:19000/api/user/auth/captcha
# 应返回200

# 3. 缺服务快速启动模板
fuser -k {port}/tcp 2>/dev/null; sleep 3
nohup java -javaagent:/data/workspace/my-xhs/skywalking-agent/skywalking-agent.jar \
  -Dskywalking.agent.service_name=my-xhs-{service} \
  -Dskywalking.collector.backend_service=21.130.247.89:11800 \
  -Xms512m -Xmx512m \
  -jar /data/workspace/my-xhs/my-xhs-{service}/target/my-xhs-{service}-1.0-SNAPSHOT.jar \
  < /dev/null > /tmp/r_{service}.log 2>&1 &

# 4. 特殊JVM参数
# notification: --spring.profiles.active=dev
# analytics:   -Dmanagement.admin-token=my-xhs-admin-token-2026
# im:          -Djwt.secret=xhs-test-secret-key-2026-my-xhs-project-imag-service

# 5. Token就绪
cat /tmp/test_token.txt 2>/dev/null && curl -s -o /dev/null -w "%{http_code}" \
  http://localhost:19000/api/user/me -H "Authorization: Bearer $(cat /tmp/test_token.txt)"
# 401→token过期,需重新登录

# 6. XXL-Job 7任务全部1min cron
curl -s -b /tmp/xxl_cookie "http://21.130.247.89:18080/xxl-job-admin/jobinfo/pageList?jobGroup=1" \
  | python3 -c "import json,sys;[print(f'{j[\"id\"]}:{j[\"scheduleConf\"]}') for j in json.load(sys.stdin)['data']]"
```

## 十、每链完成工程验证

每条业务链测试完毕后，执行以下工程检查：

### 10.1 可观测性快照

```bash
# 每个服务的Prometheus指标
for port in 19001 19002 19003 19004 19006 19008 19009 19010 19011 19012 19013 19014 19015 19016; do
  echo "=== :$port ==="
  curl -s "http://localhost:$port/actuator/prometheus" 2>/dev/null | grep 'http_server_requests_seconds_count{.*uri=' | grep -v '/**' | head -3
done

# Kibana日志完整性: 随机抽一个traceId验证
curl -s -u elastic:'Xhs@2026#Elastic' "http://21.130.247.89:19200/myxhs-logs-*/_search?size=3&q=traceId:{TRACE_ID}" -H 'Content-Type: application/json' | python3 -c "import json,sys;print(f'logs:{json.load(sys.stdin)[\"hits\"][\"total\"]}')"
```

### 10.2 数据一致性校验

```bash
# Redis库存 vs MySQL库存
python3 -c "
import redis; r=redis.Redis(host='21.130.247.89',port=16379,password='Xhs@2026#Redis')
# 对比 Redis inventory total vs MySQL t_inventory.available_stock
"

# Counter对账(F03)
curl -s -X POST http://localhost:19000/api/counter/reconcile -H "Authorization: Bearer $TOKEN" -H "X-Admin-Call: my-xhs-admin-token-2026"
```

### 10.3 MQ积压检查

```bash
# RocketMQ Dashboard消费进度
curl -s "http://21.130.247.89:18081/consumer/groupList.query" 2>/dev/null | python3 -c "
import json,sys
for g in json.load(sys.stdin).get('data',[]):
    print(g)
"
```

## 十一、代码修复质量门

发现bug并修复代码后，必须通过以下检查才能宣称修复完成：

### 11.1 修复自检清单

```
[ ] 根因已定位到源代码级别(具体文件+行号)
[ ] 修复是标准方案(非workaround/绕过/手动INSERT)
[ ] 影响范围已评估(只改了目标服务? 改了common模块需重编所有依赖?)
[ ] 编译通过: mvn package -pl {service} -DskipTests (不用-am)
[ ] 服务重启后Nacos注册≥1实例
[ ] 受影响的全链路从头重测通过
[ ] 边界场景已验证(回滚/幂等/并发)
```

### 11.2 编译安全规则

```
- 单服务修改: mvn package -pl {service} -DskipTests (不用-am)
- common模块修改: mvn package -pl my-xhs-common -DskipTests → 所有依赖服务需重启
- 多个服务修改: 逐个编译(不用-am),逐个重启
- ⚠️ -am 重编common会导致已运行服务启动后立即优雅停机
```

### 11.3 回归测试触发规则

| 修改范围 | 必须重测的链 |
|------|------|
| user服务 | 链1用户 |
| product服务 | 链2产品 + 链3购物车(Feign product) |
| cart服务 | 链3购物车 + 链5订单(购物车数据) |
| coupon服务 | 链4券 + 链5订单(领券/核销) |
| inventory服务 | 链5订单(库存预扣/确认/释放) |
| order服务 | 链5订单全生命周期 |
| payment服务 | 链5支付+退款 |
| content服务 | 链6内容社交(C07/A01/C01) |
| Gateway yml | 受影响的端点路径全部重测 |
| common模块 | 全部7链 |

## 参考

---

## 十二、Bug报告模板

测试中发现bug时，按以下格式记录到文件 `execution/{端点}-BUG.md` 或 `execution/pitfalls.md`：

```markdown
# BUG: {简短描述}

| 字段 | 值 |
|------|------|
| **发现端点** | {端点ID} |
| **严重度** | 🔴阻断 / 🟡功能降级 / 🟢小问题 |
| **现象** | curl返回{错误码}: {错误消息} |
| **X-Trace-Id** | {traceId} |
| **预期行为** | {应该返回什么} |
| **实际行为** | {实际返回了什么} |
| **复现curl** | `curl -s ...` |
| **根因定位** | 文件:{file}:{line}, 原因:{为什么出错} |
| **修复方案** | 文件:{file}:{line}, 修改内容 |
| **修复验证** | curl重测: {结果} |
| **回归检查** | {受影响的链已重测通过} |

## 下游影响分析

| 受影响端点 | 影响 | 验证 |
|------|------|:--:|
| {端点A} | {只算折扣不核销,流程继续} | ✅ |
| {端点B} | {退券/关单时恢复,非此端点阻塞} | ✅ |

## Code Review检查点

- [ ] 修复是最小化的(只改出问题的部分)
- [ ] 没有引入新的硬编码/魔法值
- [ ] 相关配置已文档化(如新增JVM参数已在§九记录)
```

### 已修复bug速查

| Bug | 严重度 | 修复 | 文件 |
|------|:--:|------|------|
| S08热搜快照空 | 🔴 | 建表t_hot_search_snapshot | HotSearchService |
| IM W01 500 JWT密钥 | 🔴 | -Djwt.secret=32+字节 | ImController:47 |
| T06 403白名单 | 🟡 | Gateway yml加read-by-type/** | application.yml |
| Analytics启动崩溃 | 🔴 | -Dmanagement.admin-token | JVM参数 |
| INTERNAL_TOKEN空 | 🔴 | coupon/cart/inventory Feign 403 | 已知限制 |

## 十三、自动化验证脚本模板

将常见验证模式固化为可复用脚本，放入 `execution/verify/` 目录：

### verify-redis.py — Redis批量验证

```python
#!/usr/bin/env python3
"""验证指定Redis key的存在性/值/TTL"""
import redis, sys, json
r = redis.Redis(host='21.130.247.89', port=16379, password='Xhs@2026#Redis', decode_responses=True)

keys = json.loads(sys.argv[1])  # [{"key": "pattern", "expected": "value_or_not_none"}]
for item in keys:
    val = r.get(item["key"]) if not item.get("hash") else r.hgetall(item["key"])
    ttl = r.ttl(item["key"])
    ok = "✅" if (item.get("expected") is None and val is not None) or val == item.get("expected") else "❌"
    print(f"{ok} {item['key']}: {val}, TTL={ttl}s")
```

### verify-mysql.sh — MySQL跨库批量查询

```bash
#!/bin/bash
# 用法: ./verify-mysql.sh "{PORT}" "{DB}" "{SQL}" "{EXPECTED_ROWS}"
COUNT=$(mysql -h 21.130.247.89 -P $1 -u root -p'Xhs@2026#MySQL' $2 -e "$3" 2>/dev/null | tail -1)
[ "$COUNT" -ge "$4" ] && echo "✅ $2: $COUNT rows" || echo "❌ $2: $COUNT < $4"
```

### verify-mq.sh — MQ消费日志验证

```bash
#!/bin/bash
# 用法: ./verify-mq.sh "{service}" "{grep_keyword}" "{min_matches}"
MATCHES=$(grep -c "$2" /tmp/r_$1.log 2>/dev/null || echo 0)
[ "$MATCHES" -ge "$3" ] && echo "✅ MQ $1: $MATCHES matches for '$2'" || echo "❌ MQ $1: $MATCHES < $3"
```

## 十四、测试会话记录

每次测试会话开始时在 `execution/SESSION-{YYYYMMDD}.md` 记录：

```markdown
# 测试会话 — 2026-08-08

## 环境快照
- 服务状态: {16/16 running}
- Git HEAD: {commit hash}
- Nacos: {16 instances registered}
- Token用户: {username}({userId})
- XXL-Job: {7/7 tasks, cron=0 * * * * ?}

## 修改记录

| 时间 | 文件 | 修改内容 | 重新编译 |
|------|------|------|:--:|
| HH:MM | {file} | {what} | ✅ |
| HH:MM | Gateway yml | {what} | ✅ |

## 执行统计

| 链 | 端点数 | 通过 | 失败 | 文件数 |
|:--:|:--:|:--:|:--:|:--:|
| 1 用户 | 15 | 15 | 0 | 15 |
| ... | | | | |

## 新发现bug

| # | 端点 | 现象 | 修复 | 状态 |
|:--:|------|------|------|:--:|
| 1 | W01 | 500 JWT密钥<256位 | -Djwt.secret | ✅ |

## 会话备注
- {重要发现/教训}
```

## 参考
