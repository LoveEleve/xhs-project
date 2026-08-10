# 16 社交与计数

> 复审维度 16 | 覆盖模块：03-analytics, 04-counter | 领域专属检查项
>
> 你是谁：分布式系统审查员。不凭记忆，逐条执行以下指令。本维度覆盖关注图/双计数/Buffer攒批等社交计数独有问题。
> 通用规则：并发见 02、MQ见 04、数据一致性见 03。

---


**执行本维度后，必须在审查报告中输出 `[16] 16 社交与计数：发现 N 项`。缺失此标题 = 本维度未被执行。执行每条检查项时，标注来源维度编号（如 [16]）。**
## 检查项

### 16.1 关注图一致性 | 透镜：业务/分布式

**必须检查**：关注/取关后的双向数据（followee 列表 + follower 列表）是否保持一致；是否有对账修复。

**怎么查**：
```bash
grep -rn 'follow\|unfollow\|followee\|follower\|addFollow\|removeFollow' my-xhs-analytics/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 关注成功/粉丝更新失败 | addFollow→A 的 followingSet 加了 B→B 的 followerSet 写失败→单向关注 |
| 对账只扫一侧 | repairFollowerRelationships 只扫 follower → followee 侧不修复 |
| 取关未传播 | unfollow 后不发 MQ→counter 的 followCount 不更新→计数残留 |
| 关注列表无分页 | SMEMBERS 全量取百万关注→OOM |

**案例**：`FollowCounterRepairJob` 只扫描 `user_id`→只修复 followee 侧，follower 侧永不修复（`FollowMapper.selectDistinctUserIds` 修复加 UNION）。

---

### 16.2 双计数体系权威方 | 透镜：微服务/业务

**必须检查**：analytics 和 counter 两个服务各维同一份计数——是否指定了权威方、有对账、有数据同步。

**怎么查**：
```bash
grep -rn 'reconcile\|counter.*analytics\|analytics.*counter\|count.*like\|like.*count' my-xhs-counter/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 无权威方 | 两份计数各写各的→漂移后无自愈 |
| 对账未执行 | reconcileLikeFromAnalytics 写了对账但无定时触发 |
| 对账方向错误 | 用 counter 修 analytics→counter 不是权威→数据越来越错 |

**案例**：analytics 维护 like:set = 权威方，counter 维护独立计数→`CounterService.reconcileLikeFromAnalytics` 以 analytics 为权威对账（修复加定期执行）。

---

### 16.3 CounterBuffer 攒批正确性 | 透镜：并发/性能

**必须检查**：CounterBuffer 的双 Buffer 交换是否原子；跨代写入是否防护；flush 频率是否合理。

**怎么查**：
```bash
grep -rn 'CounterBuffer\|swapBuffer\|flush\|Buffer\|batchCount' my-xhs-counter/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| swap 后跨代写入 | 线程 A 拿到 buffer1→swap→线程 A 的写入落入了已 swap 的旧 buffer→丢失 |
| flush 频率过高 | 100ms flush 一次→Redis QPS 爆炸 |
| flush 频率过低 | 10s flush 一次→关闭时 buffer 中最后 10s 全丢 |
| Buffer 满不 flush | Buffer 达到上限不主动 flush→等定时→数据延迟 |

**案例**：`CounterBuffer.add()` swap 后跨代写入丢失（`CounterBuffer.java:86` 修复重试循环：写入后 check current==buffer，不一致撤销重试）。

---

### 16.4 Set 幂等与计数原子性 | 透镜：并发/业务

**必须检查**：点赞/收藏的 Set 添加和计数增减是否在同一原子操作中；Set 的 SADD 天然幂等但计数器 INCR 不是。

**怎么查**：
```bash
grep -rn 'SADD\|SREM\|SCARD\|INCR\|DECR\|like\|unlike\|favorite\|unfavorite' my-xhs-analytics/src/main/java/ my-xhs-counter/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| SADD 和 INCR 非原子 | SADD 成功→然后 INCR→INCR 前 crash→Set 有数据计数不对 |
| 重复 like 照样 INCR | SISMEMBER 查重复→但查和 INCR 之间有窗口→并发双 like→计数+2 |
| Set 迁移无懒迁移 | 旧 Set 结构上线新集→历史数据归零→点赞数断崖下跌 |

**案例**：`LIKE_SET_SCRIPT` 上线无懒迁移→历史点赞归零（`CounterService.java:309` 懒迁移修复）；SADD 天然幂等但计数 INCR 无前置检查→并发重复计数。

---

## 跨维度引用

| 相关概念 | 主维度 | 说明 |
|---------|:--:|------|
| 对账任务扫描范围 | 03.4 | UNION 双方向 |
| CounterBuffer 双 Buffer 交换 | 02.5 | CHM 复合操作原子性 |
| MQ 重复消费幂等 | 04.1 | Consumer 去重 key 维度 |
| Lua 脚本原子性 | 02.3 | Set 增删 + 计数原子操作 |

---

## 验证命令汇总

```bash
mvn compile test-compile -pl my-xhs-analytics,my-xhs-counter -am
mvn test -pl my-xhs-analytics,my-xhs-counter

# 关注图
grep -rn 'follow\|unfollow\|followee\|follower' my-xhs-analytics/src/main/java/

# 双计数对账
grep -rn 'reconcile\|counter.*analytics\|analytics.*counter' my-xhs-counter/src/main/java/

# CounterBuffer
grep -rn 'CounterBuffer\|swapBuffer\|flush\|Buffer' my-xhs-counter/src/main/java/

# Set幂等/计数
grep -rn 'SADD\|SREM\|INCR\|DECR\|like\|unlike' my-xhs-analytics/src/main/java/ my-xhs-counter/src/main/java/
```
