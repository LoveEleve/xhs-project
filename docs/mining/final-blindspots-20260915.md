# 终扫盲区（2026-09-15）：基准/IM/Home/Notification/运维/文章

## 1. 性能基准模块（诚实声明）
- `my-xhs-benchmark` 三支 JMH（Order/Search/Feed）**均为空壳骨架**：只 `Math.sqrt/sin/log` 模拟，无真实服务注入、无实测数字 → 不得作为性能证据（性能数据以 wrk/99 号报告为准）

## 2. IM 深机制
- 发消息：单事务写消息+双方会话（共享存储，非真写扩散，预览截 100 字）→ 路由投递 → ACK；失败回 NACK
- seq：Redis INCR `im:seq:{conversationId}`；离线补发按 seq 升序（修 selectBatchIds 乱序）
- conversationId：关系表全局雪花 ID（P0-B 修复 `min*31+max` 碰撞串台）
- 离线：Lua 原子 ZADD+ZCARD+裁剪+EXPIRE（1000 条/7 天）；ACK 用 ZREM O(logN)（替代 LREM O(N)）
- 已读/未读：Hash HINCRBY；READ_NOTIFY 跨实例；TYPING=98 不落离线
- 边界：单用户单连接（新设备踢旧 4001）；**撤回未实现**；路由 TTL 90s

## 3. Home BFF 聚合
- 双线程池隔离：aggregator 20/50/200 CallerRuns（队满调用线程执行不丢）；batchFeign 30/80/500（防嵌套饥饿）
- 超时分层：全局 4s → 第 1 层 3s → 第 2 层动态 `max(500, 4000-elapsed)`；content 硬依赖 503，其余降级空值
- Feed：大V 10 万/页 20（上限 50）；收件箱 ZSET 游标开区间+多取 1 判 hasMore；点赞批量 ≤50 ID 防 URL 超长；每批 500 粉丝 Pipeline（约 100x 往返优化）
- 用户主页：5 路并行 3s；获赞+收藏最多 20 页×50 求和

## 4. Notification
- 聚合窗口实现=**当天剩余秒（最短 60s）**，注释仍写"5 分钟"（口径不一致，已记入待修）
- 幂等两级：msgId 24h + SETNX；失败先删幂等再重试；sender==target 跳过
- 未读：INCR+HINCRBY 原子；SAFE_DECR 防负；对账每 5 分钟（DB 为源，LIMIT 500×10+sleep 50ms 限速）
- SSE：ticket 30s 一次性（getAndDelete）；emitter 30min；心跳 10s Pipeline 续期；跨实例先查路由再 Pub/Sub；**无 Last-Event-ID 回放**（重连拉列表补齐）

## 5. 运维脚本与配置
- `ops-fixes.sh`：kibana 密码重置/从库重建（–source-data=2）/补偿表结构修复/命名空间清理
- `remote-upgrade.sh` 7 步：备份→基线 diff 强校验→restart:always→docker 自启→滚动 up→验证→查从库状态
- 指标类：mysql 死锁 textfile（5min 采集，实测 1→2）、rocketmq（cluster/TPS/consumer lag/DLQ）三步健康、ILM 30 天删除
- 备份：MySQL 每日 2 点全量（源数据点+压缩，7 天）+binlog 30 天；Redis 每 6h BGSAVE；ES 每日快照
- 部署：27 容器全 restart:always+healthcheck；broker SYNC_FLUSH + autoCreateTopicEnable=false；开机自愈脚本（磁盘>50G 扩容+等 7 个依赖+拉起 15 服务）
- 启动：15 服务统一 G1GC/200ms，JVM 256/512/1024 三档 + SkyWalking agent；令牌随机生成落 .secrets（600）
- DEPLOY-NOTES 沉淀 22 条实测坑（SYNC_FLUSH 静默回退、MySQL 无死锁状态变量、中文 SQL utf8mb4…）

## 6. Harness 文章（57 篇编号 + 2 长文）
- 五季结构：核心思想 5（纪律/参数化/自动检测/harnessing/coding-skill）+ 命令拆解 12 + 实战扩展 6 + 新增技能 28（Toolkit/治理/交接等）+ AI 研发体系 6（SDD-TDD/Owner Agent/6 阶段/上下文瓶颈/本地优先/File Wiki/FDE）
- 2 篇拆解长文：跨语言脚手架架构（6 语言/111 框架块/ADR 式拆解）、19 轮需求拷问演进（10 commit/3 主版本/2333 行 apply-harness/51 参数键/每个数字可对账）
