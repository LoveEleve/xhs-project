# 动态组件多活与流量调度

> 跨服务专题 | 开发阶段：Phase-7 | 核心组件：DynamicJdbcComponent + DynamicBeanComponent + TrafficScheduler

## 专题概要

本专题包含四个核心设计：动态 JDBC 组件（根据区域上下文自动切换数据源，业务代码对多活无感知）、动态 Spring Bean 组件（根据区域上下文动态切换 Bean 实现）、多活流量调度（全链路区域标记透传增强、流量比例分配、灰度恢复）；故障自动切换（机房级故障检测、流量自动切换、数据一致性保障）。

## 涉及服务

- my-xhs-common（组件提供方：DynamicJdbcComponent、DynamicBeanComponent、TrafficScheduler）
- my-xhs-gateway（流量调度入口：流量比例分配、故障切换触发）
- my-xhs-order（动态 JDBC 使用方：订单分库分表 + 多机房数据源）
- my-xhs-product（动态 Bean 使用方：不同机房不同的库存扣减策略）
- my-xhs-im（IM 多活：WebSocket 同区域路由、消息跨机房同步）

## 核心内容

| 维度 | 内容 |
|------|------|
| 动态 JDBC | DynamicJdbcComponent：写操作 force-local 路由本机房，读操作优先本机房降级对端 |
| 动态 Bean | @RegionBean 注解 + DynamicBeanComponent：按区域选择 Bean 实现 |
| 流量调度 | TrafficScheduler：权重管理 + Nginx 动态权重 + Nacos 配置变更通知 |
| 故障切换 | 半自动（检测自动告警 + 人工确认），阶梯恢复（10%→30%→50%→100%） |
| IM 多活 | WebSocket 同区域连接 + MQ 跨机房消息投递 + 断线重连到对端 |

## 重点覆盖

- DynamicJdbcComponent 与 ShardingSphere 的兼容（外层选机房 → 内层选分片）
- @RegionBean 注解扫描与动态注册机制
- TrafficScheduler 权重管理（Redis 持久化 + Nacos 配置通知 + Nginx Lua 动态更新）
- 故障切换 SOP（检测 → 告警 → 确认 → 切换 → 数据同步检查 → 灰度恢复）
- IM 服务 WebSocket 长连接的故障切换处理
- 故障切换时数据一致性保障（同步延迟检查 + 写入暂停 + Canal 兜底）

## 面试高频问题

- 动态 JDBC 组件如何保证写操作不跨机房？
- 故障切换时如何保证数据不丢失？
- 灰度恢复为什么要阶梯式？为什么不直接 100%？
- 动态 Bean 组件解决了什么问题？
- IM 服务多活和普通 HTTP 服务多活有什么不同？
- 你们的多活故障切换是自动的还是手动的？

## 补充一：ES 跨机房同步（CCR）详细设计

> 基础设施表中提到 ES 使用 Cross-Cluster Replication（CCR），但缺少配置和实现细节。ES 是搜索服务（my-xhs-content）的核心，必须补齐。

### CCR 架构

```
机房A（Leader 索引）          机房B（Follower 索引）
┌──────────────────┐         ┌──────────────────┐
│ ES Cluster A     │         │ ES Cluster B     │
│  - post_1 (Leader)│──CCR──→│  - post_1 (Follower)│
│  - post_2 (Leader)│──CCR──→│  - post_2 (Follower)│
│  - comment (Leader)│─CCR──→│  - comment (Follower)│
└──────────────────┘         └──────────────────┘
        ↑                           ↑
   Canal A 写入                 Canal B 写入
   （本机房 Binlog）             （本机房 Binlog）
```

### 关键配置

```yaml
# 1. 配置远端集群（在 ES Cluster B 上执行）
PUT _cluster/settings
{
  "persistent": {
    "cluster": {
      "remote": {
        "cluster-a": {
          "seeds": ["es-a-node1:9300", "es-a-node2:9300", "es-a-node3:9300"],
          "skip_unavailable": false
        }
      }
    }
  }
}

# 2. 创建 Follower 索引（自动跟随 Leader）
PUT post_1/_ccr/follow
{
  "remote_cluster": "cluster-a",
  "leader_index": "post_1",
  "read_only": true              # Follower 索引只读，写入必须走 Leader
}

# 3. 配置自动跟随模式（新索引自动同步）
PUT _ccr/auto_follow/my_xhs_ccr
{
  "remote_cluster": "cluster-a",
  "leader_index_patterns": ["post_*", "comment*"],
  "follow_index_pattern": "{{leader_index}}"
}
```

### CCR 与 Canal 的协作

| 组件 | 职责 | 数据流向 |
|------|------|---------|
| MySQL 双向复制 | MySQL 数据同步 | A MySQL ↔ B MySQL |
| Canal A → ES Cluster A | 写入本机房 Leader 索引 | Binlog → MQ → ES A（Leader） |
| CCR | ES 索引跨机房同步 | ES A Leader → ES B Follower |
| Canal B → ES Cluster B | 写入本机房 Leader 索引 | Binlog → MQ → ES B（Leader） |
| CCR（反向） | ES 索引跨机房同步 | ES B Leader → ES A Follower |

### 注意事项

1. **Follower 索引只读**：搜索请求可读 Follower，写入必须走 Leader
2. **索引模板需提前在两端创建**：mapping 和 setting 必须一致
3. **CCR 延迟通常 < 1s**：比 Canal 同步更接近实时
4. **索引删除需先停止跟随**：`POST post_1/_ccr/pause_follow` → 删除 → 重建

---

## 补充二：故障切换回滚机制

> 灰度恢复提到了回滚，但故障切换本身没有回滚。如果切换后发现是误判（如网络抖动导致假死），需要支持一键切回。

### 回滚流程

```
故障切换后5分钟内（观察期）：
  ┌──────────┐    ┌──────────┐    ┌──────────┐
  │ 机房A故障  │───→│ 切换到B   │───→│ 发现误判  │
  │ 流量→B    │    │ 流量→B    │    │ A实际正常  │
  └──────────┘    └──────────┘    └─────┬────┘
                                        │
                                        ▼
                                  ┌──────────┐
                                  │ 执行回滚   │
                                  │ 流量切回A  │
                                  └──────────┘
```

### 回滚前置条件

```java
/**
 * 故障切换回滚器
 * 必须满足以下条件才能回滚
 */
@Component
public class FailoverRollbackHandler {

    public RollbackResult rollback(String targetRegion) {
        // 1. 验证原机房健康
        HealthResult health = regionHealthChecker.check(targetRegion);
        if (!health.isHealthy()) {
            return RollbackResult.fail("原机房不健康，无法回滚");
        }

        // 2. 验证数据同步追平
        // 注意：原机房刚恢复时，可能还在回放积压的 Binlog，
        // 需等待至少一个完整的复制周期后再检查延迟
        SyncStatus mysqlSync = checkMySQLSyncStatus(targetRegion);
        if (mysqlSync.getDelayMs() > 1000) {  // 延迟 > 1s 不允许回滚
            return RollbackResult.fail("MySQL同步未追平，延迟" + mysqlSync.getDelayMs() + "ms");
        }

        SyncStatus redisSync = checkRedisSyncStatus(targetRegion);
        if (redisSync.getDelayMs() > 500) {
            return RollbackResult.fail("Redis同步未追平");
        }

        // 3. 验证观察期（切换后5分钟内才允许回滚）
        if (failoverRecord.getElapsedMinutes() > 5) {
            return RollbackResult.fail("已超过5分钟回滚窗口，请走正常灰度恢复流程");
        }

        // 4. 执行回滚
        trafficScheduler.setWeight(targetRegion, 100);   // 原机房100%
        trafficScheduler.setWeight(currentRegion, 0);     // 当前机房0%
        dynamicRegionDataSource.setPrimaryRegion(targetRegion);

        // 5. 记录回滚操作
        auditLog.record("故障切换回滚", targetRegion, "手动回滚");

        return RollbackResult.success();
    }
}
```

### 回滚窗口设计

| 时间窗口 | 允许的操作 | 说明 |
|---------|-----------|------|
| 切换后 0-5 分钟 | 一键回滚 | 观察期，确认误判后快速回滚 |
| 切换后 5-30 分钟 | 灰度恢复（10%→30%→50%→100%） | 已过观察期，走正常灰度流程 |
| 切换后 > 30 分钟 | 灰度恢复 | 必须走灰度流程，不允许一键回滚 |

---

## 补充三：多活架构监控指标体系

> 多活场景下的监控维度与传统单机房不同，需要额外关注跨机房同步、对账、区域差异等指标。

### 核心监控指标

| 指标 | 采集方式 | 告警阈值 | 级别 | 说明 |
|------|---------|---------|------|------|
| MySQL 同步延迟（Seconds_Behind_Master） | `SHOW SLAVE STATUS` | > 5s P1，> 30s P0 | 核心 | 双向复制核心健康指标 |
| MySQL 复制中断 | `SHOW SLAVE STATUS` 的 `Last_Error` | 任何非空值 P0 | P0 | 复制链路断开 |
| Redis 主从延迟 | `INFO Replication` 的 `master_repl_offset` 差值 | > 1s P1，> 5s P0 | P1 | 主从数据差距 |
| Canal 消费延迟 | Canal Admin API / MQ 消费位点差 | > 10s P1，> 60s P0 | P1 | Canal 同步延迟 |
| ES CCR 延迟 | `_ccr/stats` API | > 3s P1，> 10s P0 | P1 | 索引同步延迟 |
| 机房网络 RT | Prometheus blackbox ping | > 10ms P1，> 50ms P0 | P1 | 同城应 < 3ms |
| 双机房错误率差值 | Prometheus HTTP 错误率 | 差值 > 5% P0 | P0 | 差值大说明某机房异常 |
| 对账差异条数 | `data_reconciliation_batch` 表 | > 100/min P0 | P0 | 数据不一致 |
| 活跃 WebSocket 连接数差值 | IM 服务 Metrics | > 50% 差异 P1 | P1 | 某机房连接突降 |
| Nacos 注册实例数差值 | Nacos Open API | > 10% 差异 P1 | P1 | 某机房注册异常 |
| Nacos 配置 MD5 不一致 | 定时对比脚本 | 任何不一致 P0 | P0 | 配置漂移 |

### Grafana 面板布局

```
┌─────────────────────────────────────────────────────┐
│  My-XHS 多活架构监控                                  │
├────────────────────┬────────────────────────────────┤
│  机房健康总览       │  数据同步延迟                     │
│  ┌──────┐┌──────┐  │  MySQL: ━━━━●━━  120ms          │
│  │ A ✅ ││ B ✅ │  │  Redis: ━━●━━━━   50ms          │
│  │ RT:2ms││ RT:3ms│  │  Canal: ━━━●━━━   80ms          │
│  └──────┘└──────┘  │  ES CCR: ━━●━━━━  60ms          │
├────────────────────┼────────────────────────────────┤
│  流量分布           │  对账结果                        │
│  A: 55% ████████   │  实时: 0差异                     │
│  B: 45% ██████     │  准实时: 2差异                    │
│                    │  全量(T+1): 上次0差异              │
├────────────────────┴────────────────────────────────┤
│  故障事件时间线                                       │
│  10:00 A机房MySQL延迟>5s → 10:01 自动恢复             │
│  09:30 Canal消费延迟>10s → 09:32 扩消费者后恢复        │
└─────────────────────────────────────────────────────┘
```

### 告警升级规则

| 级别 | 通知方式 | 响应时间 | 示例 |
|------|---------|---------|------|
| P0（紧急） | 电话 + 短信 + 企业微信 | 5 分钟内 | MySQL 复制中断、配置漂移、对账大量差异 |
| P1（重要） | 短信 + 企业微信 | 30 分钟内 | 同步延迟超标、机房 RT 异常、连接数差异 |
| P2（一般） | 企业微信 | 2 小时内 | 非核心指标异常 |

### 混沌工程验收

> 当前10条验收标准都是功能验证，缺少故障注入验证。以下是多活场景的混沌验收项：

**前提条件**：
- Docker 环境已部署双机房全量基础设施（MySQL、Redis、Nacos、ES、Canal、MQ）
- Redis 需开启 `enable-debug-command local` 才能使用 `DEBUG SEGFAULT` 命令
- iptables 操作需要 root 权限
- 每次演练前需确认对账体系正常运行

| 验收项 | 注入方式 | 预期结果 | 验证命令 |
|--------|---------|---------|---------|
| MySQL Master A 宕机 | `docker kill mysql-a-master` | 30秒内 MHA 切换，数据丢失 < 1s | 切换后查询数据完整 |
| Redis Master A 宕机 | `redis-cli -h redis-a DEBUG SEGFAULT` | 15秒内 Sentinel 故障转移 | `INFO Replication` 确认新 Master |
| 机房 A 网络隔离 | `iptables -A OUTPUT -d <B网段> -j DROP` | 流量自动切到 B，无脑裂 | Gateway 日志确认路由切换 |
| Canal 消费延迟 | 制造大批量写入压测 | 延迟恢复后数据最终一致 | 对账验证 |
| Nacos A 集群宕机 | `docker kill nacos-a-{1,2,3}` | 服务从 Nacos B 获取实例 | curl 验证服务调用正常 |
| ES Cluster A 宕机 | `docker kill es-a-{1,2,3}` | 搜索请求路由到 Cluster B | 搜索功能正常 |

---

**相关专题**：
- ⬅️ [45-数据层多活](../45-data-layer-multi-active/README.md)
- 🔗 [42-多活架构概述与选型](../42-multi-active-overview-and-selection/README.md)
- 🔗 [44-网关与负载均衡多活](../44-gateway-and-loadbalancer-multi-active/README.md)

> 📋 详细文档请在开发时基于 [_template.md](../../_template.md) 填写完整内容