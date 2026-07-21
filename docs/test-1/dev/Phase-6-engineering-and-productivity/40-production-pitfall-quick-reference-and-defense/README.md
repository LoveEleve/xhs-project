# 生产踩坑速查与防御

> 所属维度：生产经验 | 开发阶段：Phase-6 | 覆盖：12个技术组件

---

## 🎯 一、速查总表

| 组件 | 最致命的坑 | 影响 | 防御 |
|------|-----------|------|------|
| **Redis** | 大Key阻塞 | Redis卡死3秒 | XXL-Job扫描大Key |
| **RocketMQ** | 消息重复消费 | 重复扣库存 | @Idempotent幂等Key |
| **MySQL** | 主从延迟读不到刚写的数据 | 下单后查不到订单 | HintManager强制走主库 |
| **MySQL** | 索引失效(函数/隐式转换/左模糊) | 慢查询拖垮DB | explain + 慢查询日志 |
| **Feign** | GET传POJO参数丢失 | 接口调用失败 | @SpringQueryAnnotation |
| **Spring** | @Transactional失效(5种场景) | 数据不一致 | 自调用/catch吞异常/非public/异常类型/引擎 |
| **ShardingSphere** | 不带分片键=全库扫描 | 查询超时 | 必须带分片键 |
| **ES** | 深分页OOM | ES崩溃 | search_after游标分页 |
| **K8s** | OOM Killed | Pod被杀 | JVM堆=容器内存60% |
| **分布式锁** | 锁没释放 | 死锁 | finally释放 |
| **JWT** | Token无法主动失效 | 账号被盗 | Redis黑名单 |
| **Canal** | binlog位点丢失 | 数据不同步 | 定期全量重建 |
| **XXL-Job** | 任务重复执行 | 重复关单 | 幂等+串行策略 |

---

## 📋 二、Redis 坑（5个）

### 坑1：大Key导致Redis阻塞

```bash
# ❌ 一个Hash存100万字段，HGETALL要3秒
HGETALL social:follower:bigV_userId

# ✅ 拆分 + SCAN遍历
# 大V粉丝分桶：social:follower:bucket:userId:0 ~ :9
# 遍历用HSCAN/ZSCAN，不用HGETALL全量返回
```

| 大Key类型 | 判断标准 | 治理方案 |
|-----------|----------|----------|
| String | >10KB | 压缩/拆分 |
| Hash | >5000字段 | 拆分为多个小Hash |
| Set/ZSet | >5000元素 | 分桶拆分 |

### 坑2：缓存击穿——热点Key过期瞬间DB被打爆

- **解决**：互斥锁(分布式锁只让1个请求查DB) 或 逻辑过期(不设TTL，异步更新)

### 坑3：Redis主从切换时数据丢失

- **影响**：购物车/计数数据可能丢失最近1-2秒写入
- **解决**：购物车异步落MySQL兜底；计数Buffer-Trigger对账修复；关键数据Lua原子操作+XXL-Job对账

### 坑4：连接池耗尽

```yaml
# ✅ 生产环境配置
spring.redis.lettuce.pool:
  max-active: 100
  max-idle: 50
  min-idle: 10
  max-wait: 3000ms
```

### 坑5：Keys命令导致Redis卡死

- **绝对不能用** `KEYS *`，用 `SCAN` 替代

---

## 📋 三、MySQL 坑（4个）

### 坑1：索引失效

```sql
-- ❌ 函数操作索引列
WHERE DATE(create_time) = '2025-05-09'
-- ✅ 范围查询
WHERE create_time >= '2025-05-09' AND create_time < '2025-05-10'

-- ❌ 隐式类型转换
WHERE phone = 13800138000  -- phone是VARCHAR
-- ✅ 类型匹配
WHERE phone = '13800138000'
```

### 坑2：分库分表后跨库查询

- **解决**：冗余字段 / 应用层聚合 / ES宽表

### 坑3：主从延迟导致读不到刚写的数据

- **场景**：下单后立即查询，读从库，主从延迟500ms，查不到刚下的单
- **解决**：关键查询走主库(下单后5秒内走主库) + ShardingSphere HintManager + 前端延迟3秒

### 坑4：连接池耗尽

```yaml
# ✅ 生产环境配置
spring.datasource.hikari:
  maximum-pool-size: 50
  minimum-idle: 10
  connection-timeout: 3000
```

---

## 📋 四、RocketMQ 坑（4个）

### 坑1：消息重复消费 → 重复扣库存
- **解决**：@Idempotent + Redis SETNX(bizId, "1", 24h)

### 坑2：消费堆积
- **监控**：堆积>5000告警
- **解决**：临时增加消费者 + 消费端优化

### 坑3：延时消息不准时
- **解决**：延时消息(主) + XXL-Job定时扫描(兜底)

### 坑4：事务消息回查频率过高
- **解决**：本地事务尽量快，复杂操作放消费端

---

## 📋 五、Spring Boot / Feign 坑（3+6个）

### @Transactional 5种失效场景
1. 自调用(同类方法调用AOP不生效)
2. 异常被catch未抛出
3. 异常类型不匹配(默认只回滚RuntimeException)
4. 方法不是public
5. 数据库引擎不支持事务(MyISAM)

### Feign 6大坑
1. GET传POJO参数丢失 → @SpringQueryAnnotation
2. 请求头丢失(UserId/TraceId) → FeignRequestInterceptor
3. @RequestParam不指定name → 编译后参数名丢失
4. 超时配置不当(默认60s) → connectTimeout:5s, readTimeout:10s
5. 重试导致重复扣减 → 全局NEVER_RETRY
6. 异步线程ThreadLocal丢失 → 手动传递RequestContextHolder

---

## 📋 六、其他组件坑

### ShardingSphere
- 不带分片键=全库扫描
- 分布式主键冲突 → 雪花ID
- 深分页性能差 → 游标分页

### ES
- 深分页OOM → search_after
- Mapping冲突 → strict模式
- Bulk批大小 → 500-5000条/批

### Docker/K8s
- OOM Killed → JVM堆=容器内存60%
- 健康检查不当 → initialDelaySeconds=30/60
- 时区问题 → ENV TZ=Asia/Shanghai

### 分布式锁
- 锁没释放 → finally中unlock
- 看门狗续期失败 → 设置足够长的leaseTime

### JWT
- Token刷新竞态 → Redis分布式锁
- Token无法主动失效 → Redis黑名单

### Canal
- binlog位点丢失 → 定期全量重建
- 消费延迟 → 并行消费+批量处理

### XXL-Job
- 任务重复执行 → 幂等+串行策略
- 分片不均匀 → 哈希分片替代范围分片

---

## 🎤 七、面试考察点

### Q1: 生产环境遇到过什么坑？

> 按组件分类讲，每个坑说清楚：**现象→原因→解决→教训**

### Q2: MySQL主从延迟怎么解决？

> 1. "关键查询走主库：下单后5秒内的订单查询强制走主库"
> 2. "ShardingSphere HintManager设置主库路由"
> 3. "前端优化：下单成功页延迟3秒再查订单列表"

---

## 📚 参考资料

| 资料 | 参考内容 |
|------|----------|
| 📄 09-技术踩坑指南.md | 12组件42个坑完整详情 |
| 📄 02-模块详细设计.md §1.8.1 | Feign 6大坑 |
| 📄 04-基础设施与部署.md §9 | Redis治理(大Key/连接池/内存) |
