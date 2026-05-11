# my-xhs 技术踩坑指南

> 生产环境踩过的坑，不是"可能遇到"，是"一定会遇到"。
> 每个坑都附带防坑方案，my-xhs 必须规避。

---

## 一、Redis 坑

### 坑1：大Key导致Redis阻塞

```bash
# ❌ 一个Hash存100万字段，HGETALL要3秒，阻塞Redis所有其他请求
HGETALL social:follower:bigV_userId   # 100万粉丝的ZSet

# ✅ 解决：拆分 + SCAN遍历
# 大V粉丝分桶：social:follower:bucket:userId:0 ~ :9
# 遍历用HSCAN/ZSCAN，不用HGETALL/ZRANGE全量返回

# 防御：XXL-Job每天扫描大Key
redis-cli --big-keys -i 0.1
```

### 坑2：缓存击穿——热点Key过期瞬间DB被打爆

```java
// ❌ 高并发下Key过期，1000个请求同时查DB
// ✅ 互斥锁：只让1个请求查DB重建缓存
public String getWithLock(String key) {
    String value = redis.get(key);
    if (value == null) {
        String lockKey = "lock:" + key;
        if (redis.setnx(lockKey, "1", 10)) {  // 10秒锁
            try {
                value = db.query(key);
                redis.set(key, value, 3600);
            } finally {
                redis.del(lockKey);
            }
        } else {
            Thread.sleep(100);  // 等待100ms后重试
            return getWithLock(key);
        }
    }
    return value;
}
```

### 坑3：Redis主从切换时数据丢失

```
问题：主从异步复制，主节点宕机时，从节点可能还没收到最新数据
影响：购物车数据、计数数据可能丢失最近1-2秒的写入

解决：
1. 购物车：异步落MySQL兜底，主从切换后从MySQL恢复
2. 计数：Buffer-Trigger数据在内存，主从切换后对账修复
3. 关键数据(库存)：用Redis Lua原子操作+XXL-Job对账
```

### 坑4：Redis连接池耗尽

```yaml
# ❌ 默认连接池太小
spring:
  redis:
    lettuce:
      pool:
        max-active: 8    # 默认8，高并发下不够

# ✅ 生产环境配置
spring:
  redis:
    lettuce:
      pool:
        max-active: 100   # 最大连接数
        max-idle: 50      # 最大空闲连接
        min-idle: 10      # 最小空闲连接
        max-wait: 3000ms  # 获取连接最大等待时间
```

### 坑5：Keys命令导致Redis卡死

```bash
# ❌ 绝对不能用KEYS命令，1000万Key要扫10秒
KEYS cart:items:*

# ✅ 用SCAN命令
SCAN 0 MATCH cart:items:* COUNT 100
```

---

## 二、RocketMQ 坑

### 坑1：消息重复消费

```java
// ❌ 消费端不做幂等，MQ至少投1次=可能投2次
// 下单消息重复消费=重复扣库存

// ✅ 幂等消费：业务唯一键去重
@RocketMQMessageListener(topic = "order_create")
public class OrderCreateConsumer implements RocketMQListener<MessageExt> {
    @Override
    public void onMessage(MessageExt msg) {
        String bizId = msg.getKeys();  // orderId作为幂等Key
        // Redis SET NX判断是否已处理
        if (!redis.setnx("idempotent:" + bizId, "1", 86400)) {
            log.info("重复消息，跳过: {}", bizId);
            return;
        }
        // 业务处理...
    }
}
```

### 坑2：消费堆积

```
问题：消费速度 < 生产速度，消息堆积
原因：1.消费端有慢查询 2.消费端异常重试 3.突发流量

监控：
  rocketmq_consumer_built_message_accumulation > 5000 → 告警

解决：
1. 临时增加消费者实例数
2. 消费端优化(批量消费、异步处理)
3. 非核心消息跳过(记录日志后丢弃，后续补消费)
```

### 坑3：延时消息不准时

```
问题：RocketMQ延时消息只有18个固定级别(1s/5s/10s/30s/1m/2m/5m...2h)
     不能精确到任意时间

场景：超时关单需要精确30分钟
解决：延时消息(主) + XXL-Job定时扫描(兜底)
     - 延时消息选30m级别，可能提前/延后1分钟
     - XXL-Job每分钟扫描，保证不会漏掉
```

### 坑4：事务消息回查频率过高

```java
// ❌ 本地事务执行太慢(>60秒)，Broker频繁回查
// ✅ 本地事务尽量快，复杂操作放异步
// 事务消息回查间隔：15秒(默认)，最多回查15次

@Transactional
public void createOrder(OrderCreateCmd cmd) {
    // 快速：只写order表和local_message表
    orderMapper.insert(order);
    localMessageMapper.insert(message);
    // 慢操作异步：扣库存、扣券 → 消费端处理
}
```

---

## 三、MySQL 坑

### 坑1：慢查询——索引失效

```sql
-- ❌ 索引失效的常见场景

-- 1. 函数操作索引列
SELECT * FROM t_order WHERE DATE(create_time) = '2025-05-09'  -- 索引失效！
-- ✅ 范围查询
SELECT * FROM t_order WHERE create_time >= '2025-05-09' AND create_time < '2025-05-10'

-- 2. 隐式类型转换
SELECT * FROM t_user WHERE phone = 13800138000  -- phone是VARCHAR，传入INT，索引失效！
-- ✅ 类型匹配
SELECT * FROM t_user WHERE phone = '13800138000'

-- 3. LIKE左模糊
SELECT * FROM t_note WHERE title LIKE '%美食%'  -- 索引失效！
-- ✅ 右模糊可以走索引
SELECT * FROM t_note WHERE title LIKE '美食%'

-- 4. OR条件
SELECT * FROM t_order WHERE user_id = 1 OR status = 'PAID'  -- 全表扫描！
-- ✅ UNION ALL
SELECT * FROM t_order WHERE user_id = 1
UNION ALL
SELECT * FROM t_order WHERE status = 'PAID' AND user_id != 1
```

### 坑2：分库分表后跨库查询

```sql
-- ❌ 分库后不能JOIN跨库的表
SELECT o.*, i.* FROM t_order o JOIN t_order_item i ON o.id = i.order_id
-- 订单和订单明细在不同库，JOIN失败！

-- ✅ 解决1：冗余字段（订单表冗余商品名称/价格，避免查商品表）
-- ✅ 解决2：应用层聚合（先查订单，再根据skuId批量查商品）
-- ✅ 解决3：ES宽表（Canal同步订单+商品到ES，搜索走ES）
```

### 坑3：主从延迟导致读不到刚写的数据

```
问题：下单后立即查询，读从库，主从延迟500ms，查不到刚下的单
影响：用户刚下完单，订单列表里看不到新订单

解决：
1. 关键查询走主库（下单后5秒内的订单查询走主库）
2. 强制路由：ShardingSphere HintManager设置主库路由
3. 前端优化：下单成功页不主动查订单列表，延迟3秒后再查
```

### 坑4：连接池耗尽

```yaml
# ❌ 默认连接池太小
spring:
  datasource:
    hikari:
      maximum-pool-size: 10  # 默认10，高并发不够

# ✅ 生产环境配置
spring:
  datasource:
    hikari:
      maximum-pool-size: 50
      minimum-idle: 10
      connection-timeout: 3000    # 获取连接超时3秒
      idle-timeout: 600000        # 空闲连接超时10分钟
      max-lifetime: 1800000      # 连接最大存活30分钟
```

---

## 四、Spring Boot / Spring Cloud 坑

### 坑1：@Transactional失效

```java
// ❌ 5种常见失效场景

// 1. 自调用（同类方法调用，AOP不生效）
@Service
public class OrderService {
    public void createOrder() {
        this.deductStock();  // ❌ 事务不生效！
    }
    @Transactional
    public void deductStock() { ... }
}
// ✅ 注入自身或用AopContext.currentProxy()

// 2. 异常被catch未抛出
@Transactional
public void createOrder() {
    try {
        deductStock();
    } catch (Exception e) {
        log.error("扣减失败", e);  // ❌ 异常被吞了，事务不回滚！
    }
}
// ✅ catch后手动回滚：TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();

// 3. 异常类型不匹配（默认只回滚RuntimeException）
@Transactional
public void createOrder() throws IOException {
    throw new IOException("文件错误");  // ❌ 不回滚！
}
// ✅ 指定回滚类型：@Transactional(rollbackFor = Exception.class)

// 4. 方法不是public
@Transactional
void createOrder() { ... }  // ❌ 默认只代理public方法

// 5. 数据库引擎不支持事务
// MySQL的MyISAM引擎不支持事务，必须用InnoDB
```

### 坑2：Nacos服务下线延迟

```
问题：服务实例宕机后，Nacos感知+推送需要10-30秒
影响：30秒内请求可能打到已下线的实例

解决：
1. Gateway加重试（同一服务不同实例重试）
2. 客户端加缓存（Nacos本地缓存，即使Nacos挂了也能用缓存路由）
3. K8s PreStop钩子：先从Nacos注销 → 等10秒 → 进程退出
```

### 坑3：Spring Boot 3.x 的坑

```
1. javax → jakarta 命名空间迁移
   ❌ import javax.servlet.http.HttpServletRequest;
   ✅ import jakarta.servlet.http.HttpServletRequest;

2. Spring Cloud 2023.x 必须用 Spring Boot 3.2+
   版本不匹配会报 ClassNotFoundException

3. Spring Cloud Gateway 基于 WebFlux，不能用 spring-web
   ❌ 在Gateway中用 HttpServletRequest（不存在）
   ✅ 用 ServerWebExchange
```

---

## 五、ShardingSphere 坑

### 坑1：分片键不在WHERE条件中 → 全库路由

```sql
-- ❌ 不带分片键的查询 = 扫描所有分库
SELECT * FROM t_order WHERE status = 'PAID'  -- 扫描4个库！

-- ✅ 必须带分片键
SELECT * FROM t_order WHERE buyer_id = 1001 AND status = 'PAID'  -- 路由到1个库
```

### 坑2：分布式主键冲突

```
问题：分库后自增ID在各库独立，会冲突
  库1: 1,2,3,4,5...
  库2: 1,2,3,4,5...  ← 冲突！

解决：雪花ID（全局唯一，趋势递增）
  ShardingSphere配置：snowflake算法
  注意：时钟回拨问题 → 用Redis分配WorkerId
```

### 坑3：分库分表后LIMIT分页性能差

```sql
-- ❌ 深分页：每个分片都要查前10000条再合并
SELECT * FROM t_order ORDER BY create_time LIMIT 10000, 10

-- ✅ 游标分页（记住上次的位置）
SELECT * FROM t_order WHERE create_time > '2025-05-09 12:00:00' 
ORDER BY create_time LIMIT 10
```

---

## 六、ES 坑

### 坑1：深分页导致OOM

```java
// ❌ from+size超过10000报错
GET /note_index/_search { "from": 10000, "size": 10 }

// ✅ search_after游标分页
GET /note_index/_search {
  "size": 10,
  "query": { "match_all": {} },
  "sort": [{ "createTime": "desc" }, { "_id": "asc" }],
  "search_after": ["2025-05-09T12:00:00", "note_123"]
}
```

### 坑2：Mapping冲突

```
问题：不同服务写入同一索引，字段类型不一致
  商品服务写入 price=99.9(浮点)
  订单服务写入 price="99.9"(字符串) → 报错！

解决：
1. 严格Mapping：创建索引时指定所有字段类型，不允许自动推断
2. 禁止dynamic mapping：设置 "dynamic": "strict"
3. 统一写入入口：通过search服务写入，不直接写ES
```

### 坑3：Bulk写入批大小不当

```
问题：
- 批太小(10条) → 网络开销大，写入慢
- 批太大(10万条) → ES内存溢出

最佳实践：
- 每批500-5000条
- 批大小控制在5-15MB
- 定期flush
```

---

## 七、Docker/K8s 坑

### 坑1：OOM Killed

```yaml
# ❌ 容器内存限制太小，JVM堆超出被杀
resources:
  limits:
    memory: 512Mi  # JVM堆+元空间+堆外内存 > 512Mi → OOM Killed

# ✅ 正确配置
resources:
  limits:
    memory: 1Gi
  requests:
    memory: 512Mi

# JVM参数：堆设为容器内存的60-70%
JAVA_OPTS: "-Xmx700m -Xms700m -XX:MaxMetaspaceSize=200m"
```

### 坑2：健康检查配置不当

```yaml
# ❌ 就绪探针检查DB连接，DB慢导致Pod被反复重启
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
  initialDelaySeconds: 5   # 太短，Spring Boot还没启动完
  periodSeconds: 5         # 太频繁

# ✅ 正确配置
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
  initialDelaySeconds: 30  # 给Spring Boot足够的启动时间
  periodSeconds: 10
  failureThreshold: 3      # 连续失败3次才标记不就绪
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
  initialDelaySeconds: 60  # 活性探针延迟更久，避免启动期被杀
  periodSeconds: 15
  failureThreshold: 3
```

### 坑3：时区问题

```dockerfile
# ❌ 容器默认UTC时区，日志时间差8小时
# ✅ 设置时区
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone
```

---

## 八、分布式锁坑

### 坑1：锁没释放（业务异常）

```java
// ❌ 业务异常后锁没释放，其他线程永远等
RLock lock = redisson.getLock("order:" + orderId);
lock.lock();
try {
    createOrder();  // 抛异常 → 没走到unlock
} finally {
    lock.unlock();  // ✅ 必须在finally中释放
}
```

### 坑2：看门狗续期失败

```java
// ❌ Redisson看门狗默认30秒续期，但业务执行1小时
// 如果Redis主从切换期间续期请求丢失 → 锁过期 → 并发问题
// ✅ 方案1：设置足够长的leaseTime
lock.lock(300, TimeUnit.SECONDS);  // 5分钟租约

// ✅ 方案2：业务中主动续期
lock.lock();
try {
    while (stillProcessing) {
        lock.expire(300, TimeUnit.SECONDS);  // 主动续期
        doWork();
    }
} finally {
    lock.unlock();
}
```

---

## 九、JWT 坑

### 坑1：Token刷新竞态条件

```java
// ❌ 两个请求同时发现AccessToken过期，都去刷新
// RefreshToken只能用1次，第二个请求失败

// ✅ 解决：Redis分布式锁控制刷新
public Token refreshToken(String refreshToken) {
    String lockKey = "refresh_lock:" + refreshToken;
    RLock lock = redisson.getLock(lockKey);
    if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {
        try {
            // 二次检查：可能其他线程已经刷新了
            String newAccessToken = redis.get("token:refresh:" + refreshToken);
            if (newAccessToken != null) return newAccessToken;
            // 执行刷新...
        } finally {
            lock.unlock();
        }
    }
}
```

### 坑2：Token无法主动失效

```
问题：JWT是无状态的，签发后无法撤回
场景：用户修改密码后，旧Token应该失效

解决：Redis黑名单
  用户修改密码 → 将旧Token的jti加入Redis黑名单(TTL=Token剩余过期时间)
  Gateway鉴权时检查黑名单：jti在黑名单 → 拒绝
```

---

## 十、Canal 坑

### 坑1：Binlog位点丢失

```
问题：Canal宕机后重启，从上次位点继续，但中间的binlog可能已被清理
影响：数据变更事件丢失，ES/缓存不同步

解决：
1. 定期全量重建（XXL-Job每周全量ES重建）
2. 对账检测（XXL-Job每天对账MySQL和ES数据量）
3. Canal HA模式（两个Canal实例，主备切换）
```

### 坑2：Canal消费延迟

```
问题：高峰期binlog产生速度 > Canal消费速度
影响：缓存删除延迟，用户看到旧数据

监控：Canal延迟 > 30秒 → 告警
解决：
1. Canal并行消费（多个消费者实例）
2. 批量消费（攒批100条binlog事件一次处理）
3. 非核心表不做Canal监听（只监听核心表：商品/订单/笔记）
```

---

## 十一、XXL-Job 坑

### 坑1：任务重复执行

```
问题：调度中心以为上次执行超时，重复调度
场景：超时关单扫描，重复扫描=重复关单

解决：
1. 任务配置：路由策略=故障转移，避免多实例重复执行
2. 幂等保障：关单前检查订单状态(只有"已创建"才能关)
3. 并发策略：SERIAL_EXECUTION（单机串行）
```

### 坑2：分片任务不均匀

```
问题：分片按ID范围，但ID分布不均匀
场景：100万用户分10片，但用户1-10万占80%流量

解决：
1. 按用户ID哈希分片（而非范围）
2. 监控各分片处理耗时，调整分片策略
```

---

## 十二、坑汇总速查表

| 组件 | 最致命的坑 | 影响 | 防御 |
|------|-----------|------|------|
| **Redis** | 大Key阻塞 | Redis卡死3秒 | XXL-Job扫描大Key |
| **RocketMQ** | 消息重复消费 | 重复扣库存 | 幂等Key去重 |
| **MySQL** | 索引失效 | 慢查询拖垮DB | 慢查询日志+explain |
| **Feign** | GET传POJO参数丢 | 接口调用失败 | @SpringQueryAnnotation |
| **事务** | @Transactional失效 | 数据不一致 | 5种失效场景排查 |
| **ShardingSphere** | 不带分片键=全库扫描 | 查询超时 | 必须带分片键 |
| **ES** | 深分页OOM | ES崩溃 | search_after |
| **K8s** | OOM Killed | Pod被杀 | JVM堆=容器60% |
| **分布式锁** | 锁没释放 | 死锁 | finally释放 |
| **JWT** | Token无法失效 | 账号被盗 | Redis黑名单 |
| **Canal** | 位点丢失 | 数据不同步 | 定期全量重建 |
| **XXL-Job** | 任务重复执行 | 重复关单 | 幂等+串行策略 |
