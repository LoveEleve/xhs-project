# my-xhs 技术选型评审 — 基于实际运行数据的修正版

> 评审日期：2026-06-01 | 基于 21.91.124.110 实际 Docker 运行数据
> 16 容器稳定运行 46h+，总内存 ~8GiB/15.36GiB，全部 host 网络

---

## 修正说明

上版 `PROD-SUITABILITY-REVIEW-RESULT.md` 基于 docker-compose.yml **静态文件分析**。
本版结合**实际运行容器状态**进行修正，修正项标注 ✅/❌。

---

## 一、修正项清单

### 1.1 ShardingSphere "伪分库" — 结论修正

**原判断**：4 个 datasource 指向同一 MySQL → 伪分库，没有水平扩展

**实际状态**（docker-compose.yml 确认）：
```yaml
mysql-order:
    command: --port=13308 --max-connections=500
```
- mysql-order **是一个容器**，内部有 4 个 database（my_xhs_order_0~3）
- sharding-config.yaml 的 ds0~ds3 都指向 `21.91.124.110:13308`

**修正结论**：✅ 确实是"同物理机、多逻辑库"的分库方案。**但这在开发/演示阶段是合理的**——真正的水平扩展需要把 ds1~ds3 迁移到不同主机，这只是配置文件的差异。不应判为"伪分库"，而应判为"开发环境分库拓扑"。

**但连接池计算的结论不变**：
```
mysql-content 承载 6 个服务 × 20 连接 = 120。max_connections=300
→ 2 实例达 80%，3 实例超限
→ 生产需 ShardingSphere-Proxy 收敛连接
```

### 1.2 Redis allkeys-lru — 确认有效

```yaml
command: redis-server --port 16379 --appendonly yes --maxmemory 256mb --maxmemory-policy allkeys-lru
```

**当前运行状态**：Redis 仅用 3.6M 内存（远未到 256MB）

**修正结论**：✅ 风险结论不变。当前数据量小时没问题，但一旦 Feed 收件箱 + 购物车 + 粉丝列表增长到 256MB，`allkeys-lru` 会淘汰非 TTL 的业务数据。**建议改为 `volatile-lru`**。

### 1.3 SkyWalking OAP 内存问题 — 🔴 新发现

**实际运行数据**：
```
my-xhs-skywalking-oap: 2.42G  ← 全系统最大内存消费者
my-xhs-elasticsearch:  1.07G
my-xhs-xxl-job-admin:  1.07G
my-xhs-canal:          1.16G
```

SkyWalking OAP **单独吃掉 2.42G**，占整个系统 15.36G 的 16%。

**根本原因**：docker-compose.yml 中**没有设置 JVM 堆大小**：
```yaml
skywalking-oap:
    environment:
      SW_STORAGE: elasticsearch
      # ← 缺 SW_HEAP_MEMORY 或 JAVA_OPTS 限制！
```

默认 OAP 的 JVM 会占用宿主机约 25% 的内存。**这是未经调优的部署**。

**修正**：加上 `SW_HEAP_MEMORY: 1024` 即可控制在 1G 以内。

### 1.4 XXL-Job Admin 1.07G — 🔴 同样问题

```yaml
xxl-job-admin:
    environment:
      PARAMS: >-
        --server.port=18080
        --xxl.job.accessToken=my-xhs-xxl-job-token-2026
        # ← 没有 -Xms -Xmx 参数！
```

**修正**：加上 `--spring.datasource.url=... -Xms256m -Xmx256m`

### 1.5 Canal 1.16G — ⚠️ 偏高但可接受

Canal 作为 CDC 组件，需要解析 binlog 并写入 RocketMQ，内存需求确实不低。但 1.16G 对于 3 个 instance 来说偏高——可能 Canal Admin 进程也内置在 server 中。

**修正**：docker-compose.yml 中的 canal server 配置没有 JVM 参数限制。加上 `-Xms256m -Xmx512m`。

---

## 二、基于实际运行数据的重新评估

### 2.1 内存占用分析

| 组件 | 实际内存 | 合理值 | 状态 |
|------|:---:|:---:|:---:|
| MySQL×4 | 1.56G (390M×4) | 1.5~2G | ✅ 合理 |
| Redis | 3.6M | <100M | ✅ 极低 |
| RocketMQ (NameSrv+Broker) | 1.18G | 1~1.5G | ✅ 合理 |
| ES | 1.07G | 1~2G | ✅ 合理 |
| Nacos | 675M | 512M~1G | ✅ 合理 |
| **SkyWalking OAP** | **2.42G** | **512M~1G** | 🔴 **严重超标** |
| **XXL-Job Admin** | **1.07G** | **256M~512M** | 🔴 **超标** |
| **Canal** | **1.16G** | **512M~768M** | 🟡 **偏高** |
| Prometheus | 60M | 100~200M | ✅ |
| Grafana | 42M | 100~200M | ✅ |
| SkyWalking UI | 204M | 200~400M | ✅ |

**结论**：SkyWalking OAP + XXL-Job + Canal 三者超额消耗了约 **2.5G** 内存。加 JVM 限制后，系统整体内存可从 8G 降至 **5~6G**——节省 **25~33%**。

### 2.2 之前评审结论的修正

| 原结论 | 基于实际数据的修正 |
|--------|---------|
| "ShardingSphere 伪分库" | 改为"开发环境同机多库分片，生产仅需改数据源地址即可实现真分布" |
| "Canal 1.1.7 已 EOL" | ✅ 保留——但**当前在稳定运行**。EOL 不影响功能，只影响安全补丁和新特性 |
| "SkyWalking Agent 未挂载" | ✅ 保留——OAP 在跑但 Agent 未挂，OAP 的 2.42G 白花了 |
| "XXL-Job 几乎没用分片" | ✅ 保留——Admin 跑了 1.07G 却几乎没调度实际的分片任务 |
| "Redis allkeys-lru 危险" | ✅ 保留——当前只有 3.6M 数据安全，但策略应提前改 |
| "CosId 声明但未集成" | ✅ 保留——自研方案已有双 Buffer，但也缺时钟回拨 |
| "RocketMQ 走 4.x 协议" | ⚠️ 修正——镜像 5.1.4 但 `sh mqbroker -n ... -c broker.conf` 确实走 Remoting。**这是 RocketMQ 5.x 的默认行为**——Proxy 需要显式启动 `sh mqproxy` 子进程。不算错，但没用上新特性 |

### 2.3 防火墙安全评估

```bash
# 用户提供的防火墙策略
策略: 白名单 ACCEPT + 所有其他 DROP
白名单: 127.0.0.1 + 21.214.97.212（开发机）
保护端口: 15 个核心端口
```

**评价**：✅ 这是生产级别的安全策略。白名单 + DROP 最小化暴露——**超越华仔**。

---

## 三、修正后的评分

| 维度 | 上版 | 修正后 | 原因 |
|------|:---:|:---:|------|
| 技术选型方向 | 82 | **85** | ShardingSphere 不是"伪分库"是开发拓扑；整体选型方向正确 |
| 方案实现深度 | 45 | **50** | 承认开发环境拓扑的合理性，但 Canal/CosId/RocketMQ Proxy 等仍需改进 |
| 方案间协调性 | 60 | **65** | Redis Key 不一致、限流重复——但仍需修补 |
| 运维复杂度 | 35 | **40** | 实际运行 46h 稳定，说明基础运维过关。但 JVM 限制缺失导致内存浪费 |
| 部署质量 | — | **55** | 新增维度：防火墙 ✅ / 卷挂载 ✅ / Docker Compose ✅ / 但 JVM 参数缺失 |
| **综合** | **57** | **59** | 小幅上调 |

---

## 四、即刻修复清单（基于实际运行数据）

| # | 修复项 | 容器 | 预期效果 | 优先级 |
|---|--------|------|---------|:---:|
| 1 | SkyWalking OAP 加 `SW_HEAP_MEMORY: 1024` | skywalking-oap | 2.42G → ~1G | 🔴 |
| 2 | XXL-Job Admin 加 `-Xms256m -Xmx256m` | xxl-job-admin | 1.07G → ~300M | 🔴 |
| 3 | Canal 加 `-Xms256m -Xmx512m` | canal | 1.16G → ~500M | 🟡 |
| 4 | Redis `allkeys-lru` → `volatile-lru` | redis | 防业务数据淘汰 | 🟡 |

**修复后预期**：总内存从 ~8G 降至 **~5.5G**，减少 30%。
