# my-xhs 全链路测试交接文档

> 2026-08-07 FINAL | 新会话唯一入口 | 所有测试需重新执行

---

## 零、核心约束（不可违反）

### 1. 请求入口：只走 Gateway
```
所有 curl 只能打到 http://localhost:19000（Gateway 端口）
禁止直连微服务（19001-19016）
Gateway 自动完成：JWT校验 → HMAC签名 → TraceId生成 → 限流 → 路由转发
下游 Feign/MQ/存储操作全部自动流转，不需要手动触发
```

### 2. 五层数据验证（不仅是 HTTP 200）
```
每端点 = curl → HTTP(状态码+字段) → Redis(key/value/TTL) → MySQL(行增改删) → MQ(消费日志) → Trace(traceId)
             ↑                                    ↑              ↑            ↑
          Python redis 验证                    MySQL 直查询      grep 日志    grep traceId
```

### 3. 每端点先画图再测试
```
执行前必须：
  ① 读源码确认实际调用链（Mapper/RedisOperator/MQ topic）
  ② 画 ASCII 流转图：Gateway → 微服务 → MySQL/Redis/MQ/Trace
  ③ 写 3-5 句业务逻辑描述
  ④ 然后执行 curl + 五层验证
```

### 4. 一次一个端点
```
禁止 curl A && curl B              ← 批量测试
禁止 for ... curl ...              ← 脚本测试
业务链（D01→D08→D05）可以连续，但必须先声明"这条链需要连续测试"
```

### 5. 任何问题立即修复
```
curl 返回非预期结果 → 立即汇报 → 读源码定位根因 → 修复代码 → 重编译 → 重测
修复后必须从头完整重走整条业务链，不能只重测修复的那一步
```

### 6. 每个阶段必须检查三层保障机制（MQ + XXL-Job + 对账）——最容易漏！
```
❌ 只看 HTTP 200 不 grep MQ 日志  = 不知道消息是否真正被消费持久化
❌ 不检查 XXL-Job 注册+触发状态    = 不知道定时任务是否工作（券过期/超时关单/死信对账）
❌ 不跑对账端点                    = 不知道数据一致性最后防线是否有效

这三个是生产关键保障的"暗面"——SQL/Redis 验证只能证明当前请求被处理了，
不能证明异步消息、定时任务、数据对账这些"后台事实"也正确。
每个阶段结束后必须逐项检查——不验=没测完。

详细速查命令 → §零-A
```

### 7. 必须验证可观测性三层（SkyWalking Trace + Prometheus Metrics + Kibana 集中日志）——全链路的"明面"
```
❌ grep 本地日志 ≠ 全链路追踪   → SkyWalking 已部署（OAP :12800 / UI :8080 / Agent /data/tmp/opencode/agent96/）
❌ 不查 Prometheus 指标        → 15个服务 /actuator/prometheus 被采集但没人在意
❌ 不用 Kibana 集中搜索         → Logstash :15044 把日志送入 ES :19200，Kibana :15601 可跨服务搜索

"全链路"不只是业务链（curl→DB→MQ），更要验证可观测链（Trace→Metrics→Logs）——这条链
才是生产中排障的依据。不验=不知道SkyWalking是否在记录、Prometheus是否在采集、日志是否在流入。

详细速查命令 → §零-B
```

---

## 零-A、三层保障机制速查表（每阶段结束必查）

> **MQ消费者日志 + XXL-Job触发状态 + 对账端点 —— 三个缺一不可**
> **先登录 XXL-Job：`curl -c /tmp/xxl_cookie -s -X POST "http://21.130.247.89:18080/xxl-job-admin/login" -d "userName=admin&password=123456"`**

### 各阶段必查矩阵

| 阶段 | XXL-Job 必须查 | 对账必须查 | MQ 必须 grep |
|:--:|------|------|------|
| 1 商家准备 | 15(couponReconcile), 16(couponExpire) | — | coupon |
| 2 购物车 | — | — | — |
| 3 下单预扣 | 10(OrderClose), 11,12,13,14 | — | inventory(预扣减), order |
| 4 支付闭环 | 11,12,13 | — | order(支付回调) |
| 5 确认收货 | 11,12,13 | — | order |
| 6 退款闭环 | 11,12,13,14 | — | order(退款), inventory(释放) |
| 7 取消退券 | 10,14,15,16 | — | order, inventory, coupon |
| 8 内容社交 | — | F03 reconcile | analytics(L/F/C) |
| 9 内容分发 | — | — | content(FeedPush) |
| 10 关注链 | — | F03 reconcile | analytics, content(FeedPush) |
| 11 SSE通知 | — | — | notification |

### XXL-Job — 7 任务（全部 cron=`0 * * * * ?`，每 1 分钟触发）

```bash
# 查看全部任务注册状态（确认 7/7 已启用）
curl -s -b /tmp/xxl_cookie "http://21.130.247.89:18080/xxl-job-admin/jobinfo/pageList?jobGroup=1&start=0&length=20" | python3 -c "
import json,sys
for j in json.load(sys.stdin)['data']:
    print(f'  id={j[\"id\"]} {j[\"jobDesc\"]:30s} cron={j[\"scheduleConf\"]} status={j[\"triggerStatus\"]}')"

# 手动触发单个任务（即时验证，不等 1min）
curl -s -b /tmp/xxl_cookie -X POST "http://21.130.247.89:18080/xxl-job-admin/jobinfo/trigger?id={TASK_ID}"
```

| id | 任务 | 负责人 | grep 日志命令 | 关键阶段 |
|:--:|------|------|------|:--:|
| 10 | OrderCloseJob | order | `grep "订单超时\|orderClose\|执行完成" /tmp/r_order.log \| tail -5` | 3,7 |
| 11 | localMessageRetryJob | order | `grep "消息重试\|RetryJob" /tmp/r_order.log \| tail -5` | 3-6 |
| 12 | deadLetterScanJob | order | `grep "死信\|deadLetter" /tmp/r_order.log \| tail -5` | 3-6 |
| 13 | orderMappingRepairJob | order | `grep "映射修复\|orderMapping" /tmp/r_order.log \| tail -5` | 3-6 |
| 14 | inventoryReconcileJob | inventory | `grep "库存对账\|inventoryReconcile" /tmp/r_inventory.log \| tail -5` | 3,6,7 |
| 15 | couponReconcileJob | coupon | `grep "券对账\|couponReconcile" /tmp/r_coupon.log \| tail -5` | 1,7 |
| 16 | couponExpireJob | coupon | `grep "券过期\|couponExpire" /tmp/r_coupon.log \| tail -5` | 1,7 |

### 对账 — 3 项（1 HTTP 端点 + 2 XXL-Job 内部触发）

```bash
# ① 计数器对账 F03（HTTP admin 端点，从 analytics 权威源重建 counter）
curl -s -X POST http://localhost:19000/api/counter/reconcile \
  -H 'Authorization: Bearer '"$TOKEN" \
  -H 'X-Admin-Call: my-xhs-admin-token-2026'
# → 预期: data=N（修复的 counter 条数），200

# ② 券对账（XXL-Job 触发 → grep 日志）
curl -s -b /tmp/xxl_cookie -X POST "http://21.130.247.89:18080/xxl-job-admin/jobinfo/trigger?id=15"
sleep 5 && grep "券对账\|couponReconcile" /tmp/r_coupon.log | tail -3

# ③ 库存对账（XXL-Job 触发 → grep 日志）
curl -s -b /tmp/xxl_cookie -X POST "http://21.130.247.89:18080/xxl-job-admin/jobinfo/trigger?id=14"
sleep 5 && grep "库存对账\|inventoryReconcile" /tmp/r_inventory.log | tail -3
```

### MQ 消费者日志 — 各服务关键词

| 服务 | 日志文件 | 命令（执行业务端点后 grep 验证） |
|------|------|------|
| inventory | /tmp/r_inventory.log | `grep "预扣减\|扣减完成\|释放" /tmp/r_inventory.log \| tail -5` |
| order | /tmp/r_order.log | `grep "支付回调\|退款回调\|订单消息\|OrderTransaction" /tmp/r_order.log \| tail -5` |
| coupon | /tmp/r_coupon.log | `grep "持久化\|消费\|outbox" /tmp/r_coupon.log \| tail -5` |
| analytics | /proc/{PID}/fd/1 | `grep "消费\|持久化成功" /proc/{PID}/fd/1 \| tail -5` |
| content | /proc/{PID}/fd/1 | `grep "推送\|FeedPushConsumer" /proc/{PID}/fd/1 \| tail -5` |
| counter | /tmp/r_counter.log | `grep "持久化成功\|消费" /tmp/r_counter.log \| tail -5` |

> `/proc/{PID}/fd/1` — 先用 `ps aux | grep my-xhs-{analytics\|content}` 获取 PID

---

## 零-B、可观测性三层速查表（每阶段结束必查）

> **SkyWalking 链路追踪 + Prometheus 指标 + Kibana 集中日志 —— 全链路的另一半**

### 服务总览

| 服务 | 端口 | 用途 | 健康检查 |
|------|:--:|------|------|
| SkyWalking OAP | 11800(gRPC) 12800(HTTP) | Trace 收集 + 分析 | `curl -sI http://21.130.247.89:8080 \| head -1` → 200 |
| SkyWalking UI | 8080 | Trace 可视化 | `curl -sI http://21.130.247.89:8080` |
| Prometheus | 19090 | 指标采集 (每5s抓15服务) | `curl -s http://21.130.247.89:19090/-/healthy` |
| Grafana | 13000 | 4 Dashboard (admin/Xhs@2026#Admin) | `curl -sI http://21.130.247.89:13000` |
| Logstash | 15044 | 日志管道 (TCP 接收) | `pgrep -f logstash` |
| Kibana | 15601 | 日志可视化 (elastic/Xhs@2026#Elastic) | `curl -sI http://21.130.247.89:15601` |
| Sentinel Dashboard | 8858 | 流控降级面板 (sentinel/sentinel) | `curl -sI http://21.130.247.89:8858` |

### 每阶段必查矩阵

| 阶段 | SkyWalking 必查 | Prometheus 必查 | Kibana 必查 | Grafana 必看 |
|:--:|------|------|------|------|
| 0 认证 | 查 U03 登录 trace | user 服务 up=1 | 搜索 "login success" | JVM Monitor |
| 1 商家准备 | 查 N01/P01/I01 三条 trace | product/coupon/inventory up=1 | 搜索 "admin" 端点调用 | JVM Monitor |
| 2 购物车 | 查 B01 加购 trace | cart up=1 | 搜索 "cart/add" | API Monitor |
| 3 下单预扣 | 查 D01→I02 跨服务链 | order+inventory QPS>0 | 搜索 "预扣减\|createOrder" | biz-metrics(订单) |
| 4 支付闭环 | 查 D08 支付 trace | payment http_requests_count | 搜索 "支付\|payment" | biz-metrics |
| 5 确认收货 | 查 D07→D06 两段 trace | — | 搜索 "deliver\|confirm" | API Monitor |
| 6 退款闭环 | 查 M03 退款 trace | — | 搜索 "refund" | biz-metrics |
| 7 取消退券 | 查 D05 取消 trace | — | 搜索 "cancel\|退券" | biz-metrics |
| 8 内容社交 | 查 C07→C01→A01 三条 trace | analytics+content up=1 | 搜索 "publish\|comment\|like" | biz-metrics |
| 9 内容分发 | 查 S01 搜索 trace + H01 Feed trace | search+home up=1 | 搜索 "搜索\|feed\|推送" | API Monitor |
| 10 关注链 | 查 A10 关注 trace | — | 搜索 "follow\|FeedPush" | biz-metrics |

### SkyWalking — 链路追踪验证

```bash
# OAP 健康检查
curl -s http://21.130.247.89:12800/healthCheck
# → 预期: {"status":"UP"}

# 从 Gateway 日志获取 traceId 后在 SkyWalking 中搜索
TRACE_ID=$(grep "返回状态码" /tmp/r_gw.log | tail -1 | grep -oP '[0-9a-f]{32}')
echo "TraceId: $TRACE_ID"
echo "Open: http://21.130.247.89:8080/trace/$TRACE_ID"
# → 应看到完整的跨服务调用链（Gateway→微服务→MySQL/Redis/MQ 每一步耗时和状态）

# GraphQL API 查询 trace（批量验证）
curl -s -X POST http://21.130.247.89:12800/graphql \
  -H 'Content-Type: application/json' \
  -d '{"query":"{queryTraces(condition:{queryDuration:{start:\"2026-08-07 00\" end:\"2026-08-07 23\"} paging:{pageNum:1 pageSize:5}}){traces{key}}}"}'
```

### Prometheus + Grafana — 指标验证

```bash
# Prometheus 健康
curl -s http://21.130.247.89:19090/-/healthy
# → Healthy

# 查某服务是否被采集
curl -s "http://21.130.247.89:19090/api/v1/query?query=up{service=\"my-xhs-user\"}"
# → value=[1, "1"] 表示 user 服务 Prometheus 端点正常

# 查某服务 /actuator/prometheus 是否暴露指标（直接请求服务端口）
curl -s http://localhost:19001/actuator/prometheus | head -20
# → 应看到 jvm_* / http_server_requests_* / process_* 等指标

# 查订单创建 QPS
curl -s "http://21.130.247.89:19090/api/v1/query?query=rate(http_server_requests_seconds_count{service=\"my-xhs-order\",uri=\"/api/order/create\"}[5m])"

# Grafana Dashboard（浏览器打开）
# http://21.130.247.89:13000 (admin / Xhs@2026#Admin)
# 4 Dashboard: api-monitor / jvm-monitor / tomcat-monitor / biz-metrics
```

### Logstash + Kibana — 集中日志验证

```bash
# Logstash 管道（：15044 接收微服务 TCP 日志 → ES :19200 myxhs-logs-* 索引）
# 检查 index 是否存在
curl -s -u "elastic:Xhs@2026#Elastic" "http://21.130.247.89:19200/_cat/indices/myxhs-logs-*?v"
# → 应看到 green open myxhs-logs-2026.08.07 ...

# Kibana 访问
# http://21.130.247.89:15601 (elastic / Xhs@2026#Elastic)
# Discover → 选索引 myxhs-logs-* → 搜索关键词如 "预扣减\|createOrder"

# ES 直搜验证日志已流入（不走 Kibana）
TODAY=$(date +%Y.%m.%d)
curl -s -u "elastic:Xhs@2026#Elastic" \
  "http://21.130.247.89:19200/myxhs-logs-$TODAY/_search?q=message:*预扣减*&size=3" | python3 -c "
import json,sys
hits = json.load(sys.stdin)['hits']['hits']
print(f'Logstash→ES: {len(hits)} 条匹配') if hits else print('❌ 日志未流入ES!')"
```

### 可观测性验证 vs 本地日志 grep 对比

| 验证方式 | 本地 `grep` | 可观测性栈 |
|------|:--:|:--:|
| 跨服务搜索 | ❌ 逐个 ssh + grep | ✅ Kibana 一个查询 |
| 完整调用链 | ❌ 只能靠日志拼 | ✅ SkyWalking DAG 可视化 |
| 延迟分析 | ❌ 手动计算时间戳 | ✅ SkyWalking 自动计算每段耗时 |
| 错误率/QPS | ❌ grep 计数 | ✅ Prometheus + Grafana 实时 |
| JVM 健康状况 | ❌ 不知道 | ✅ Grafana JVM Monitor GC/堆/线程 |

---

## 一、基础设施

### Gateway + 微服务
```bash
GATEWAY=http://localhost:19000   # 唯一入口！
MYSQL_HOST=21.130.247.89
REDIS_HOST=21.130.247.89
```

| 服务 | 端口 | PID获取 | 日志 |
|------|:--:|------|------|
| gateway | 19000 | `ps aux\|grep my-xhs-gateway` | /tmp/r_gw.log |
| user | 19001 | `ps aux\|grep my-xhs-user` | /tmp/r_user.log |
| content | 19002 | `ps aux\|grep my-xhs-content` | /proc/PID/fd/1 |
| analytics | 19003 | `ps aux\|grep my-xhs-analytics` | /proc/PID/fd/1 |
| counter | 19004 | `ps aux\|grep my-xhs-counter` | /tmp/r_counter.log |
| product | 19006 | `ps aux\|grep my-xhs-product` | /tmp/r_product.log |
| cart | 19008 | `ps aux\|grep my-xhs-cart` | /proc/PID/fd/1 |
| inventory | 19009 | `ps aux\|grep my-xhs-inventory` | /tmp/r_inventory.log |
| coupon | 19010 | `ps aux\|grep my-xhs-coupon` | /tmp/r_coupon.log |
| order | 19011 | `ps aux\|grep my-xhs-order` | /tmp/r_order.log |
| payment | 19012 | `ps aux\|grep my-xhs-payment` | /proc/PID/fd/1 |
| notification | 19013 | `ps aux\|grep my-xhs-notification` | /proc/PID/fd/1 |
| im | 19014 | `ps aux\|grep my-xhs-im` | /proc/PID/fd/1 |
| home | 19015 | `ps aux\|grep my-xhs-home` | /data/workspace/my-xhs/logs/my-xhs-home.log |
| search | 19016 | `ps aux\|grep my-xhs-search` | /data/workspace/my-xhs/logs/my-xhs-search.log |

### MySQL — 4 主库（分库分布）⚠️ 每个端口有多个库！

| 端口 | 别名 | 库名 | 用途 |
|:--:|------|------|------|
| 13306 | `$MYSQL_USER` | `my_xhs_user` | 用户/地址/黑名单/关注/粉丝表 |
| 13307 | `$MYSQL_CONTENT` | `my_xhs_content` | 笔记/评论/点赞/收藏表 |
| | | `my_xhs_coupon` | 券模板/用户券/券outbox表 |
| | | `my_xhs_product` | SPU/SKU/商品分类表 |
| 13308 | `$MYSQL_ORDER` | `my_xhs_order_0~3` | 订单(4分库)/支付/购物车表 |
| 13309 | `$MYSQL_INVENTORY` | `my_xhs_inventory` | 库存/outbox/compensation表 |

```bash
# 通用连接模板（库名必须指定！）
MYSQL_USER="mysql -h21.130.247.89 -P13306 -uroot -p'Xhs@2026#MySQL'"        # 13306 → my_xhs_user
MYSQL_CONTENT="mysql -h21.130.247.89 -P13307 -uroot -p'Xhs@2026#MySQL'"    # 13307 → my_xhs_content
MYSQL_COUPON="mysql -h21.130.247.89 -P13307 -uroot -p'Xhs@2026#MySQL'"     # 13307 → my_xhs_coupon (⚠️ 同端口不同库！)
MYSQL_PRODUCT="mysql -h21.130.247.89 -P13307 -uroot -p'Xhs@2026#MySQL'"    # 13307 → my_xhs_product (⚠️ 同端口不同库！)
MYSQL_ORDER="mysql -h21.130.247.89 -P13308 -uroot -p'Xhs@2026#MySQL'"      # 13308 → my_xhs_order_{0..3}
MYSQL_INVENTORY="mysql -h21.130.247.89 -P13309 -uroot -p'Xhs@2026#MySQL'"  # 13309 → my_xhs_inventory

# 验证模板: MYSQL_xxx <库名> -e "SQL"
# 例: 查券模板: mysql -h21.130.247.89 -P13307 -uroot -p'Xhs@2026#MySQL' my_xhs_coupon -e "SELECT ..."
```

### Redis — 3 实例（本机无 redis-cli，用 Python）
```bash
# 16379: 主（Token/验证码/计数器/库存/社交/Like/Favorite/Feed inbox）
# 16380: 缓存专用（allkeys-lru）
# 16381: 业务数据（noeviction，购物车等）

python3 -c "import redis; r=redis.Redis(host='21.130.247.89',port=16379,password='Xhs@2026#Redis',decode_responses=True); print(r.get('KEY'))"
```

### RocketMQ
- Dashboard: http://21.130.247.89:18081
- Dashboard API 示例: `curl -s "http://21.130.247.89:18081/topic/list.query"`
- NameServer: 21.130.247.89:9876,9877

### ES
- 业务 ES: 21.130.247.89:19200 (elastic / Xhs@2026#Elastic)
- 索引: note_index (ik_max_word), product_index
- 验证: `curl -s -u "elastic:Xhs@2026#Elastic" "http://21.130.247.89:19200/note_index/_search?q=title:xxx&size=3"`

### XXL-Job
- Dashboard: http://21.130.247.89:18080/xxl-job-admin (admin/123456)
- 7 cron 任务已配置（均为每 1 分钟执行，方便测试）
- 登录 API: `curl -c /tmp/xxl_cookie -X POST "http://21.130.247.89:18080/xxl-job-admin/login" -d "userName=admin&password=123456"`

### Nacos
- 21.130.247.89:18848 (nacos/nacos)

### 可观测性六件套

| 服务 | 端口 | 用途 | 健康检查 |
|------|:--:|------|------|
| SkyWalking OAP | 11800(gRPC) 12800(HTTP) | Trace 收集+分析 | `curl -s -o /dev/null -w "%{http_code}" http://21.130.247.89:8080` → 应=200 |
| SkyWalking UI | 8080 | Trace 可视化 | http://21.130.247.89:8080 |
| Prometheus | 19090 | 指标采集(5s×15服务) | `curl -s http://21.130.247.89:19090/-/healthy` |
| Grafana | 13000 | 指标可视化(4 Dashboard) | http://21.130.247.89:13000 (admin/Xhs@2026#Admin) |
| Logstash | 15044 | 日志管道(TCP→ES) | `pgrep -f logstash` |
| Kibana | 15601 | 日志可视化 | http://21.130.247.89:15601 (elastic/Xhs@2026#Elastic) |

> SkyWalking Agent 路径: `/data/tmp/opencode/agent96/`（微服务启动时 attach）
> Prometheus 抓取所有 15 服务 `/actuator/prometheus` 端点（配置验证: `curl http://21.130.247.89:19001/actuator/prometheus | head -5`）
> Grafana 4 Dashboard: api-monitor / jvm-monitor / tomcat-monitor / biz-metrics
> Logstash 输入: tcp:15044 json_lines，输出: ES :19200 索引 `myxhs-logs-YYYY.MM.dd`

### 其他基础设施

| 服务 | 端口 | 说明 |
|------|:--:|------|
| Sentinel Dashboard | 8858 | 流控降级 (sentinel/sentinel) |
| MySQL 从库 ×4 | 13310-13313 | GTID 主从复制（读写分离） |
| Redis Sentinel ×3 | 26379-26381 | 高可用 |
| RocketMQ Slave | 11912 | Broker 备 |
| VictoriaMetrics | 8428 | 时序DB（当前闲置） |

---

## 二、155 端点覆盖状态

> 前半段严格逐端点验证✅。后半段开始批量 curl+跳数据验证❌——仅重测后半段。

| 服务 | 总 | 已验证 ✅ | 需重测 ❌ | 说明 |
|------|:--:|:--:|:--:|------|
| 01-user | 19 | Auth(5):U01-U03B+refresh+logout + User(7):U04-U10 + Address(7):全部 (19) | 0 | 全部端点已验证 ✅ |
| 02-content | 16 | C01,C02,C03,C05,C06,C07,C16 (7) | 9 | 笔记+评论+分享；C04/C08-C15待补 |
| 03-analytics | 18 | A01-A10 (10) | 8 | 点赞+收藏全链路；A11-A18待补 |
| 04-counter | 3 | F03 (1) | 2 | 对账已验证 |
| 05-product | 10 | P01,P02,P03,P04,P05,P06,P07,P09,P10 (9) | 1 | SPU CRUD+SKU+分类全测；仅P08(内部batch)待补 |
| 06-cart | 11 | B01,B02,B03,B04,B05,B06,B07,B08,B09 (9) | 2 | 全量CRUD+全选+合并；仅内部reconcile待补 |
| 07-coupon | 9 | N01,N02,N04,N05 (4) | 5 | 模板CRUD+领券+查券；上下线已验 |
| 08-inventory | 10 | I01,I02(内部),I06 (3) | 7 | 库存初始化+预扣+查询已验证 |
| 09-order | 14 | D01,D02,D03,D04,D05,D06,D07,D08,D09 (9) | 5 | 完整生命周期+D08 remote打通 Feign→payment |
| 10-payment | 5 | D08内部,M01,M03 (3) | 2 | 支付+退款全链路打通(需 InternalCallFeignConfig) |
| 11-search | 18 | S01,S03,S04,S06,S07 (5) | 13 | 搜索+建议+历史+重建 |
| 12-home | 7 | H01,H02 (2) | 5 | Feed+笔记详情 |
| 13-notification | 9 | T01-T05,T07,T09 (7) | 2 | SSE全链路+列表+已读 |
| 14-im | 6 | W01-W03,W05,W06,online-count (6) | 0 | IM 全测 ✅ |
| **总计** | **155** | **95** | **60** | |

> ✅ = 端点完整验证过（HTTP+Redis+MySQL+MQ+Trace 七层）
> ❌ = 未测/批量测/跳了数据验证——需从头重测
> 2026-08-07 更新: 新增 14 个已验证端点(N02/P02/P04/P05/P07/P09/P10/B03/B05/B07/D03/D04/M01/M03)

---

## 三、已修复问题清单（14 项）

| # | 问题 | 修复位置 | 严重度 |
|:--:|------|------|:--:|
| 1 | 10 服务 ADMIN_TOKEN 空默认 | my-xhs-*/application.yml → `${ADMIN_TOKEN:my-xhs-admin-token-2026}` | 🔴 |
| 2 | D01 下单无库存前置校验 | InventoryFeignClient.queryStock() + OrderService.createOrder() | 🔴 |
| 3 | Feign INTERNAL_TOKEN 空→fail-closed | order/product yml → `${INTERNAL_TOKEN:my-xhs-internal-token-2026}` | 🔴 |
| 4 | EventSourcing cancelled_at/paid_at/delivered_at/completed_at 四时间戳 NULL | OrderMapper.setXxxAt() + OrderService 四方法各补一行 | 🟡 |
| 5 | search Feign Decoder 重复 bean→启动失败 | FeignUnifiedConfig.feignSpringDecoder() @Primary | 🟡 |
| 6 | search status 过滤=1 应为=2 | NoteSearchService.java:139 filter v=2 | 🟡 |
| 7 | order 硬编码 mock SKU（价格 99.00 应为 29.90） | ProductFeignClient + OrderService.calculateTotalAmount() | 🔴 |
| 8 | t_coupon_outbox 表缺失 | 对方在 13307 建表 | 🟡 |
| 9 | t_inventory_outbox/compensation/tcc_freeze 表缺失 | 对方在 13309 建表+AUTO_INCREMENT | 🟡 |
| 10 | I06 lockedStock 返回 null | InventoryService.getStock() Redis路径补查 MySQL | 🟡 |
| 11 | Canal 未运行→ES 不同步 | 对方启动 Canal 容器 | 🟡 |
| 12 | HMAC 白名单多模块缺失 | Gateway hmac-white-list 多轮扩展 | 🟡 |
| 13 | delayLevel 30min→1min（测试用，不还原） | OrderService.sendCloseDelayMessage delayLevel=5 | 🟡 |
| 14 | XXL-Job 7 Handler 仅 1 cron | Dashboard API 全注册为 1min | 🟡 |
| 15 | D08→payment Feign 永远 403（M03 退款被阻） | 5 处联动修复（2026-08-07 第2会话）：PaymentController /pay isAdminCall→isInternalCall、PaymentFeignClient 补 configuration=InternalCallFeignConfig、InternalCallFeignConfig INTERNAL_TOKEN 空默认→my-xhs-internal-token-2026、OrderController D08 remote补 payRequest.setAmount()、Order yml pay.type=mock→remote | 🔴 |

> **#15 详细说明**: PaymentFeignClient 是 order 唯一缺 InternalCallFeignConfig 的 Feign 客户端——Inventory/Coupon/Product 三个都有。导致 D08 remote 模式的 Feign 调用不带 X-Internal-Call 头，payment 服务返回 403。修复后 D01→D08(remote)→payment 创建记录→M03 退款全链路打通。

---

## 四、测试数据资产

### 测试用户
| 用户 | ID | 密码 | 说明 |
|------|------|------|------|
| testuser | 10001 | Test@123456 | 主测试用户 |
| testuser2 | 10002 | Test@123456 | 关注 testuser |
| zerofan1 | 2085591227647995906 | Test@123456 | 0粉0关注 |

### 业务数据
| 数据 | ID | 值 |
|------|------|------|
| 券模板 | 2085346463623200770 | 满10元, 总量100, perUserLimit=1 |
| 用户券 | 2085350660389257217 | testuser 已领，未使用 |
| SPU | 2085352027803664385 | P01测试商品, categoryId=1 |
| SKU | 2085530413171785729 | P06测试SKU, price=29.90 |
| 库存 | available=~993 | 已初始化 1000（重启后需重初始化） |
| 地址 | addressId=1 | testuser 默认地址（张三） |

### JWT 获取一条龙
```bash
# 保存到 $TOKEN 变量
curl -s http://localhost:19000/api/user/auth/captcha > /tmp/cap.json
KEY=$(python3 -c "import json; print(json.load(open('/tmp/cap.json'))['data']['captchaKey'])")
CODE=$(grep "$KEY" /tmp/r_user.log | tail -1 | grep -oP 'code=\K\w+')
TOKEN=$(curl -s http://localhost:19000/api/user/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"testuser\",\"password\":\"Test@123456\",\"captchaKey\":\"$KEY\",\"captchaCode\":\"$CODE\"}" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['data']['accessToken'])")
echo $TOKEN
```
> Token 有效期 30 分钟，过期重新执行
> admin 端点加 `-H 'X-Admin-Call: my-xhs-admin-token-2026'`

---

## 五、每端点测试流程（标准步骤）

```
Step 1: 读源码 → 确认 Controller/Service/Mapper/RedisOperator/MQ topic 调用链
Step 2: 画 ASCII 流转图
        [curl] → Gateway:19000 → service:port → MySQL/Redis/MQ/Trace
Step 3: 写 3-5 句业务逻辑描述
Step 4: 获取 JWT Token（如需要）
Step 5: 执行 curl（走 Gateway:19000）
Step 6: 七层验证（5 业务层 + 3 可观测层）：
        HTTP  → 状态码 + JSON 字段值
        MySQL → 行增/改/删 + 字段值正确
        Redis → key 存在/value/TTL
        MQ    → 消费者日志 ← ⚠️ 最容易漏！
                ├ 执行 curl 后立即 grep（参考 §零-A MQ 消费者表）
                ├ 不能只看"生产者发送成功"——必须确认消费端日志已打印
                └ 异步延迟>5s 的业务（如 D01→I02 预扣），先 sleep 5 再 grep
        ──────── 可观测层（每阶段结束时批量验证 → §零-B）────────
        SkyWalking → traceId 在 Gateway 日志 grep → 打开 SkyWalking UI 验证完整调用链
                     http://21.130.247.89:8080/trace/{traceId}
        Prometheus → curl http://21.130.247.89:19001/actuator/prometheus 确认指标已曝光
        Kibana     → ES 搜索 myxhs-logs-* 索引确认日志已流入 Logstash→ES 管道
        
        每层验证后标注 ✅/❌，任一层 ❌ = 停止 → 读源码 → 修复 → 重测
Step 7: 写入测试文档 §执行记录（ASCII图+业务逻辑+curl+五层结果表）
Step 8: 继续下一个端点
```

---

## 六、完整测试计划（155 端点 + 16 边界）

> **全部重新测试**。从阶段 0 开始，逐阶段执行。
>
> ✅ = 阶段已完成，❌ = 需重测（后半段批量 curl 跳过数据验证的端点）
>
> **每阶段结束必须做三次检查（§零-A + §零-B）：**
> 1. §零-A 三层保障：XXL-Job 触发状态 + 对账端点 + MQ 消费者日志
> 2. §零-B 可观测层：SkyWalking 完整调用链 + Prometheus 指标 + Kibana 集中日志
> 3. 全部 ✅ 后才进入下一阶段

### ✅ 阶段 0：认证闭环（4端点）— 已完成
```
U01(GET /api/user/auth/captcha) → Redis SET myxhs:user:captcha:{uuid}=code(5min)
U02(POST /api/user/auth/register) → 注册新用户 0粉0关注，MySQL INSERT t_user
U03(POST /api/user/auth/login) → 获取 JWT, Redis SET token:access/refresh/hmac
U03B(登录新注册的用户) → 验证注册后可直接登录
```

### 阶段 1：商家 admin 准备（6端点）
```
N01(POST /api/coupon/template, admin) → MySQL INSERT t_coupon_template, Redis stock
N02(PUT /api/coupon/template/{id}/status, admin) → 券上下线
P01(POST /api/product/spu, admin) → MySQL INSERT t_spu
P06(POST /api/product/sku, admin) → MySQL INSERT t_sku
I01(POST /api/inventory/init, admin) → Redis SET total+buckets, MySQL INSERT t_inventory
```

### 阶段 2：购物车（9端点）
```
B01(POST /api/cart/add) → Redis HSET myxhs:cart:{userId}:items
B02(PUT /api/cart/quantity) → Redis HGET quantity 变化
B04(PUT /api/cart/check?checked=true) → Redis SADD checked
B05(PUT /api/cart/check-all?checked=true) → 验证全选
B06(GET /api/cart/list) → HTTP 200, items 列表
B09(GET /api/cart/count) → HTTP 200, data.count
B03(DELETE /api/cart/{skuId}) → Redis HDEL
B07(POST /api/cart/merge) → 合并购物车
B08(DELETE /api/cart/clear) → Redis HGETALL 空
```

### 阶段 3：下单+库存预扣（4端点）
```
D01(POST /api/order/create) → total=29.90×qty, payAmount 正确
  └ 等3s→ I02(MQ异步) → Redis total 减少, MySQL locked_stock 增加
  └ 验证 inventory log "预扣减完成"
D09(GET /api/order/pay/status/{orderId}) → status=0(待付款)
```

### 阶段 4：支付闭环（5端点）
```
D08(POST /api/order/pay/create, payType=99 Mock) → status=1(已支付), paymentNo=MOCK_PAY_
  └ MySQL: t_order_0.paid_at 非 NULL
D09(GET /api/order/pay/status/{orderId}) → status=1
D10(pay-success callback, 内部触发→MQ消费者日志验证)
D11(pay-fail callback, 内部触发→MQ消费者日志验证)
I03(confirm deduct, 内部触发→Redis prededuct删除, MySQL locked→available)
```

### 阶段 5：确认收货+订单查询（5端点）
```
D07(POST /api/order/deliver, admin, @RequestBody) → 发货, status=1→2
  └ MySQL: t_order_0.delivered_at 非 NULL
D06(POST /api/order/confirm?orderId=xxx) → 收货, status=2→3
  └ MySQL: t_order_0.completed_at 非 NULL
D02(GET /api/order/{orderId}) → D03(GET list 分页) → D04(GET by-order-no)
```

### 阶段 6：退款闭环（3端点）
```
M03(POST /api/payment/refund) → D12(refund-success, 内部→MQ验证)
  → I04(release stock, 内部→Redis total恢复)
```

### 阶段 7：异常取消+退券（3端点）
```
D01(新建, 不支付) → D05(POST /api/order/cancel?orderId=xxx)
  └ MySQL: status=4, cancelled_at 非 NULL + Redis prededuct回滚, total恢复
N08(用券, 内部→MQ验证) → N09(退券, 内部→MQ验证)
```

### 阶段 8：内容社交+0粉丝（12端点）
```
注册0粉用户 → C07(POST /api/note/publish) → FeedPushConsumer: pushed=0/0 无报错
A01(POST /api/social/like) → Redis SADD myxhs:like:note:{noteId}
A02(DELETE like) → A03(GET status) → A04(GET batch-status) → A05(GET count)
C01(POST /api/comment) → MySQL INSERT t_comment
C03(GET list/{noteId}) → C05(GET count) → C06(GET page)
C02(DELETE comment) → MySQL deleted=1
A06(POST /api/social/favorite, body: {noteId}) → A07(DELETE) → A08(GET status) → A09(GET list)
```

### 阶段 9：内容分发（8端点）
```
C16(POST /api/note/{id}/share) → S07(POST /api/search/index/rebuild, admin)
S01(GET /api/search/note?keyword=测试) → ES _search 验证
H01(GET /api/home/feed?size=5) → 0粉→notes=0, 有粉→notes≥1
H02(GET /api/home/note/{noteId}) → S03(GET suggest) → S04(GET history) → S06(DELETE history)
```

### 阶段 10：关注链+Feed 推模（10端点）
```
A10(POST follow/B) → A12(GET following/A) → A13(GET follower/B) → A14(GET common/B)
A15(GET relation/B) → A16(GET follower/count/B) → A17(GET following/count/A)
C07(B发笔记) → H01(A的Feed 验证推模式: 看到B笔记)
A11(DELETE follow/B) → H01(A的Feed 不再含B笔记)
```

### 阶段 11：通知 SSE（7端点）
```
T01(POST sse/ticket) → T02(GET sse?ticket=xxx, event:connected+heartbeat)
T09(POST test/send) → SSE stream 收到 event:notification
T03(GET list) → T04(GET unread-count) → T05(POST read/{id}) → T06(POST read-by-type) → T07(POST read-all)
```

### 阶段 12-15 + 边界 + 管理端点
```
阶段12 IM: W01,W02,W03,W05,W06
阶段13 用户管理: U04,U05,U06-U12(地址CRUD),U13,U14,U15,U16,U17,U18,U19
阶段14 商品查询+热搜: P03,P04,P07,P08,P09,P10,S02,S03,S08,S09,S10,S11
阶段15 推荐+计数: S15,S16,S17,S18,F01,F02,F03
管理补测: N02,P05,I05,S12,S13,S14
内部端点(MQ验证): I03,I04,I08-I10,D10-D13,N07-N09,M02,M04,B10,B11,I07,A18,H06,H07
```

### 16 边界场景
```
1. 0粉丝发笔记    2. 幂等下单      3. 库存不足      4. 券已下线/过期
5. 修改密码       6. 登出Token黑名单 7. 拉黑自己    8. 关注自己
9. 重复领券       10. 取消已付订单  11. 非本人操作  12. Token刷新
13. 注册重名      14. 登录失败锁定  15. 并发下单    16. 分页边界
```

---

## 七、常用验证命令

### Redis 库存
```bash
python3 -c "import redis; r=redis.Redis(host='21.130.247.89',port=16379,password='Xhs@2026#Redis',decode_responses=True); print(r.get('inventory:{2085530413171785729}:total'))"
```

### MySQL 库存
```bash
mysql -h21.130.247.89 -P13309 -uroot -p'Xhs@2026#MySQL' my_xhs_inventory -e "SELECT available_stock,locked_stock FROM t_inventory WHERE sku_id=2085530413171785729"
```

### MQ 预扣验证
```bash
grep "预扣减完成\|预扣减成功" /tmp/r_inventory.log | tail -3
```

### 订单状态（4 分库）
```bash
for db in my_xhs_order_{0,1,2,3}; do
  mysql -h21.130.247.89 -P13308 -uroot -p'Xhs@2026#MySQL' "$db" \
    -e "SELECT id,status,paid_at,cancelled_at FROM t_order_0 WHERE id=xxx" 2>/dev/null
done
```

### 点赞/收藏计数器
```bash
python3 -c "import redis; r=redis.Redis(host='21.130.247.89',port=16379,password='Xhs@2026#Redis',decode_responses=True); print(r.get('myxhs:counter:1:{noteId}:1'))"
# 对账修复
curl -s -X POST http://localhost:19000/api/counter/reconcile -H 'Authorization: Bearer '"$TOKEN" -H 'X-Admin-Call: my-xhs-admin-token-2026'
```

---

## 八、常见坑点速查

| 问题 | 原因 | 解决 |
|------|------|------|
| 服务启动失败，端口占用 | 僵尸进程 | `pkill -9 -f "my-xhs-{服务}"` 等 3s 再启动 |
| Redis 库存 None | 重启导致数据丢失 | `curl I01 init` 重新初始化 |
| Token 过期（30min） | JWT 短有效期 | 重新执行 JWT 获取一条龙 |
| HMAC 403 | 端点不在 Gateway 白名单 | 加 `/api/{path}/**` 到 hmac-white-list，重编重启 Gateway |
| LocalDateTime 400 | Jackson 默认空格格式 | 用 `yyyy-MM-dd HH:mm:ss` 不用 ISO T |
| 编译后 JAR 没更新 | `mvn compile` 不打包 | `mvn package -DskipTests -Dmaven.test.skip=true` |
| admin 端点 403 | 该服务 JAR 是旧的（没 admin token） | `mvn package` + 重启该服务 |
| Feign 503/coupon 不可用 | INTERNAL_TOKEN 空→fail-closed | 确认 yml `${INTERNAL_TOKEN:my-xhs-internal-token-2026}` |
| User 日志找不到 captcha | user 重启→日志文件变了 | `grep "生成成功" /tmp/r_user.log | tail -1` |
| 查询空结果 total=0 | Canal 未运行 | 验证 ES 有数据后检查搜索 filter 状态码 |
| Gateway 没响应 | 进程被 pkill 杀了 | `java -jar ...` 重启动，等 12s |

---

## 九、新会话极简启动流程

> 每端点详细执行记录（ASCII图+curl+验证表）见 `execution/` 目录。
> 踩坑记录见 `execution/pitfalls.md`。

```bash
# 1. 确认服务都在
ps aux | grep "my-xhs-" | grep -v grep | awk '{print $NF}' | sort | wc -l  # 应=16

# 2. 登录 XXL-Job + 确认 7 个任务全部已注册（生产保障暗面——别漏！）
curl -c /tmp/xxl_cookie -s -X POST "http://21.130.247.89:18080/xxl-job-admin/login" -d "userName=admin&password=123456"
curl -s -b /tmp/xxl_cookie "http://21.130.247.89:18080/xxl-job-admin/jobinfo/pageList?jobGroup=1&start=0&length=20" | python3 -c "
import json,sys
tasks = json.load(sys.stdin)['data']
print(f'XXL-Job: {len(tasks)}/7 个任务已注册')
for j in tasks:
    ok = '✅' if j['triggerStatus']==1 else '❌停用'
    print(f'  id={j[\"id\"]} {j[\"jobDesc\"]:30s} cron={j[\"scheduleConf\"]} {ok}')"
# 应看到 7 个任务，全部 cron=0 * * * * ?，全部 ✅
# 如有 ❌ → Dashboard 手动启用 → 不跳过去继续

# 3. 可观测性六件套健康检查（全链路的另一半——别漏！）
echo "=== 可观测性健康检查 ==="
# SkyWalking OAP(通过 UI 验证)
curl -sI http://21.130.247.89:8080 | grep -q 200 && echo "✅ SkyWalking UI" || echo "❌ SkyWalking UI DOWN"
# Prometheus
curl -s http://21.130.247.89:19090/-/healthy | grep -q Healthy && echo "✅ Prometheus" || echo "❌ Prometheus DOWN"
# 验证 actuator 指标曝光（user服务为例）
curl -s http://localhost:19001/actuator/prometheus | grep -q http_server_requests && echo "✅ Actuator metrics exposed" || echo "❌ No Prometheus metrics"
# Logstash → ES 日志管道
curl -s -u "elastic:Xhs@2026#Elastic" "http://21.130.247.89:19200/_cat/indices/myxhs-logs-*?v" | grep -q myxhs-logs && echo "✅ Logstash→ES pipeline" || echo "❌ Log pipeline broken"
echo "=== 可观测性检查完成 ==="
# 凡有 ❌ → 检查对应容器是否运行 → 不跳过去继续

# 4. 获取 JWT
curl -s http://localhost:19000/api/user/auth/captcha > /tmp/cap.json
KEY=$(python3 -c "import json; print(json.load(open('/tmp/cap.json'))['data']['captchaKey'])")
CODE=$(grep "$KEY" /tmp/r_user.log | tail -1 | grep -oP 'code=\K\w+')
TOKEN=$(curl -s http://localhost:19000/api/user/auth/login \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"testuser\",\"password\":\"Test@123456\",\"captchaKey\":\"$KEY\",\"captchaCode\":\"$CODE\"}" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['data']['accessToken'])")

# 5. 验证 Redis 库存
python3 -c "import redis; r=redis.Redis(host='21.130.247.89',port=16379,password='Xhs@2026#Redis',decode_responses=True); v=r.get('inventory:{2085530413171785729}:total'); print('stock='+str(v))"
# 如果 None → curl I01 init 重新初始化

# 6. 从阶段 0 U01 开始
curl -s http://localhost:19000/api/user/auth/captcha | python3 -c "import json,sys; print(json.load(sys.stdin)['data']['captchaKey'])"
# → 读源码 → 画 ASCII 流转图 → 写业务逻辑 → 七层验证(业务5层+可观测3层) → 写入文档 → 下一个
```
