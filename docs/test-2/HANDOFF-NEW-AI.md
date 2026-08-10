# my-xhs 全链路测试 — 交接文档

> 2026-08-08 | 第3会话产出 | 交接新AI执行重测
> 新AI任务：**重写全链路测试文档 + 执行测试**

---

## 零、当前状态

### 已完成

| 项目 | 状态 |
|------|:--:|
| 7条业务链全部测试 | 47个执行文件 |
| 47文件含L1+L2验证 | 业务逻辑 + Redis/MySQL/MQ数据 |
| 方法论 | v3.0 中英双版 638/400行 |
| 3个@Scheduled修改 | HotSearchService/PreDeductTimeoutJob/IncrementalIndexSyncJob → 60s |
| 2个bug修复 | IM JWT 256位key / T06 Gateway白名单 read-by-type/** |
| t_hot_search_snapshot表 | 已建 |

### 未完成

| 项目 | 详情 |
|------|------|
| **47文件L3深度缺失** | 全部仅4基础透镜(业务自洽/数据一致/幂等/回滚), 缺5深层(性能/可扩展/微服务/并发/安全)。**根因**: 本会话20+次纠正暴露AI系统性跳过L3——每个新链首文件都忘记写L3+L4, 事后cat >>追加但追加在L4节之后(非L3节内) |
| **FULL-CHAIN文档是curl手册** | 非方法论要求的分层执行指南 — 缺少前置检查/源码分析/9透镜/业务逻辑 |
| **D01+D02+W01等仅有Http验证** | 部分执行文件写入了假数据(W03"MySQL COUNT=0"实际是查错库ERROR 1146) |
| **N04领券validStart陷阱** | 模板Redis缓存30min, 改validStart后必须清缓存再领券 |
| **GateWay白名单** | read-by-type/**已修复但需重编Gateway; Ant模式read/**不匹配read-by-type/{type} |

---

## 一、文档索引

新AI按此顺序阅读：

### 1. 先读
- **`README.md`** — 项目入口, 阅读顺序指引
- **`HANDOFF-20260808.md`** — 基础设施 + 覆盖表 + 启动流程
- **`methodology/TEST-METHODOLOGY.md`** (CN) — 638行, 必须全部读完

### 2. 执行时参考
- **`methodology/TEST-METHODOLOGY-EN.md`** (EN) — 英文版同规则
- **`plans/FULL-CHAIN-RETEST-PLAN.md`** — curl命令参考(需重写)
- **`execution/pitfalls.md`** — 22项+预防措施

### 3. 已归档
- **`execution/_archive/`** — 第1-2会话30个旧执行文件

---

## 二、新AI任务

### 任务0: 部署架构设计（最优先，测试前必做）

当前部署信息分散在 HANDOFF §一基础设施表和本会话实测发现中，缺乏整体视图。必须在测试前完成：

**输入**: 用户提供的远端服务实际部署详情
**产出**: 独立文档 `plans/DEPLOYMENT-ARCHITECTURE.md`

**要求**:

```markdown
# my-xhs 部署架构

## 1. 物理拓扑
| 机器 | IP | 角色 | 运行服务 |
|------|------|------|------|
| {待用户提供} | | | |

## 2. 服务端口矩阵
| 服务 | 端口 | 机器 | JVM参数 | 日志路径 | 特殊说明 |
|------|:--:|------|------|------|------|
| gateway | 19000 | localhost | 无特殊 | /tmp/r_gw.log | 唯一入口 |
| user | 19001 | localhost | 无特殊 | /tmp/r_user.log | |
| content | 19002 | localhost | 无特殊 | /tmp/r_content.log | 需重编译后启动 |
| analytics | 19003 | localhost | **-Dmanagement.admin-token=...** | /tmp/r_analytics.log | 🔴 缺参数启不来 |
| notification | 19013 | localhost | **--spring.profiles.active=dev** | /tmp/r_notification.log | 🔴 缺参数404 |
| im | 19014 | localhost | **-Djwt.secret=...32+字节** | /tmp/r_im.log | 🔴 缺参数500 |

## 3. 中间件拓扑
| 中间件 | 端口 | 机器 | 用途 | 连接方式 |
|------|:--:|------|------|------|
| MySQL 13306 | 13306 | 21.130.247.89 | user/IM | 直连 |
| MySQL 13307 | 13307 | 21.130.247.89 | content/coupon/cart/product | 直连(同端口不同库) |
| MySQL 13308 | 13308 | 21.130.247.89 | order(分库0-3)/payment | 直连 |
| MySQL 13309 | 13309 | 21.130.247.89 | inventory | 直连 |
| Redis Sentinel | 26379-26381 | 21.130.247.89 | 全部服务 | **Sentinel→master=16379** |
| RocketMQ | 9876,9877 | 21.130.247.89 | MQ消息 | nameserver |
| ES | 19200 | 21.130.247.89 | 搜索/日志 | HTTP |
| Nacos | 18848 | 21.130.247.89 | 服务发现 | HTTP |
| XXL-Job | 18080 | 21.130.247.89 | 定时任务 | HTTP |

## 4. 已知部署陷阱
| # | 服务 | 陷阱 | 修复 |
|:--:|------|------|------|
| 1 | analytics | 缺-Dmanagement.admin-token启不来 | JVM加参数 |
| 2 | notification | 缺--spring.profiles.active=dev→T09 404 | JVM加参数 |
| 3 | im | JWT secret<256位→W01 500 | -Djwt.secret=32+字节 |
| 4 | content/analytics | JAR可能未编译 | mvn package先编译 |
| 5 | 全部 | mvn -am导致服务自停 | 只用-pl不用-am |
| 6 | Redis | Sentinel主=16379非16381 | 验证命令用16379 |
| 7 | INTERNAL_TOKEN | coupon/cart/inventory默认空→Feign 403 | 已知限制 |
```

### 任务1: 重写 FULL-CHAIN-RETEST-PLAN.md

当前文档是**curl命令参考手册**，需要重写为**分层执行指南**。

**重写要求**:

每端点必须按以下结构组织：

```markdown
### {端点ID}: {描述}

#### § 前置检查
| 条件 | 验证命令 | 不满足后果 |
|------|------|------|
| I01库存已初始化 | python3 -c "r.get('inventory:{skuId}:total')>0" | D01下单拒绝 |

#### § 源码分析
- Controller: {file}:{line} → @Mapping + 参数
- Service: {file}:{line} → 核心逻辑
- 下游: Feign/MQ/Consumer 列表

#### § ASCII流转图
curl → Gateway → {service} → Redis/MySQL/MQ

#### § 业务逻辑 (3-5句)
做什么/触发什么/状态机

#### § 数据验证 (L2)
| 层 | 验证命令 | 预期值 |
|------|------|------|
| HTTP | curl -s ... | 200, X-Trace-Id |
| Redis | python3 -c "..." | key=value |
| MySQL | mysql -e "..." | COUNT>=1 |
| MQ | grep /tmp/r_{}.log | "消费成功" |

#### § 生产级检查 (L3)
| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 性能 | RateLimit? RT? | ✅ |
| 可扩展 | 分片? hash tag? | ✅ |
| 微服务 | Feign? 降级? | ✅ |
| 并发 | 锁? 幂等? | ✅ |
| 安全 | JWT? Admin? 脱敏? | ✅ |

#### § curl
```bash
TOKEN=$(cat /tmp/test_token.txt)
curl -s ...
```
```

**关键**: 当前文档只包含curl命令，必须补全前置检查+源码分析+ASCII图+业务逻辑+L3 9透镜。

### 任务2: 执行全链路测试

按7条链逐端点测试，严格遵循方法论：

**执行节奏**:
- 一curl → 一文件 → 9透镜验证 → 下一个端点
- 禁止 `curl A && curl B`
- 链7最危险(收尾心态触发批量)

**每端点写文件**: `execution/{service}/{端点ID}-{描述}.md`

**每端点必须含**:
1. § 业务逻辑 (3-5句)
2. § ASCII流转图
3. § 业务链验证
4. § 数据验证 L2
5. § 生产级检查 L3 (9透镜含性能/可扩展/微服务/并发/安全)
6. § 可观测性 L4
7. § curl
8. § 踩坑

**重点**: 所有47个现有执行文件只有L1+L2，重写时必须包含完整L3。

---

## 三、关键知识

### 前置依赖（测试前必须确认）

| 端点 | 前置 | 验证 |
|------|------|------|
| D01下单 | I01库存+B01购物车+U06地址 | Redis total>0, cart非空 |
| M01支付 | D01已下单 status=0 | MySQL确认 |
| C07 Feed | 有粉丝关注 | 先建follow关系 |
| N04领券 | 模板status=1+缓存清除 | Redis template key DEL |
| W01 WS | JWT≥256位 | -Djwt.secret=32字节 |

### 已知陷阱

| # | 陷阱 | 解决 |
|:--:|------|------|
| 1 | mvn -am → 服务自停 | 去掉-am |
| 2 | Cart Redis: `myxhs:cart:{uid}:items` | 花括号hash tag |
| 3 | IM表在my_xhs_im(13306) | 非my_xhs_user |
| 4 | A01/A06需JSON body | 非query param |
| 5 | A10 targetUserId在PATH | 非body |
| 6| INTERNAL_TOKEN空→Feign 403 | coupon/cart/inventory |
| 7 | analytics需-Dmanagement.admin-token | JVM参数 |
| 8 | notification需@Profile("dev") | --spring.profiles.active=dev |
| 9 | validStart需@FutureOrPresent | 时间格式 yyyy-MM-dd HH:mm:ss |
| 10 | Token 30min过期 | 重登录 |
| 11 | PayCallbackSimulator不工作 | payType=99 |
| 12 | testuser密码失效 | 用chaintest_c1/Test@123456 |

### 服务JVM参数

```
notification: --spring.profiles.active=dev
analytics:    -Dmanagement.admin-token=my-xhs-admin-token-2026
im:           -Djwt.secret=xhs-test-secret-key-2026-my-xhs-project-imag-service
```

### Redis经验

```
- 持久化: Sentinel主=16379(非16381)
- Cart: myxhs:cart:{userId}:items/checked/sort (3key花括号)
- Inventory: inventory:{skuId}:total (花括号)  
- Coupon: myxhs:coupon:{templateId}:stock (花括号)
```

---

## 四、执行流程

### 第一步：环境确认（5分钟）
```bash
# 1. 16服务+16 Nacos注册
ps aux | grep "my-xhs-" | grep java | grep -v grep | wc -l  # 应≈16
for s in user content analytics counter product cart coupon inventory order payment notification im home search gateway; do
  c=$(curl -s "http://21.130.247.89:18848/nacos/v1/ns/instance/list?serviceName=my-xhs-$s&namespaceId=my-xhs" | python3 -c "import json,sys;print(len(json.load(sys.stdin).get('hosts',[])))")
  [ "$c" -eq 0 ] && echo "❌ my-xhs-$s NOT REGISTERED"
done

# 2. 特殊JVM参数确认
ps aux | grep notification | grep "spring.profiles.active=dev" || echo "❌ notification 缺 @Profile(dev)"
ps aux | grep analytics | grep "management.admin-token" || echo "❌ analytics 缺 -Dmanagement.admin-token"
ps aux | grep im | grep "jwt.secret" || echo "❌ im 缺 -Djwt.secret(需≥256位)"

# 3. XXL-Job全部1min cron
curl -s -b /tmp/xxl_cookie "http://21.130.247.89:18080/xxl-job-admin/jobinfo/pageList?jobGroup=1" 2>/dev/null | python3 -c "
import json,sys
for j in json.load(sys.stdin).get('data',[]):
    ok='✅' if j['scheduleConf']=='0 * * * * ?' and j['triggerStatus']==1 else '❌'
    print(f'{ok} id={j[\"id\"]:>3} {j[\"jobDesc\"]:30s} cron={j[\"scheduleConf\"]} status={j[\"triggerStatus\"]}')
"

# 4. Token就绪
cat /tmp/test_token.txt && curl -s -o /dev/null -w "%{http_code}" http://localhost:19000/api/user/me -H "Authorization: Bearer $(cat /tmp/test_token.txt)"
# 401 → 需要重新登录(chaintest_c1/Test@123456)
```

### 第二步：部署规划 → 阅读 → 重写
```
1. 等待用户提供远端部署详情 → 完成 §二 任务0 — 产出 DEPLOYMENT-ARCHITECTURE.md
2. 读 README.md → 读 methodology/TEST-METHODOLOGY.md (638行, 必须完整读完)
3. 按§二模板重写 FULL-CHAIN-RETEST-PLAN.md
```

### 第三步：逐链测试
```
按链1→7逐端点, 严格一curl一文件:
- 每端点: 读源码→ASCII图→curl→9透镜验证→写文件→下一个
- 每链完成: 执行方法论§十 工程验证(Prometheus快照+对账+MQ)
- 链7最后一条: 特别警惕收尾心态触发批量curl
```

## 五、本会话测试数据

已创建的测试资产（可直接复用）：

| 资产 | ID | 说明 |
|------|------|------|
| 用户 | chaintest_c1 / Test@123456 | 2085982901507301378 |
| SPU | 2085989545951625217 | name=链2测试商品-已更新, categoryId=1 |
| SKU | 2085989641275572226 | price=99.00, stock=500(MySQL) |
| 券模板 | 2085998573675192321 | type=2折扣券, 已领1张 |
| 订单 | 2086000934497923073 | ORD2026080816055339313780001 |
| 笔记 | 2086012037638475778 | like/fav/comment=1 |
| 粉丝 | feed_follower / Test@123456 | 2086023092250976258 |

## 附录: 部署现状

> 来源: `config/docker-compose.yml` + `config/mysql-detail/mysql-schema.md`

### 物理拓扑

| 机器 | IP | 角色 | 运行内容 |
|------|------|------|------|
| 中间件主机 | 21.130.247.89 | 全部中间件 | MySQL/Redis/RocketMQ/ES/Nacos/Canal/可观测性 |
| 测试机 | 21.214.97.212 | 16个微服务 | my-xhs-* (端口19000-19016) |

### MySQL详细

| 端口 | 角色 | 库 | 表(示例) |
|:--:|------|------|------|
| 13306 | 主 | my_xhs_user, my_xhs_analytics, **my_xhs_im**, my_xhs_notification, nacos_config, xxl_job | t_user(73行), t_chat_message(12行), t_notification(30行) |
| 13307 | 主 | my_xhs_content, my_xhs_coupon, my_xhs_product, my_xhs_cart | t_note(46行), t_coupon_template, t_cart_item, t_hot_search_snapshot(479行) |
| 13308 | 主 | my_xhs_order_{0..3}, my_xhs_payment | t_order分库(userId%4) |
| 13309 | 主 | my_xhs_inventory | t_inventory, t_inventory_outbox |
| 13310-13313 | 从 | 对应主库复制(read-only) | 只读查询 |

### Redis详细

| 端口 | 角色 | 淘汰策略 | 用途 |
|:--:|------|------|------|
| 16379 | **Sentinel主** | allkeys-lru | 业务数据 |
| 16380 | Cache | allkeys-lru | 缓存 |
| 16381 | Business | noeviction | 业务数据(无过期) |
| 26379-26381 | Sentinel | — | 高可用(3节点仲裁) |

### 中间件端口

| 中间件 | 端口 | 说明 |
|------|:--:|------|
| RocketMQ NameServer | 9876, 9877 | 双NameServer |
| RocketMQ Broker | 11911(master), 11912(slave) | 主从 |
| RocketMQ Dashboard | 18081 | 管理界面 |
| ES 业务 | 19200 | IK分词器, myxhs-logs-* 索引 |
| ES SkyWalking | 19201 | 链路追踪存储 |
| Nacos | 18848 | 注册+配置, standalone |
| XXL-Job | 18080 | jobGroup=1, accessToken=my-xhs-xxl-job-token-2026 |
| Sentinel | 8858 | 规则运行时推送, 非持久化 |
| SkyWalking OAP | 11800(gRPC), 12800(HTTP) | 链路采集 |
| SkyWalking UI | 8080 | 链路可视化 |
| Prometheus | 19090 | 指标采集 |
| Grafana | 13000 | admin/Xhs@2026#Admin |
| Kibana | 15601 | kibana_system/Xhs@2026#KibanaSystem |

### Canal

| 实例 | 监听库 | 目标MQ Topic |
|------|------|------|
| note_instance | my_xhs_content.t_note | NOTE_INDEX_TOPIC |
| product_instance | my_xhs_product.t_spu | PRODUCT_INDEX_TOPIC |
| inventory_instance | my_xhs_inventory | — |

### 微服务端口 + 日志

| 服务 | 端口 | 日志 | 特殊JVM参数 |
|------|:--:|------|------|
| gateway | 19000 | /tmp/r_gw.log | — |
| user | 19001 | /tmp/r_user.log | — |
| content | 19002 | /tmp/r_content.log | 可能需要重编译 |
| analytics | 19003 | /tmp/r_analytics.log | 🔴 **-Dmanagement.admin-token=my-xhs-admin-token-2026** |
| counter | 19004 | /tmp/r_counter.log | — |
| product | 19006 | /tmp/r_product.log | — |
| cart | 19008 | /tmp/r_cart.log | — |
| inventory | 19009 | /tmp/r_inventory.log | — |
| coupon | 19010 | /tmp/r_coupon.log | — |
| order | 19011 | /tmp/r_order.log | — |
| payment | 19012 | /tmp/r_payment.log | — |
| notification | 19013 | /tmp/r_notification.log | 🔴 **--spring.profiles.active=dev** |
| im | 19014 | /tmp/r_im.log | 🔴 **-Djwt.secret=32+字节** |
| home | 19015 | — | — |
| search | 19016 | /tmp/r_search2.log | — |

### 关键表DDL速查

> 完整DDL: `config/mysql-detail/mysql-ddl.md`

| 表 | 库/端口 | 关键列 | 唯一索引 | COMMENT |
|------|------|------|------|------|
| t_user | my_xhs_user/13306 | id, username, password(BCrypt), status | — | 用户 |
| t_user_address | my_xhs_user/13306 | receiver_name, receiver_phone, detail_address, is_default | — | 地址 |
| t_note | my_xhs_content/13307 | title, content, status(2=发布), audit_status | — | 笔记 |
| t_comment | my_xhs_content/13307 | note_id, user_id, parent_id, content | — | 评论 |
| t_like | my_xhs_analytics/13306 | user_id, biz_type(1笔记/2评论), biz_id | **uk_user_biz** | 点赞 |
| t_favorite | my_xhs_analytics/13306 | user_id, note_id | **uk_user_note** | 收藏 |
| t_follow | my_xhs_analytics/13306 | user_id, follow_user_id | **uk_user_follow** | 关注 |
| t_chat_message | my_xhs_im/13306 | conversation_id, sender_id, receiver_id, content, seq_no | — | 聊天 |
| t_chat_user_relation | my_xhs_im/13306 | user_id, peer_id, conversation_id | — | 会话 |
| t_notification | my_xhs_notification/13306 | user_id, type, title, content, is_read | — | 通知 |
| t_spu | my_xhs_product/13307 | name, category_id, status(1=上架) | — | 商品SPU |
| t_sku | my_xhs_product/13307 | spu_id, name, price, stock | — | 商品SKU |
| t_coupon_template | my_xhs_coupon/13307 | name, type(1满减/2折扣/3无门槛), total_count, remain_count, status | — | 券模板 |
| t_user_coupon | my_xhs_coupon/13307 | user_id, coupon_id, status(0未用/1已用/2过期) | **uk_claim_no** | 用户券 |
| t_coupon_outbox | my_xhs_coupon/13307 | claim_no, status(0待发/1已发) | **uk_claim_no** | 券outbox |
| t_cart_item | my_xhs_cart/13307 | user_id, sku_id, quantity, checked | **uk_user_sku** | 购物车 |
| t_order | my_xhs_order_{0-3}/13308 | order_no, user_id, status(0待付/1已付/2已发/3完成/4取消/5退款) | — | 订单 |
| t_order_item | my_xhs_order_{0-3}/13308 | order_id, sku_id, quantity, price | — | 订单项 |
| t_order_event | my_xhs_order_{0-3}/13308 | order_id, event_type | — | EventSourcing |
| t_local_message | my_xhs_order_{0-3}/13308 | topic, status | — | 本地消息表 |
| t_payment | my_xhs_payment/13308 | order_id, pay_type, amount, status | — | 支付 |
| t_refund | my_xhs_payment/13308 | payment_id, refund_amount, status | — | 退款 |
| t_inventory | my_xhs_inventory/13309 | sku_id, available_stock, locked_stock | — | 库存 |
| t_inventory_outbox | my_xhs_inventory/13309 | — | — | 库存outbox |
| t_hot_search_snapshot | my_xhs_content/13307 | keyword, score, rank_no, snapshot_time | — | 热搜快照 |

### 关键索引说明

| 索引 | 用途 |
|------|------|
| uk_user_biz (t_like) | 防止重复点赞(同一用户+同一业务对象) |
| uk_user_note (t_favorite) | 防止重复收藏 |
| uk_user_follow (t_follow) | 防止重复关注 |
| uk_claim_no (t_user_coupon/t_coupon_outbox) | MQ消费幂等 |
| uk_user_sku (t_cart_item) | 购物车去重 |
| idx_conversation (t_chat_message) | 会话消息查询 |

### SkyWalking Agent

```
路径: /data/tmp/opencode/agent96/
使用: -javaagent:/data/workspace/my-xhs/skywalking-agent/skywalking-agent.jar
      -Dskywalking.agent.service_name=my-xhs-{service}
      -Dskywalking.collector.backend_service=21.130.247.89:11800
```

```
docs/test-2/
├── README.md
├── HANDOFF-20260808.md

├── methodology/

│   ├── TEST-METHODOLOGY.md    (CN 638行)
│   └── TEST-METHODOLOGY-EN.md (EN 400行)

├── plans/
│   ├── DEPLOYMENT-ARCHITECTURE.md ← 任务0产出 ✅
│   └── FULL-CHAIN-RETEST-PLAN.md ← 任务1总规划(7链+执行顺序+前置依赖)

├── business-docs/                  ← 任务1核心产出(逐模块)
│   ├── user/                       ← ✅ 链1完成(17文件)
│   │   ├── README.md              ← 端点清单+Redis Key+MySQL表
│   │   ├── architecture.md        ← 调用链路图/依赖关系/端口/数据流/安全机制
│   │   ├── business-logic.md      ← 状态机/异常路径/边界条件/生命周期
│   │   ├── failures.md            ← 已知bug/陷阱/兼容性/依赖故障
│   │   ├── U01-captcha.md         ← 每端点7章模板:
│   │   ├── U03-login.md           ←   §源码分析(Controller:行号+Service:行号)
│   │   ├── U04-refresh.md         ←   §业务逻辑(3-5句)/§前置检查/§ASCII流转图
│   │   ├── U05-logout.md          ←   §数据验证L2(HTTP/Redis/MySQL/MQ表)
│   │   ├── U06-me.md              ←   §生产级检查L3(9透镜)/§curl
│   │   ├── U07-update-me.md
│   │   ├── U09-user-info.md
│   │   ├── U10-add-address.md
│   │   ├── U11-list-address.md
│   │   ├── U12-update-address.md
│   │   ├── U13-delete-address.md
│   │   ├── U14-register.md
│   │   ├── U16-change-password.md
│   │   └── test-plan.md           ← 测试执行指南(执行顺序+异常场景)
│   ├── product/                   ← ⬜ 链2(待生成)
│   ├── cart/                      ← ⬜ 链3
│   ├── coupon/                    ← ⬜ 链4
│   ├── order/                     ← ⬜ 链5
│   ├── content-social/            ← ⬜ 链6
│   ├── notification/              ← ⬜ 链7
│   └── im/                        ← ⬜ 链7

├── engineering-docs/              ← 跨模块(全部 module 完成后写)
│   ├── distributed-transactions.md
│   ├── monitoring-pipeline.md
│   ├── middleware-topology.md
│   ├── service-dependency-map.md
│   ├── cache-strategy.md
│   ├── security-model.md
│   ├── failover-scenarios.md
│   └── deployment-guide.md

├── HANDOFF-NEW-AI.md ← 本文件

└── execution/                     ← 测试执行产出(任务2时写)
    ├── TEMPLATE.md                ← 测试执行模板 (L1→L4 + 异常矩阵 + 9透镜)
    ├── README.md
    ├── pitfalls.md
    ├── _archive/ (30旧文件)
    ├── user/     (15旧文件 — 仅L1+L2)
    ├── product/  (7旧文件)
    └── ...
```

## 七、模块文档标准（新AI执行时参考）

### 每模块必须产出 4 类文件

| 文件 | 必须覆盖 | 用户要求 |
|------|------|------|
| `README.md` | 端点清单+架构概览+Redis Key+MySQL表 | 模块全景图 |
| `architecture.md` | 调用链路图、依赖关系、端口、数据流、安全机制 | 架构分析 |
| `business-logic.md` | 状态机、异常路径、边界条件、生命周期约定 | 业务分析 |
| `failures.md` | 已知bug、陷阱、兼容性、依赖故障 | 故障分析 |
| **`test-plan.md`** | **执行顺序、前置准备脚本、异常场景矩阵(认证/业务/数据一致性/并发)+端点→产出映射** | **测试执行指南** |

### 每端点 7 章模板

1. § 源码分析 — Controller:行号 + Service:行号 + 下游调用
2. § 业务逻辑 — 3-5句描述做什么/触发什么/数据流
3. § 前置检查 — 表格(条件/验证命令/不满足后果)
4. § ASCII流转图
5. § 数据验证 L2 — HTTP/Redis/MySQL/MQ 各层验证命令和预期值
6. § 生产级检查 L3 — 9透镜(性能/可扩展/微服务/并发/安全)
7. § curl — 可直接执行的完整bash命令

### 执行顺序

1. 先逐模块完成 business-docs/{module}/(4分析+所有端点)
2. **每个模块完成后必须深度审查**：对照7章模板+3分析文档要求
3. 全部8个模块完成后，才写 engineering-docs/(全局视角)
4. 最后执行 execution/(任务2全链路测试)

### 端点列表验证规则

**不能直接沿用文档列表**——必须 grep Controller 源码确认端点是否存在、路径是否正确。链1实际执行发现 19端点中 7处偏差(不存在/路径不同)。
