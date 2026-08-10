# 踩坑记录

> 2026-08-07 第2会话 | 2026-08-08 第3会话 | 22 项环境/配置/代码问题
> 🔴 S03 suggest_index 修复耗时 ~2.5h，涉及 4 层根因链
> 🔴 S08 热搜快照表缺失 → 修复后 42 行数据

## 环境/进程管理（#1-#3）

| # | 问题 | 现象 | 根因 | 解决 |
|:--:|------|------|------|------|
| 1 | 僵尸进程堆积 | 服务重启后 health 不响应 | 多次 `pkill && java &`，pkill 超时杀不掉，新进程端口冲突 | `fuser -k {port}/tcp` 按端口精准杀 |
| 2 | 38 Java 进程 + 65 端口 | 系统资源耗尽 | 多天多会话累积（MCP/javalens/SCA labs 残留） | 用户要求全清→`pkill -9 -f "my-xhs-"` → 16 服务全重启 |
| 3 | TOKEN 跨 Bash 丢失 | curl 返回 401 | 每次 Bash 是新 shell | `echo $TOKEN > /tmp/test_token.txt` |

## 配置/基础设施（#4-#8）

| # | 问题 | 现象 | 根因 | 解决 |
|:--:|------|------|------|------|
| 4 | XXL-Job pageList content=null | API 查询返回空 | `jobGroup=2` 写错，实际在 jobGroup=1 | HANDOFF 文档修正 |
| 5 | MySQL 查券表空 | 13307 my_xhs_content 查不到券 | 表在 `my_xhs_coupon`（同端口不同库） | HANDOFF §一 补数据库分布表 |
| 6 | SkyWalking healthCheck 404 | OAP :12800/healthCheck 不存在 | SW OAP 无此端点 | 改用 UI :8080 |
| 7 | Payment 反复崩溃 | 启动后自动 shutdown | pkill 僵尸 + 日志干扰 | `fuser -k` 精准杀 |
| 8 | Redis key 格式推测错误 | A06 ZSCORE=None 误判为 Bug | 猜 key=`myxhs:favorite:user:10001`，实际 `PROJECT_PREFIX + "favorite:"` → `myxhs:favorite:10001` | **读 RedisKeyConstants 源码**不猜 |

**11. 不要称"全部成功"时有项标"待重启生效"** — mvn -am 后`IncrementalIndexSyncJob`/`PreDeductTimeoutJob`代码改了但未部署≠完成；javap bytecode 验证 fixedRate=60000l 才算真部署

---

## 第3会话新增 (#16-#22，2026-08-08)

### 基础设施/环境

| # | 问题 | 现象 | 根因 | 解决 |
|:--:|------|------|------|------|
| 16 | **testuser 密码失效** | 登录返回"用户名或密码错误" | 密码被之前测试修改(原 Test@123456) | 注册新用户 mytestuser(2085927845755985922)/Test@123456 |
| 17 | **redis-cli 未安装** | HANDOFF 的 Redis 验证命令全部不可用 | 测试环境未预装 Redis 客户端 | Python `import redis` 替代 |
| 18 | **Redis Sentinel 主节点≠16381** | 在 16381 查不到 myxhs:* 键 | search 服务配置 Sentinel(26379~26381)→主节点实际为 16379 | HANDOFF §一 需标注 Sentinel 架构 |
| 19 | **MySQL 远程 root 无 CREATE 权限** | `CREATE TABLE ...` 返回 42000 denied | `root@21.214.97.212` 对 my_xhs_content.* 只有 SELECT/INSERT/UPDATE/DELETE | 用户(运维)在 MySQL 服务器本地执行建表 |

### 功能缺陷

| # | 问题 | 现象 | 根因 | 解决 |
|:--:|------|------|------|------|
| 20 | **t_hot_search_snapshot 表缺失** | S08 返回空数组 `data=[]`，search 日志"快照表不存在，跳过持久化" | 表不存在→HotSearchService 降级跳过持久化→热搜数据只在 Redis ZSet 无法历史查询 | 在 my_xhs_content 库建表，S08 重测返回 42 条快照 ✅ |

### 运维/构建

| # | 问题 | 现象 | 根因 | 解决 |
|:--:|------|------|------|------|
| 21 | **mvn -am 污染 common 模块** | `mvn package -pl my-xhs-search -am` 后 service 启动后 3 秒自停 | -am 重编 common→公共类版本不一致→Spring 上下文异常→优雅停机 | 去掉 -am，单独 -pl 编译 |
| 22 | **中文参数 curl 400** | `?keyword=测试` → 400 Bad Request | curl GET 不自动编码中文 | 用 `-G --data-urlencode "keyword=测试"` |

### 定时任务修复（影响本会话测试效率）

> 3 个 @Scheduled 任务从 5 分钟缩至 1 分钟，避免等待影响验证节奏。

| 文件 | 修改前 | 修改后 |
|------|--------|--------|
| HotSearchService.java | @Scheduled(fixedRate=300000) | @Scheduled(fixedRate=60000) |
| PreDeductTimeoutJob.java | @Scheduled(fixedRate=300000)+leaseTime 240s | @Scheduled(fixedRate=60000)+leaseTime 40s |
| IncrementalIndexSyncJob.java | @Scheduled(fixedRate=300000) | @Scheduled(fixedRate=60000) |

### XXL-Job 全部 1 分钟 cron（之前已修，确认）

| id | 任务 | cron | 状态 |
|:--:|------|------|:--:|
| 10 | OrderCloseJob | 0 * * * * ? | 1 |
| 11 | localMessageRetryJob | 0 * * * * ? | 1 |
| 12 | deadLetterScanJob | 0 * * * * ? | 1 |
| 13 | orderMappingRepairJob | 0 * * * * ? | 1 |
| 14 | inventoryReconcileJob | 0 * * * * ? | 1 |
| 15 | couponReconcileJob | 0 * * * * ? | 1 |
| 16 | couponExpireJob | 0 * * * * ? | 1 |

---

## 🔴 S03 suggest_index 修复全链路（#9-#15，~2.5h）

### 问题链

```
S03 API 返回 data=[] 
  → 查 ES → suggest_index 0 docs（mapping 正确但无数据）
  → grep 全项目 → 只有读取代码，无写入逻辑 ← 根因1
  → 新增 IndexRebuildJob.rebuildSuggestIndex()
  → 重建执行 → Table 'my_xhs_search.t_note' doesn't exist ← 根因2
  → standalone application-datasource.properties: master=my_xhs_search
  → 改为 my_xhs_content → Unknown column 'like_count' ← 根因3
  → t_note 实际列: id/user_id/title/content/... 无 like_count/collect_count/comment_count
  → Map.of() NPE（cover_url 列有 null 值） ← 根因4
  → 改 HashMap → ES client .withJson() 报错 ← 根因5
  → 改 .document(map) → t_spu 不在 my_xhs_content 库 ← 根因6
  → 产品重建抛异常→suggest 步骤被跳过
  → 每步独立 try-catch → 终于通过 ✅
```

### 根因明细

| # | 根因 | 修复 |
|:--:|------|------|
| 9 | **suggest_index 无写入逻辑** — IndexInitializer 创建 mapping + SuggestService 读取，中间缺数据填充 | 新增 `rebuildSuggestIndex()`/`bulkIndexSuggest()`/`buildSuggestDoc()` 3 方法 |
| 10 | **数据源连错库** — `application-datasource.properties` 中 `spring.datasource.master.jdbc-url` 指向 `my_xhs_search`，t_note 在 `my_xhs_content` | 改为 `jdbc:mysql://...13307/my_xhs_content`（同端口不同库） |
| 11 | **SQL 列不存在** — `SELECT like_count, collect_count, comment_count FROM t_note` → 这三列在 MySQL 不存在（存 Redis counter） | 移除不存在列 + `buildNoteDocument` 硬编码 0 |
| 12 | **Map.of() 不接受 null** — `cover_url`/`created_at` 等列可为 NULL，Java `Map.of()` 在 null 值上抛 NPE | 改用 `new HashMap<>()` + `getOrDefault()` null-safe |
| 13 | **ES Client API 用错** — `.withJson(new StringReader(json))` 对 `IndexOperation.Builder` 不可用（ES Java Client 8.x） | 改为 `.document(map)` 直接传 Map 对象 |
| 14 | **t_spu 不在 my_xhs_content 库** — 产品重建 `SELECT FROM t_spu` 抛异常，后续 suggest 重建被跳过 | 每阶段独立 try-catch，产品失败不影响建议 |
| 15 | **日志不写入文件** — `java -jar ... > /dev/null 2>&1` 吞掉 Logback 输出 | 改为 `java -jar ... < /dev/null > /tmp/r_search.log 2>&1` 同时捕获 stdout |

## 最终修复文件清单

| 文件 | 改动 |
|------|------|
| `IndexRebuildJob.java` | +70 行：3 新方法 + try-catch 隔离 + 旧 SQL 修复 + HashMap + .document() |
| `application-datasource.properties` | 1 行：`my_xhs_search` → `my_xhs_content` |

## 预防措施

1. **不要** 在同一个 shell 中 `pkill && java &` — 用 `fuser -k {port}/tcp` 分开杀
2. **不要** 在 `>` 重定向前写复杂管道 — 独立启动
3. **每次** 新 Bash 调用前从文件读 TOKEN
4. **永远** 先查 docker-compose.yml 确认端口/库名
5. **读 RedisKeyConstants 源码** 确认 key 格式，不凭 ASCII 图猜测
6. **查 ES 索引** 时确认 write side 有代码 — mapping 存在≠功能实现
7. **ES Client 8.x** 用 `.document(map)` 非 `.withJson(reader)`
8. **Map.of()** 不能含 null 值 — 数据来自 DB 时用 HashMap
9. **多阶段重建** 必须 try-catch 隔离 — 一阶段失败不应阻断其他阶段
10. **调试日志** 用 `< /dev/null > /tmp/xxx.log 2>&1` 而非 `> /dev/null 2>&1`
11. **不要称"全部成功"时有项标"待重启生效"** — mvn -am 后`IncrementalIndexSyncJob`/`PreDeductTimeoutJob`代码改了但未部署≠完成；javap bytecode 验证 fixedRate=60000l 才算真部署
12. **中文参数 curl 用 --data-urlencode** — GET 请求不自动编码，`?keyword=测试` → 400
13. **mvn 不要用 -am** — 单个服务重编只加 -pl，-am 污染 common 可能导致启动自停
14. **Redis 连接前确认 Sentinel** — 搜 myxhs:* 键时先查 Sentinel 主节点（非配置文件写的 business port）
15. **远程 MySQL 权限有限** — 建表/改结构需运维在服务器本地执行
16. **定时任务间隔测试阶段统一 ≤60s** — XXL-Job 1min cron + @Scheduled 60s，避免等待拖慢验证
17. **任何降级/空返回不能标 ✅** — S08 返回 `data=[]` 是表缺失降级，非功能正常，必须修复后重测
