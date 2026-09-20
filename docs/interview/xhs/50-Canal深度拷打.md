# 第50题 | 组件深度拷打：Canal

> 难度：★★★★★｜频率：★★★★☆｜区分度：高
> 关键词：模拟从库、binlog ROW、位点管理、flatMessage、external_gte 抗乱序、补偿与重建

## 问题
MySQL 和 ES 怎么同步？为什么用 Canal？消息丢了/乱序怎么办？全量和增量怎么衔接？

## 面试可讲版（五段式）

**① 原理层**
- **本质**：Canal 伪装成 MySQL Slave——注册一个 `slaveId`、向 master 发起 **dump 协议**请求，接收 **binlog（必须 ROW 格式）**，解析成结构化事件（INSERT/UPDATE/DELETE + 前后镜像）；
- **架构**：canal-server（instance = 一个订阅通道，按 `filter.regex` 过滤库表）+ 消费端；**serverMode** 支持 TCP / Kafka / **RocketMQ**（本项目）——即 Canal 只负责"解析+投递"，下游消费归 MQ；
- **位点管理**：instance 记录 binlog file+offset（本项目由 tsdb（本地文件/DB）持久化），重启从位点续传——**不丢但可能重复**（at-least-once），所以消费端必须幂等；
- **flatMessage**：把事件平铺成 JSON（对下游友好），配合 `database.hash`、`partitionHash` 把同一行的事件投到同一 MQ 分区→**同 id 保序**；
- **设计边界**：DDL 不产生数据事件（表结构变更要单独处理）；事务大/批量写会放大单条消息。

**② 项目用法（3 个 instance → 3 个 Topic）**
| instance | 源表（filter.regex） | Topic | 消费用途 |
|---|---|---|---|
| note_instance | `my_xhs_content.t_note` | NOTE_INDEX_TOPIC（3 分区，hash by id） | 笔记索引同步 |
| product_instance | `my_xhs_product.t_spu, t_sku` | PRODUCT_INDEX_TOPIC（hash by id） | 商品索引同步 |
| inventory_instance | `my_xhs_inventory.t_inventory` | INVENTORY_CACHE_TOPIC | 库存缓存失效/刷新 |
- **版本与参数**：canal-server **1.1.7**、`flatMessage=true`、`canalBatchSize=50`、`database.hash=true`；三个实例 slaveId **1001/1002/1003** 避免冲突；
- **索引一致性三段设计**（28 题展开）：
  1. **抗乱序**：ES 写入带 `external_gte`（DB `updated_at` 毫秒）——旧版本事件被 ES 拒绝，天然幂等；
  2. **防复活**：删除写 **tombstone**（`deleted=true` 文档）而不是物理删除，避免"删除后旧消息把文档写回来"；
  3. **兜底补偿**：`IncrementalIndexSyncJob` 按 `updated_at` 增量扫描回补（Canal 断链/消息丢失的自愈通道），`IndexRebuildJob` 全量重建 + **别名切换**。
- **为什么用 Canal 而不是业务双写**：同步逻辑与业务代码解耦（发帖/改价不需要改同步代码）、不侵入事务（双写要么进事务放大锁、要么事务后写有丢失窗口）、DB 变更成为唯一事实源。

**③ 坑与事故**
1. **binlog 格式**：必须是 ROW（STATEMENT 解析不出前后镜像）；MySQL binlog 保留策略要覆盖 Canal 最长断线时间（本项目 binlog 保留 30 天）；
2. **位点与重复**：at-least-once——重启/位点回退会重复投递，消费端靠 `external_gte` + 幂等收敛（这正是版本控制的用武之地）；
3. **乱序窗口**：同 id 有 hash 保序，但**全量重建与增量并行**时仍可能旧事件覆盖新数据——用 external_gte + 重建期间的增量消息重放策略兜底；
4. **延迟链路**：MySQL → binlog → Canal → RocketMQ → 消费 → ES refresh（1s），端到端秒级；**Canal 挂了不报警就等于静默不同步**——靠增量补偿 Job 定时对账发现（观测缺口）；
5. **slaveId 冲突**：与真实从库或其他 Canal 实例撞 ID 会互相踢下线——固定 1001-1003 并登记管理。

**④ 兜底**
- 增量补偿（扫描 updated_at 回补）+ 全量重建（别名切换）+ 搜索对账任务；
- 消费端幂等（external_gte）+ tombstone 防复活；缓存侧走 INVENTORY_CACHE_TOPIC 删除而非写入。

**⑤ 拷打追问**
1. **"Canal 原理一句话？"** 模拟 Slave 拉 binlog，解析成行级事件投递给下游；核心是 dump 协议 + 位点。
2. **"为什么不用事务消息/双写？"** 双写侵入业务且一致性难（先写谁）；Canal 把"DB 变更"变成事件源，业务零侵入；代价是链路长、需要补偿。
3. **"位点丢了会怎样？"** 从 tsdb 恢复；最坏从无位点重启 → 重新消费（重复但幂等）；所以幂等设计是前提。
4. **"怎么保证 ES 一定和 MySQL 一致？"** 不保证实时强一致——最终一致：Canal 实时 + 增量对账补偿 + 全量重建三件套；external_gte 让乱序无害。
5. **"全量和增量怎么衔接？"** 重建写新索引（别名切换）；重建期间的增量事件也要重放/等重建窗口结束后追增量，否则会漏。
6. **"为什么要 flatMessage？"** 原生 protobuf 事件对下游不友好；flat JSON 直接可读、易与 MQ 集成；但单条消息变大（前后镜像都带）。
7. **"DDL 怎么办？"** Canal 有 DDL 事件但本项目未消费——索引结构变更走应用侧重建任务（映射变更同步在消费端处理）。
8. **"Canal 高可用？"** 生产要 ZK 选主多 server；本项目单 server（边界），靠补偿任务兜底。

**⑥ 话术**
> "ES 同步走 Canal：三个实例分别抓笔记、商品、库存表的 binlog，ROW 模式，flatMessage 投到 RocketMQ 三个 Topic，按 id hash 保序。一致性设计是三段：写入带 external_gte 版本号让乱序无害、删除写 tombstone 防旧消息复活、还有增量补偿 Job 和全量重建别名切换兜底。业务零侵入是选它的核心原因——不用在事务里双写。边界也清楚：Canal 是 at-least-once，消费必须幂等；单 server 没有 HA，Canal 挂了的发现靠对账而不是告警，这是要补的观测缺口。"

## 发散追问地图（横向）
- 原理：binlog 格式（ROW/STATEMENT/MIXED）、dump 协议、位点/tsdb、多 instance。
- 部署：serverMode（TCP/Kafka/RocketMQ）、HA（ZK 选主）、监控（延迟/位点）。
- 一致性：at-least-once、幂等、版本控制、对账、全量重建衔接。
- 替代方案：Debezium（Kafka Connect）、Flink CDC、DataX（离线）、业务双写。
- 扩展：Canal 消费多下游（缓存/索引/数仓）、消息过滤与转换。

## 面试官评分点
**高级开发级**：能讲模拟从库/dump/位点/flatMessage；知道消费要幂等。
**架构师加分**：external_gte + tombstone + 补偿/重建的完整一致性方案；at-least-once 与乱序窗口的边界；Canal 无 HA/无告警的观测缺口；为什么不用双写。
**危险信号**：以为 Canal 精确一次；删除消息把文档写回；全量与增量衔接没方案；binlog 没开 ROW。

## 本项目真实证据
- canal-server 1.1.7（容器）；`canal.properties`（serverMode=rocketMQ、flatMessage、batch 50、destinations）；
- 3 个 instance.properties（过滤表、slaveId、Topic、partitionHash）；消费端 `NoteIndexSyncConsumer`/`ProductIndexSyncConsumer`/`LikeCountSyncConsumer`、`IncrementalIndexSyncJob`、`IndexRebuildJob`；
- external_gte/tombstone 证据见 28 题（`ProductIndexSyncConsumer` 等）。

## 版本与来源
Canal 1.1.7 文档；本项目 Canal 配置与消费端代码。

## 真实性说明
实例/过滤/Topic/slaveId/参数均为部署配置事实；单 server 无 HA、DDL 未消费、Canal 自身无告警为主动披露的边界。

## 本轮补充（2026-09-20 路线复核）
- Canal `ts` 做 **external version + tombstone**（让 ES 拒绝旧版本写）；缓存一致性路线仍是延迟双删（**承认 Canal 更优，列为下一步**——决策台账 C9/D 类）。
