# 第49题 | 组件深度拷打：ShardingSphere-JDBC

> 难度：★★★★★｜频率：★★★★★｜区分度：高
> 关键词：解析-路由-改写-归并、INLINE 分片、绑定表、Snowflake、跨分片查询、扩容

## 问题
分库分表怎么做的？分片键为什么选 user_id？跨分片查询怎么办？扩容怎么扩？

## 面试可讲版（五段式）

**① 原理层**
- **核心流程（JDBC 内核）**：SQL 解析 → 分片路由（计算 ds/表）→ SQL 改写（逻辑表→物理表、分页/聚合改写）→ 执行（多数据源并行）→ 结果归并（内存排序/聚合/LIMIT）；
- **分片策略**：standard（单分片键，本项目）、complex（多键）、hint（强制路由）；算法 INLINE 表达式 / 自定义 ClassBased；
- **绑定表**：分片规则同构（同分片键+同算法）的表**绑定**后，JOIN 不会产生笛卡尔积（本项目 5 张表全部绑定）；
- **广播表**：每个库都存一份（小字典表，本项目未用——订单域无字典表）；
- **分布式主键**：Snowflake（worker-id + 时间戳 + 序列），worker-id 必须全局唯一；
- **事务**：ShardingSphere 支持 LOCAL/XA，本项目用 **LOCAL + 本地消息表 + RocketMQ 事务消息**（05 题）——不引入 XA 的协调开销。

**② 项目用法（订单域 4×4，运行态配置）**
- **版本与集成**：ShardingSphere-JDBC **5.5.1**；Spring Boot 3.2.5 的自动配置**不识别** `jdbc:shardingsphere:` 协议 → 代码里用 `YamlShardingSphereDataSourceFactory` 手动创建 DataSource（`ShardingSphereDataSourceConfig`）；
- **拓扑**：4 个 database（ds0~ds3）× 每库 4 张分表 = **16 个物理分片/逻辑表**（开发环境同一 MySQL 实例多 database 仿真，生产改 jdbcUrl 即水平扩展）；
- **分片规则**：`user_id % 4 → ds`；`(user_id / 4) % 4 → t_xxx_n`——**库表各 4 级、两级哈希**让 16 个分片的数据分布均匀；
- **分片表**：`t_order / t_order_item / t_local_message / t_order_snapshot / t_order_event`（绑定表组，同一用户的数据全在**同库同表后缀**，JOIN 无笛卡尔积、单用户事务不跨库）；
- **主键**：Snowflake；worker-id **动态计算**（本机 IP 后两段 `(ip[2]*256+ip[3])%1024`）覆盖 YAML 占位值，避免多实例冲突；
- **连接池**：每个 ds 独立 Hikari（max 10 / min 2 / keepalive 30s / maxLifetime 30min），`max-connections-size-per-query: 2`；
- **非分片键查询**：订单号 → `t_order_no_mapping` 映射表（**独立数据源**，绕过分片路由）+ 映射补录 Job（29 题）；
- **压测隔离**：`ShadowTableInterceptor`（默认关，`myxhs.shadow.enabled=true` + 请求头 `X-Pressure-Test`）把 `t_*` 表名改写为 `t_*_shadow`，压测数据不污染生产表。
- **动态数据源融合（Demo 实测）**：映射表数据源支持 master/slave **运行时切换**（`AbstractRoutingDataSource`，server_id 1↔2），与 ShardingSphere 分片**同应用并存**、分片下单流程不受影响；同时验证 MyBatis/MP/JPA 多 ORM 并存（`shardingsphere-dynamic-datasource-20260918.md`、`multi-orm-coexistence-20260918.md`）。

**③ 坑与事故（真实配置演进）**
1. **协议不识别**：Spring Boot 3.2.5 下 `jdbc:shardingsphere:` 自动配置失效（Driver 类加载问题）→ 手动工厂创建（代码注释写明原因）；
2. **sql-show 生产血泪**：`sql-show: true` 会把每条物理 SQL 打日志（16 分片放大后日志爆炸+性能损耗），**P-D32 已修复为 false**；
3. **worker-id 边界**：同机多实例/容器同 IP 时 IP 推导会**冲突**（Snowflake 同 worker-id 有重复风险）——当前单实例无影响，多实例需引入实例编号或注册中心协调；
4. **跨分片查询是范式问题**：按 user_id 分片后，"按订单号/按商家/按状态"查询天然跨分片（全路由 + 内存归并，16 分片放大）——解法是映射表（订单号）与业务约束（商家维度在别的模型），**没有银弹**；
5. **扩容代价**：固定 4×4 暂无 rehash 能力；扩容要走双写迁移/影子表同步（简历主动写"暂无 rehash"）。

**④ 兜底**
- 映射表 + 映射补录任务（分片外的第二索引）；
- 本地消息表在分片内（同 user_id 同分片）保证下单+预扣的本地事务原子；
- 对账 Job 收口跨系统差异（18 题）。

**⑤ 拷打追问**
1. **"分片键为什么选 user_id？"** 交易查询 90% 是"我的订单"（用户维度），且下单/支付/取消全在单用户上下文；选订单号分片则用户列表全路由；选商家则用户查询全路由——**按最热查询模式选**。
2. **"4×4 怎么定的？"** 单表容量与增长预估（订单量级）+ 单实例并行度经验（16 分片足够当前容量测试的 4,871 RPS 目标）；分片过多会放大协调成本、过少没有扩展余量。
3. **"跨分片分页怎么做？"** LIMIT/OFFSET 改写为各分片 limit 0, offset+limit 再内存归并；深分页代价高——产品上禁止跳页/改游标。
4. **"绑定表原理？"** 同一分片键+同一算法 → 相同 user_id 的 JOIN 各行落在同一物理分片，路由到单分片执行，避免"每库拉全表做笛卡尔积"。
5. **"Snowflake 时钟回拨怎么办？"** ShardingSphere 内置实现有回拨等待/异常策略；应用侧受 NTP 约束；worker-id 冲突才是本项目更现实的风险（见坑 3）。
6. **"为什么不用 ShardingSphere-Proxy/MyCAT？"** JDBC 无额外进程、无网络跳数、与 Spring 事务集成好；Proxy 适合多语言/遗留系统。代价是分片逻辑与应用耦合（升级要动应用依赖）。
7. **"分片后怎么保证事务？"** 避免跨分片：分片键设计让单业务事务落在单库（user_id 分片 + 绑定表）；跨系统一致性用本地消息表+事务消息+对账，而不是 XA。
8. **"分片后怎么做聚合统计？"** 应用侧归并（本项目订单统计走别的模型/离线），ShardingSphere 内存归并只适合小结果集。

**⑥ 话术**
> "订单按 user_id 做了 4 库×4 表，两级哈希 16 分片，五张表绑定成组——同一用户的数据同库同表后缀，JOIN 不跨库、单用户事务不跨库。主键 Snowflake，worker-id 用本机 IP 后两段算，避免多实例冲突；订单号反查用独立映射表绕过分片。踩过两个典型坑：Spring Boot 3.2.5 不识别 shardingsphere 协议得手动建 DataSource；sql-show 开着把 16 分片的物理 SQL 全打日志，生产是事故级的。扩容目前是短板，固定 4×4 没有 rehash，真扩容要走双写迁移。"

## 发散追问地图（横向）
- 内核：SQL 解析/路由/改写/归并细节、Hint 路由、联邦查询。
- 算法：INLINE/自定义/HASH_RANGE、基因法分片。
- 数据迁移：rehash、双写、影子表比对、停机/不停机方案。
- 生态：ShardingSphere-Proxy vs JDBC、分布式事务（XA/Seata）。
- 运维：分片均衡、慢 SQL、跨分片监控。

## 面试官评分点
**高级开发级**：能讲分片流程、INLINE 规则、绑定表、Snowflake、映射表方案。
**架构师加分**：分片键的业务驱动论证；扩容与 rehash 的诚实短板；连接池/查询并行度参数；sql-show 事故与 worker-id 冲突边界。
**危险信号**：分片键拍脑袋；跨分片查询没方案；宣称 XA 解决一切；不知道绑定表。

## 本项目真实证据
- `sharding-config.yaml`（4 ds×4 表、5 逻辑表、绑定表组、INLINE 表达式、Snowflake、Hikari 参数、sql-show=false）；
- `ShardingSphereDataSourceConfig`（手动工厂 + IP 推导 worker-id）；`ShadowTableInterceptor`（压测影子表，默认关）；
- 映射表与补录：`OrderMappingRepairJob`（29 题）；扩容口径写入简历（固定 4×4，无 rehash）。

## 版本与来源
ShardingSphere 5.5.1 官方文档；本项目 `sharding-config.yaml` 与配置类。

## 真实性说明
分片规则/表清单/池参数/worker-id 算法均为仓库配置与代码事实；**"SS × 动态数据源融合/多 ORM 并存"已 Demo 实测**；"无 rehash、单实例 worker-id 边界、影子表与分片表配合未验证"等主动披露。

## 本轮补充（2026-09-20 哈希迁移实测）
- 倾斜根因：Snowflake 增量 `Δms<<22` 恒被 16 整除 + 低流量 Δseq=0 → `mod 16` 恒定（131 单落 2/16 片=75%）。
- 修复：`hashCode&0x7fffffff` 取模 + 迁移 682 行（5 表，dry-run/停服约 2 分钟）；迁移后 **16/16 片全用**、订单↔映射一致、路由抽查命中。
- worker-id 加入端口维度（354 vs 454）防多实例重号；映射表 + `MappingRepairJob`(5min) 消除写失败窗口。
