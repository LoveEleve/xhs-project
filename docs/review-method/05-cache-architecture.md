# 05 缓存架构

> 复审维度 05 | 每个模块必查 | 9 透镜全覆盖，缓存的正确性/性能/降级为本维度核心
>
> 你是谁：分布式系统审查员。不凭记忆，逐条执行以下指令。每个检查项标注了透镜类型，覆盖
> 业务逻辑/工程正确性/分布式正确性/生产级/可扩展性/并发/微服务/性能/盲区 全部 9 个维度。
> 注：缓存权威方向（L1 vs Cache-Aside）的判定规则见 03.1；Canal 回声防护见 03.7。

---


**执行本维度后，必须在审查报告中输出 `[05] 05 缓存架构：发现 N 项`。缺失此标题 = 本维度未被执行。执行每条检查项时，标注来源维度编号（如 [05]）。**
## 检查项

### 5.1 缓存穿透/击穿/雪崩 | 透镜：生产级/工程

**必须检查**：每种缓存访问模式的防护是否完备——穿透（不存在的 key 穿透到 DB）、击穿（热点 key 过期瞬间打挂 DB）、雪崩（大量 key 同时过期）。

**怎么查**：
```bash
grep -rn 'opsForValue().get\|redisOperator.get\|boundValueOps\|redisTemplate.*get' my-xhs-<module>/src/main/java/
# 逐 get 点检查：null 后的处理——是穿透到 DB 还是返回空？热点 key 有锁吗？
```

**判定**：

| 场景 | 缺陷模式 | 正确形态 |
|------|---------|---------|
| 穿透 | 查 DB 也为 null→回种 null→但下一次仍穿透（没缓存 null） | 缓存空值（短 TTL）+ 布隆预筛 |
| 击穿 | 热点 key 过期→大量线程同时查 DB | 分布式锁 + 双重检查（第一个拿到锁的查 DB 回种，其余等锁后读缓存） |
| 雪崩 | 所有 key TTL 相同→同时过期→DB 瞬间被打挂 | TTL 加随机抖动 ±20%；或永不过期 + 逻辑过期 |
| 冷Key并发miss | 缓存 miss→大量线程无并发控制直接查 DB→1000 个 DB 查询→击穿 | 分布式锁 `setIfAbsent` + 双重检查，锁粒度 = 缓存 key 粒度 |

**案例**：05-product `getSpuDetail` 缓存 miss 后无锁直接查 DB→冷 Key 并发 miss 击穿（修复加分布式锁 + 双重检查）；商品 key 统一 TTL 1h→雪崩风险（修复加随机抖动）。

---

### 5.2 缓存降级链完整性 | 透镜：生产级/盲区

**必须检查**：每个 Redis 操作的 catch 块是否覆盖了降级路径——Redis 不可用时，用户是否还能得到数据（通过 DB 降级）而非直接 500。

**怎么查**：
```bash
grep -rn 'redisOperator\.\|redisTemplate\.\|stringRedisTemplate\.' my-xhs-<module>/src/main/java/ -A3 | grep -B3 'catch\|try'
```
逐 Redis 操作检查：有 try-catch 吗？catch 块是 500 还是降级查 DB？

**判定**：

| 检查点 | 问题模式 |
|--------|---------|
| get 有 catch / set 没有 | Redis SET 失败抛异常→读路径有降级、写路径直接 500→不一致 |
| catch 块无操作 | `catch(Exception e) { log.error(...) }` → 上层收到 null→NPE |
| 降级路径无监控 | 切到 DB 降级但无 metric→Redis 挂了 30 分钟后端团队不知道 |

**案例**：05-product `getSpuDetail` SET 有 catch 但 WARM-UP GET 写路径无 catch→half-coverage（修复补全所有 Redis 写操作的 try-catch）。

---

### 5.3 布隆过滤器正确性 | 透镜：工程/性能

**必须检查**：如果用了布隆过滤器（BloomFilter），容错率、初始化大小、重建机制是否合理。

**怎么查**：
```bash
grep -rn 'BloomFilter\|bloom\|Bloom' my-xhs-<module>/src/main/java/
```

**判定**：

| 检查点 | 问题模式 |
|--------|---------|
| `mightContain` 无 try-catch | 布隆 Redis 连接断开→访问所有 key 都穿透到 DB |
| 误判率过高 | 允许 0.01 但预期数据量百万级→实际误判率 >10% |
| 无重建机制 | 服务重启后布隆为空→全部认为不存在→全部穿透到 DB |
| contains→不存在→深信不疑 | 误判（明明存在但布隆说不存在）的概率非零→偶发穿透 |

**案例**：05-product `SpuService.getDetail` 布隆 `contains` 无 try-catch→布隆故障时全穿透打挂 DB（修复加 try-catch 降级：布隆故障→跳过布隆直接查缓存+DB）。

---

### 5.4 BigKey / HotKey | 透镜：性能/盲区

**必须检查**：Redis 中存储的值是否可能膨胀为 BigKey（大 Hash/大 Set/大 String）；是否存在 HotKey（单个 key 被高频访问）。

**怎么查**：
```bash
# 大集合操作——SMEMBERS/HGETALL 在 Service 中的调用
grep -rn 'SMEMBERS\|HGETALL\|LRANGE.*0.*-1\|get.*All\|members()\|entries()' my-xhs-<module>/src/main/java/

# 大 String——set 值时的大小
grep -rn 'redisOperator.set\|stringRedisTemplate.*set.*json\|toJSONString' my-xhs-<module>/src/main/java/
```

**判定**：

| 检查点 | 问题模式 |
|--------|---------|
| `SMEMBERS` 百万级 Set | 单次返回全部元素→Redis 阻塞 + 网络传输 OOM |
| `HGETALL` 大 Hash | 同上 |
| 序列化整个对象树 | `JSON.toJSONString(entity)` 包含嵌套集合→一条 Value 几 MB |
| HotKey 无本地缓存 | 某页面高频访问→每次查同一个 Redis key→压垮单分片 |

**案例**：03-analytics `SMEMBERS myxhs:like:note:{noteId}` 百万赞→OOM（修复改分页 SSCAN）；cart `getCartList` HGETALL→大数据量。

---

### 5.5 缓存更新与一致时效性 | 透镜：工程/分布式

**必须检查**：写操作后缓存更新/失效的时机——是同步删缓存、afterCommit 删缓存、还是等 TTL 自然过期。

**怎么查**：
```bash
grep -rn '@Transactional\|@CacheEvict\|redisOperator.*del\|redisTemplate.*delete' my-xhs-<module>/src/main/java/
```
逐写操作确认：缓存失效的时机和方式。

**判定**：

| 模式 | 风险 | 适用场景 |
|------|------|---------|
| 同步 `del(key)`（事务内） | 事务回滚但缓存已删→下次读空缓存回填旧值 | Cache-Aside 经典问题——应 afterCommit 删 |
| `afterCommit` 删缓存 | commit 和 afterCommit 之间 crash→缓存永久残留（03.1 已覆盖） | CDB 加 TTL + 对账兜底 |
| 纯 TTL 过期 | 写操作后 TTL 内读到旧值 | 对实时性要求低的元数据（分类树/配置）可接受 |
| `set(key, entity)`（更新缓存） | 并发 `set` VS `del`→两个写操作互相覆盖→脏数据 | 永远不 SET 新值——删缓存，读时回填 |

**案例**：05-product `updateSpu` 同步 `del(key)`（事务内）→事务回滚缓存已删→可选读回旧值（修复改为 `afterCommit` 删缓存）。

---

### 5.6 TTL 合理性 | 透镜：工程/生产级

**必须检查**：每个缓存 key 的 TTL 是否根据数据变更频率做了差异化设置——是否有全模块同一 TTL 导致缓存更新不及时或资源浪费。

**怎么查**：
```bash
grep -rn 'opsForValue().set\|redisOperator.set\|expire\|Expire' my-xhs-<module>/src/main/java/ -A1 | grep -E 'Duration|TimeUnit|[0-9]+,'
```
逐 key 评估 TTL 是否匹配业务特征。

**判定**：
- 静态数据（分类树）TTL 5 分钟→太短，应 1h+
- 动态数据（库存/计数）TTL 1h→太长，用户看到 1h 前数据
- 所有 key 同一 TTL→雪崩风险（见 5.1）
- 没有逻辑过期机制→缓存 miss 时延迟高

**案例**：注释写"缓存 1 小时"但实际 2h→注释与代码不一致；商品分类树 5min TTL→浪费 Redis QPS。

---

### 5.7 缓存中间件耦合 | 透镜：可扩展性

**必须检查**：模块是否直接写死 Redis API 还是通过抽象层。切换缓存的代价。

**怎么查**：
```bash
grep -rn 'redisOperator\.\|stringRedisTemplate\.\|redisTemplate\.' my-xhs-<module>/src/main/java/ | wc -l
grep -rn 'cacheManager\|@Cacheable\|@CacheEvict\|@CachePut' my-xhs-<module>/src/main/java/ | wc -l
```

**判定**：前者远大于后者→直接绑定 Redis API→切 Caffeine/Hazelcast 需改所有缓存代码。**记录即可，不强制修改。**

**案例**：（全特性面预置检查项——my-xhs 全量直接依赖 redisOperator/stringRedisTemplate，Spring Cache 抽象层未使用。）

---

### 5.8 缓存序列化兼容性 | 透镜：工程/盲区

**必须检查**：缓存值的序列化方式（JSON/ProtoBuf/JavaSerializable）在实体字段增删后是否会导致反序列化失败。

**怎么查**：
```bash
grep -rn 'toJSONString\|JSON.toJSON\|ObjectMapper\|Jackson' my-xhs-<module>/src/main/java/ | grep -i 'cache\|redis'
grep -rn 'class.*\|implements Serializable' my-xhs-<module>/src/main/java/com/myxhs/*/dto/
```

**判定**：
- JSON 反序列化缺字段不抛异常（Gson/Jackson 默认安全）→ 加字段后旧缓存可正常读取
- Java Serializable 序列化→ 类变更 `serialVersionUID` 不一致→ 反序列化失败→ 全量缓存 miss→ 打挂 DB
- ProtoBuf 缺 UnknownFields 处理→ 新字段反序列化到旧 Schema→ 数据丢失

**案例**：（全特性面预置检查项——my-xhs 使用 Jackson JSON 序列化，字段增删兼容性较好，但 ProtoBuf/Serializable 场景需严格审查。）

---

## 跨维度引用

| 相关概念 | 主维度 | 说明 |
|---------|:--:|------|
| 缓存权威方向（L1 vs Cache-Aside） | 03.1 | 判定合法模式 + afterCommit 风险 |
| Canal/外部回声防护 | 03.7 | L1 权威下 Canal UPDATE DELETE 的致命性 |
| 缓存回填安全 | 03.5 | 滞后 MySQL 覆盖 Redis 权威 |
| Redis+DB 双写回滚 | 03.2 | MQ/DB 双操作失败回滚 |

---

## 验证命令汇总

```bash
mvn compile test-compile -pl <module> -am
mvn test -pl <module>

# Redis get→DB 查询（穿透/击穿路径）
grep -rn 'opsForValue().get\|boundValueOps' my-xhs-<module>/src/main/java/ -A5 | grep -B3 'selectById\|selectOne\|selectList'

# Redis 操作 try-catch 覆盖率
grep -rn 'redisOperator\.\|stringRedisTemplate\.\|redisTemplate\.' my-xhs-<module>/src/main/java/ -B1 | grep -B1 'try'

# 大集合操作
grep -rn 'SMEMBERS\|HGETALL\|LRANGE.*0.*-1\|members()\|entries()' my-xhs-<module>/src/main/java/

# 布隆过滤器
grep -rn 'BloomFilter\|bloom\|Bloom' my-xhs-<module>/src/main/java/

# 事务内删缓存
grep -rn '@Transactional' my-xhs-<module>/src/main/java/ -l | xargs grep -l 'redisOperator.*del\|redisTemplate.*delete' 2>/dev/null

# TTL 设置
grep -rn 'opsForValue().set\|redisOperator.set\|\.expire(' my-xhs-<module>/src/main/java/ -A1 | grep -E 'Duration|TimeUnit|[0-9]+,'
```
