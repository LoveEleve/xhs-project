# my-xhs 交接文档 — Task 1 验证 + Task 2 全链路测试

> 2026-08-10 | 46项P0修复 | 9篇engineering-docs | 12/12 test-plan | 15/15 服务 UP
> **新 AI 任务：先验证 Task 1 完整性 → 再按七链顺序执行 Task 2 测试**

---

## 零、当前状态

```
15 服务 UP  |  Token → /tmp/test_token.txt  |  12/12 test-plan 覆盖
MySQL(75用户/17SPU/20SKU/28库存/16券) | Redis(库存桶/Feed预制) | ES UP | Canal UP
测试用户: chaintest_u1/Test@123456  chaintest_u2/Test@123456
```

### 已完成的前置工作

| # | 步骤 | 状态 |
|:--:|------|:--:|
| 1 | 15 服务部署 (start-all.sh 已固化所有 JVM 参数) | ✅ |
| 2 | CaptchaService P0 修复 (序列化器不匹配 → 验证码可用) | ✅ |
| 3 | pre-test-init 9步执行 (清理+验证+初始化+预制) | ✅ |
| 4 | 12/12 模块 test-plan.md 全覆盖 (815行) | ✅ |
| 5 | pitfalls.md 36坑记录 (本会话新增14项) | ✅ |
| 6 | engineering-docs 9篇 (中间件/拓扑/缓存/安全/故障/监控/部署) | ✅ |
| 7 | fix-plan.md 8项P0全部已实施 (commit 223bfd4) | ✅ |

---

## 一、Task 1 验证清单（先执行，确认完整性）

> Task 1 产出: 46项P0代码修复 + 12模块business-docs + 9篇engineering-docs + 12 test-plan + pitfalls

### 1.1 编译验证（必须执行）

```bash
cd /data/workspace/my-xhs && mvn clean compile -Dmaven.test.skip=true -q 2>&1 | tail -3
# 预期: BUILD SUCCESS (无任何 ERROR)
# 如果有 ERROR → 逐模块: mvn clean compile -pl my-xhs-{module} -am -Dmaven.test.skip=true
```

### 1.2 代码修复验证

46项P0修复分布，关键修改点可用 git diff/grep 验证:

| 修复类别 | 数量 | 关键文件 (验证该文件能否编译即可) |
|------|:--:|------|
| 链1 user | 7 | `UserService.java`: `@Transactional` + `DuplicateKeyException` import |
| 链2 product | 4 | `SpuService.java`: `SPU_ASYNC_EXECUTOR` (非 `spuAsyncExecutor`) |
| 链3 cart | 5 | `CartSyncConsumer.java`: `CartReconcileJob.java` |
| 链4 coupon | 6 | `CouponService.java`: `String claimNo` (非 `boolean mqSuccess`) |
| 链5 order | 6 | `OrderService.java`: `@Value` import + `context` 声明移到 try 外 |
| 链6 content | 6 | `LikeUnlikeConsumer.java`: `versionKey` 声明移到 try 外（注：该文件实际在 `my-xhs-analytics` 模块，非 content） |
| 链7 notif+im | 4 | `NotificationEventConsumer.java`: `msgId` 声明移到 try 外 |
| 架构级 | 8 | `AbstractDomainEvent.java`: `setTimestamp(Instant)` added |
| CaptchaService | 1 | `CaptchaService.java`: `stringRedisTemplate.opsForValue().set()` (非 `redisOperator.set()`) |

### 1.3 配置修复验证

```bash
# Sentinel 端口 — 15服务全部分配唯一端口 (无冲突)
for svc in gateway user content analytics counter product cart inventory coupon order payment notification im home search; do
  p=$(grep 'port: 87' /data/workspace/my-xhs/my-xhs-$svc/src/main/resources/application.yml | head -1 | grep -oP '\d+')
  echo "$svc: ${p:-NONE}"  # order=8733 payment=8734 非默认8719
done

# ADMIN_TOKEN + INTERNAL_TOKEN + Sentinel 降级 — start-all.sh 顶部
grep -E "ADMIN_TOKEN|INTERNAL_TOKEN|SENTINEL_ENABLED|spring.data.redis.host|management.admin-token" /data/workspace/my-xhs/start-all.sh
```

### 1.4 文档完整性验证

```bash
# business-docs 12模块
ls /data/workspace/my-xhs/docs/test-2/business-docs/   # user/product/cart/coupon/order/content-social/counter/home/search/inventory/notification/im

# engineering-docs 9篇
ls /data/workspace/my-xhs/docs/test-2/engineering-docs/  # middleware-topology/service-dependency-map/cache-strategy/security-model/failover-scenarios/monitoring-pipeline/deployment-guide/distributed-transactions/fix-plan

# test-plan 12/12 全覆盖
find /data/workspace/my-xhs/docs/test-2/business-docs -name 'test-plan.md' | wc -l  # 必须=12

# pitfalls 踩坑记录
wc -l /data/workspace/my-xhs/docs/test-2/execution/pitfalls.md  # 187行
```

---

## 二、环境速查

### 1.1 服务端口

```
gateway :19000  user :19001  content :19002  analytics :19003  counter :19004
product :19006  cart :19008  inventory :19009  coupon :19010  order :19011
payment :19012  notification :19013  im :19014  home :19015  search :19016
```

### 1.2 远程中间件 (21.130.247.89)

```
MySQL Master:3306  Slave:3307  |  Redis:6379  Sentinel:26379
Nacos:18848  SentinelDash:8858  XXL-Job:18080  RocketMQ:9876/11911
ES:19200(elastic/Xhs@2026#Elastic)  SW-ES:19201(elastic/Xhs@2026#ElasticSW)
Canal:11111  SkyWalking:11800/12800/8080  Logstash:15044/15045  Kibana:15601
```

### 1.3 测试凭据

```bash
# Token (30min过期，重新获取见 §1.4)
cat /tmp/test_token.txt

# 测试用户
chaintest_u1 / Test@123456    # 主测试用户
chaintest_u2 / Test@123456    # 第二用户 (block/关注/通知)

# 管理员令牌
ADMIN_TOKEN=my-xhs-admin-token-2026
INTERNAL_TOKEN=my-xhs-internal-token-2026
```

### 1.4 快速获取新 Token

```bash
KEY=$(curl -s http://localhost:19000/api/user/auth/captcha | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['captchaKey'])")
sleep 0.5
CODE=$(python3 -c "import redis; r=redis.Redis(host='21.130.247.89',port=6379,password='Xhs@2026#Redis'); print(r.get('myxhs:user:captcha:$KEY').decode())")
RESP=$(curl -s -X POST http://localhost:19000/api/user/auth/login -H "Content-Type: application/json" -d "{\"username\":\"chaintest_u1\",\"password\":\"Test@123456\",\"captchaKey\":\"$KEY\",\"captchaCode\":\"$CODE\"}")
TOKEN=$(echo "$RESP" | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null)
echo "$TOKEN" > /tmp/test_token.txt
```

---

## 三、测试规范

### 2.1 五层验证模型 (L0→L4)

```
L0 前置检查 → 确认 Token有效 + 依赖服务 UP + 前置数据存在
L1 HTTP验证  → 状态码(200←→401/400) + TraceId + 业务关键字段
L2 数据验证  → Redis key存在 + MySQL行写入 + MQ消费日志
L3 九透镜    → 性能(<500ms)/安全(JWT/HMAC)/并发(锁/幂等)/一致性
L4 链级汇总  → 每条链完成后: Prometheus快照 + MQ积压 + SkyWalking链路
```

### 2.2 执行规范 (铁律)

```
一curl → 一文件 → L1→L4逐层验证
禁止批量curl        禁止跳过L2数据验证
每端点产出文件: execution/{module}/{端点ID}.md
每链完成后: 记录发现的问题到执行文件中
已知踩坑: execution/pitfalls.md (36项, 执行前必读)
```

### 2.3 产出文件格式

每个端点测试完后写入 `execution/{module}/{端点ID}.md`，按 TEMPLATE.md 格式:
```
## L0: 前置检查
## L1: HTTP 正确性
  ### 1.1 正常路径 (完整 curl + 响应 + 解读)
  ### 1.2 异常路径 (401/400/403)
  ### 1.3 业务逻辑验证
## L2: 数据正确性 (Redis/MySQL/MQ 逐层验证)
## L3: 生产级质量 (九透镜)
## 发现的问题
```

---

## 四、七链执行计划

```
链1→链2→链3→链4→链5→链6→链7
逐链执行，不可跳链
```

### 链1: user (16端点)

| 顺序 | 端点ID | 说明 | 依赖 |
|:--:|------|------|------|
| 1 | U01-captcha | 获取验证码 | — |
| 2 | U14-register | 注册 chaintest_u1 | U01 |
| 3 | U03-login | 登录 → Token | U01+U14 |
| 4 | U06-me | 当前用户信息 | Token |
| 5 | U07-update-me | 更新用户信息 | Token |
| 6 | U09-user-info | 公开用户信息 | Token |
| 7 | U16-change-password | 改密码 | Token |
| 8 | U10-add-address | 添加地址 | Token |
| 9 | U11-list-address | 地址列表 | Token+U10 |
| 10 | U12-update-address | 更新地址 | Token+U10 |
| 11 | U13-delete-address | 删除地址 | Token+U10 |
| 12 | U04-refresh | 刷新Token | Token |
| 13 | U-B1-block | 屏蔽 chaintest_u2 | Token+u2已注册 |
| 14 | U-B2-unblock | 取消屏蔽 | Token+U-B1 |
| 15 | U-B3-block-list | 屏蔽列表 | Token+U-B1 |
| 16 | U05-logout | 登出 | Token |

> 详细: `business-docs/user/test-plan.md`

### 链2: product (15端点)

> **前置**: SKU/SPU 已由 init.sql 预置，布隆由 product 启动自动加载
> 创建+查询，需 ADMIN_TOKEN 管理端点
> 详细: `business-docs/product/test-plan.md`

### 链3: cart (10端点)

> **前置**: 链2 已有 SKU，链1 已有 Token
> 详细: `business-docs/cart/test-plan.md`

### 链4: coupon (7端点)

> **前置**: 链1 Token，券模板在测试中创建
> 详细: `business-docs/coupon/test-plan.md`

### 链5: order (12端点) + payment

> **前置**: SKU + Redis库存桶 + 地址 + 券(可选)
> ⚠️ Redis库存桶可能需重新初始化 (见 §五)
> 详细: `business-docs/order/test-plan.md`

### 链6: content-social (15端点) + counter + home + search

> **前置**: 链1 Token + chaintest_u2 第二用户
> counter/home/search 均依赖链6产生的社交事件/笔记
> 详细: `business-docs/content-social/test-plan.md`

### 链7: notification (9端点) + im (7端点)

> **前置**: 链1 Token + chaintest_u2 + dev profile (home/notification)
> N09 test-send 需要 @Profile("dev")
> 详细: `business-docs/notification/test-plan.md` + `im/test-plan.md`

---

## 五、预置数据状态

### 4.1 已完成的预制

| 数据 | 状态 | 验证 |
|------|:--:|------|
| chaintest_u1 + u2 已注册 | ✅ | `mysql -h 21.130.247.89 -P 3306 -u root -p'Xhs@2026#MySQL' -e "USE my_xhs_user; SELECT id,username FROM t_user WHERE username LIKE 'chaintest%';"` |
| Token → /tmp/test_token.txt | ✅ | `head -c 30 /tmp/test_token.txt` |
| Feed inbox (10001×30, 10002×30) | ✅ | `redis-cli -h 21.130.247.89 -a 'Xhs@2026#Redis' ZCARD myxhs:feed:inbox:10001` |
| Feed outbox (10001×20) | ✅ | `redis-cli -h 21.130.247.89 -a 'Xhs@2026#Redis' ZCARD myxhs:feed:outbox:10001` |
| Redis 库存桶 (SKU 1-10) | ⚠️ 可能需重新初始化 | 见 §五.1 |
| 布隆过滤器 | ⚠️ product重启后自动加载 | Redisson内置布隆(不需RedisBloom模块) |
| TCC/Outbox/订单表已清空 | ✅ | 链5从干净状态开始 |
| 旧缓存已清空 | ✅ | Redis 62 keys deleted |

### 4.2 不需要额外注入的数据

```
✅ 用户(75): testuser/testuser2/testuser3/chaintest*  
✅ 商品(17 SPU/20 SKU): 价格79~3499, 库存30~500
✅ 库存(28条): 对应SKU的available_stock
✅ 券模板(16): 满减/立减/限量, status=1
✅ 推送模板(5): like/comment/follow/system/order
```

---

## 六、已知问题与应急操作

### 5.1 Redis 库存桶重新初始化

测试过程中 Redis 被 flush 后需重新初始化:

```bash
SKUS=$(mysql -h 21.130.247.89 -P 3306 -u root -p'Xhs@2026#MySQL' -N -e "USE my_xhs_product; SELECT id,stock FROM t_sku WHERE status=1 AND stock>0 ORDER BY id LIMIT 10;")
while read -r sid stock; do
  curl -s -X POST "http://localhost:19009/api/inventory/init" \
    -H "X-Admin-Call: my-xhs-admin-token-2026" \
    -H "Content-Type: application/json" \
    -d "{\"skuId\":$sid,\"totalStock\":$stock,\"bucketCount\":4}" \
    | python3 -c "import json,sys;d=json.load(sys.stdin);print(f'SKU $sid: {d[\"code\"]}')"
  sleep 15  # ⚠️ @RateLimit(maxRequests=5, windowSeconds=60) — 必须间隔
done
```

验证:
```bash
python3 -c "import redis; r=redis.Redis(host='21.130.247.89',port=6379,password='Xhs@2026#Redis'); [print(f'SKU {s}: OK' if r.get(f'inventory:{s}:total') else f'SKU {s}: MISSING') for s in [1,2,3,4,5]]"
```

### 5.2 验证码提取

验证码不写入日志（安全设计），从 Redis 读取:

```python
# 1. 获取 captchaKey
KEY=$(curl -s http://localhost:19000/api/user/auth/captcha | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['captchaKey'])")

# 2. 从 Redis 读取 code (CaptchaService 已修复为纯文本存储)
CODE=$(python3 -c "import redis; r=redis.Redis(host='21.130.247.89',port=6379,password='Xhs@2026#Redis'); print(r.get('myxhs:user:captcha:$KEY').decode())")
```

### 5.3 登录锁清理

多次登录失败会被锁定 (5次锁定15分钟):

```bash
python3 -c "import redis; r=redis.Redis(host='21.130.247.89',port=6379,password='Xhs@2026#Redis'); r.delete('myxhs:user:login:lock:chaintest_u1', 'myxhs:user:login:fail:chaintest_u1'); print('cleared')"
```

### 5.4 布隆过滤器

product 服务启动时自动加载全量 SPU ID 到 Redisson 布隆。RedisBloom 模块未安装（BF.CARD 不可用），但 Redisson 布隆正常工作（使用 bitmap + hash 函数实现）。如果 product 刚重启需等待加载完成。

### 5.5 服务重启

```bash
# 单个服务重启 (绝对路径, nohup脱壳)
nohup java \
  -javaagent:/data/workspace/my-xhs/skywalking-agent-9.6.0/skywalking-agent.jar \
  -Dskywalking.agent.service_name=my-xhs-{svc} \
  -Dskywalking.collector.backend_service=21.130.247.89:11800 \
  -Xms512m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:MaxMetaspaceSize=256m \
  -Dspring.data.redis.sentinel.enabled=false \
  -jar /data/workspace/my-xhs/my-xhs-{svc}/target/my-xhs-{svc}-1.0-SNAPSHOT.jar \
  > /tmp/r_{svc}.log 2>&1 &

# 特殊服务:
#   gateway:    需加 -Dspring.data.redis.host=21.130.247.89 + -Xms256m -Xmx256m
#   analytics:  需加 -Dmanagement.admin-token=my-xhs-admin-token-2026
#   order/inventory/search: -Xms1024m -Xmx1024m
#   home/notification (dev endpoint): -Dspring.profiles.active=dev
```

### 5.6 批量健康检查

```bash
for p in 19000 19001 19002 19003 19004 19006 19008 19009 19010 19011 19012 19013 19014 19015 19016; do
  curl -sf --connect-timeout 2 localhost:$p/actuator/health >/dev/null 2>&1 && echo ":$p UP" || echo ":$p DOWN"
done
```

---

## 七、关键踩坑 (必读)

| # | 场景 | 现象 | 解决 |
|:--:|------|------|------|
| 1 | Token过期 | curl返回401 | 按§1.4重新获取 |
| 2 | 库存初始化 | 40202限流 | 间隔≥15s逐条调用 |
| 3 | 登录失败 | 40108密码错误 | 用chaintest_u1非testuser(密码不匹配) |
| 4 | 服务挂了 | health DOWN | 按§5.5重启, 注意绝对路径+nohup |
| 5 | dev端点404 | home/notif测试端点不可用 | 加 `-Dspring.profiles.active=dev` |
| 6 | MySQL表名 | TRUNCATE失败 | Cart=`t_cart_item`, Order=`my_xhs_order_0.t_order_0/1/2/3` |
| 7 | 验证码 | 日志无code | 从 /api/user/auth/captcha 响应取key → Redis GET |
| 8 | 库存预扣失败 | inventory:{id}:total=0 | 执行§5.1重新初始化 |
| 9 | 管理端点403 | X-Admin-Call无效 | 值=`my-xhs-admin-token-2026` |

---

## 八、文档完整地图

> 新 AI 从本文档出发，可找到下方所有文档。全部路径相对于 `docs/test-2/`。

### 8.1 交接文档 (2份，互补)

| 文档 | 行数 | 内容 |
|------|:--:|------|
| **`HANDOFF-TASK2-TEST.md`** ← 你正在读 | 377 | **主入口**: Task 1 验证 + Task 2 测试执行 |
| `HANDOFF-NEW-AI.md` | 232 | **详细背景**: 代码审查方法论 / 5维度 / 缺陷模式 / 编码规范 / 教训固话 |

### 8.2 测试规范文档 (3份)

| 文档 | 行数 | 内容 |
|------|:--:|------|
| `execution/TEMPLATE.md` | 112 | 每端点产出格式 (L0→L4 五层验证模型) |
| `execution/pitfalls.md` | 187 | 36项已知踩坑 (部署5+代码1+脚本4+数据2+工具2+旧会话22) |
| `plans/FULL-CHAIN-RETEST-PLAN.md` | 73 | 七链全貌 + 前置依赖矩阵 + 执行协议 |

### 8.3 业务分析文档 (12模块 × 4-5篇 = 12模块完整分析)

> 每模块包含: `README.md`(端点清单) + `architecture.md`(架构/数据流) + `business-logic.md`(状态机/异常路径) + `failures.md`(已知缺陷) + 端点文件(源码/业务/前置/ASCII/L2/L3/curl)

| 模块 | 端点 | 测试入口 | 关键依赖 |
|------|:--:|------|------|
| `business-docs/user/` | 16 | `user/test-plan.md` | 链1 — 无依赖, Token产出者 |
| `business-docs/product/` | 14 | `product/test-plan.md` | 链2 — 需 Admin Token |
| `business-docs/cart/` | 10 | `cart/test-plan.md` | 链3 — 需 SKU |
| `business-docs/coupon/` | 7 | `coupon/test-plan.md` | 链4 — 需 Admin Token |
| `business-docs/order/` | 23 | `order/test-plan.md` | 链5 — 需 SKU+库存+地址 |
| `business-docs/content-social/` | 38 | `content-social/test-plan.md` | 链6 — 需 u2 + 关注关系 |
| `business-docs/counter/` | 3 | `counter/test-plan.md` | 链6 依赖 — 纯消费者 |
| `business-docs/home/` | 7 | `home/test-plan.md` | 链6 依赖 — 需 dev profile |
| `business-docs/search/` | 18 | `search/test-plan.md` | 链2+6 依赖 — 需 ES 索引 |
| `business-docs/inventory/` | 10 | `inventory/test-plan.md` | 链5 依赖 — 需 Admin Token |
| `business-docs/notification/` | 9 | `notification/test-plan.md` | 链7 — 需 dev profile |
| `business-docs/im/` | 7 | `im/test-plan.md` | 链7 — 需 chaintest_u2 |

### 8.4 工程文档 (9篇 — 跨模块全局视图)

| 文档 | 行数 | 内容 |
|------|:--:|------|
| `engineering-docs/middleware-topology.md` | 466 | 22容器拓扑 / 全部端口 / 账号密码 / 启动依赖 |
| `engineering-docs/service-dependency-map.md` | 291 | 16 Feign / 16 Topic / 21 Consumer / 15 Gateway 路由 / 7链数据流全景 |
| `engineering-docs/cache-strategy.md` | 308 | 5种 Redis 策略 (延迟双删/逻辑过期/CacheAside/TCC预扣/Buffer攒批) |
| `engineering-docs/security-model.md` | 281 | 三层安全 (JWT+HMAC+AdminToken+InternalToken+IM/SSE ticket) |
| `engineering-docs/failover-scenarios.md` | 374 | 8中间件+3服务故障 / 恢复SOP / 诊断脚本 |
| `engineering-docs/monitoring-pipeline.md` | 333 | 四层监控 (SkyWalking/ELK/Prometheus+VM/XXL-Job) |
| `engineering-docs/deployment-guide.md` | 412 | 6步部署 / 验证清单 / FAQ / §七 Sentinel降级说明 |
| `engineering-docs/distributed-transactions.md` | 111 | 四套自实现分布式事务 (TCC+事务消息+Outbox+本地消息) |
| `engineering-docs/fix-plan.md` | 274 | 8项架构级P0修复记录 (全部已实施 + 修订记录) |

### 8.5 测试数据脚本

| 文件 | 用途 |
|------|------|
| `../pre-test-init.sh` | 测试前置数据初始化 (清理+验证+库存桶+Feed预制+用户注册) |

### 8.6 新 AI 标准执行路径

```
1. 读本交接文档 (HANDOFF-TASK2-TEST.md) — 了解全貌
2. 读 HANDOFF-NEW-AI.md — 了解方法论 + 编码规范 + 踩坑教训
3. 读 execution/pitfalls.md — 避免重踩已知坑
4. 执行 §一 Task 1 验证 — 确认编译/文档完整
5. 执行 §二 环境速查 — 获取 Token
6. 按 §四 七链执行计划 — 逐链逐端点测试
7. 每端点产出写入 execution/{module}/{端点ID}.md (格式见 TEMPLATE.md)
8. 遇到问题查 §六 应急操作
```
