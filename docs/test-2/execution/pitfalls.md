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

---

## 第4会话新增 (#23-#36，2026-08-10)

> 本会话完成 engineering-docs 7篇 + pre-test-init 脚本 + CaptchaService 修复 + 部署调试

### 部署/配置类 (#23-#27)

| # | 问题 | 现象 | 根因 | 解决 |
|:--:|------|------|------|------|
| 23 | **Redisson Sentinel 默认启用** | 所有服务 `Unable to connect to Redis server: 127.0.0.1/127.0.0.1:6380` | `RedissonConfig.@Value("${spring.data.redis.sentinel.enabled:true}")` 默认 true，Docker Sentinel 将 slave IP 报告为容器内部 127.0.0.1 | 所有服务加 `-Dspring.data.redis.sentinel.enabled=false` 走 standalone 直连 |
| 24 | **Gateway 缺 host** | Sentinel 禁用后 Gateway `Cannot build a RedisURI` | gateway yml 只有 sentinel 段，无 `spring.data.redis.host` | Gateway 加 `-Dspring.data.redis.host=21.130.247.89` |
| 25 | **Analytics admin-token** | `${management.admin-token}` 无法解析 → 启动 crash | 属性名 `management.admin-token` ≠ `ADMIN_TOKEN`(其他服务用)，export 不覆盖 | analytics 加 `-Dmanagement.admin-token=my-xhs-admin-token-2026` |
| 26 | **`bash -c` 信号传播** | Java 进程被 SIGTERM 优雅停机 | `bash -c 'java ... &'` 子 shell 退出→信号传播给进程组 | 每个 Java 命令独立 `nohup ... &` 脱壳 |
| 27 | **`--spring.profiles.active` JVM 非法** | `Unrecognized option: --spring.profiles.active=dev` | `--xx` 被 JVM 解析为 HotSpot 选项 | 改为 `-Dspring.profiles.active=dev` |

### 代码Bug (#28)

| # | 问题 | 现象 | 根因 | 解决 |
|:--:|------|------|------|------|
| 28 | **CaptchaService 序列化器不匹配 P0** | 验证码永远 40104，登录/注册不可用 | `redisOperator.set()` 用 Jackson JSON("ABCD"带引号)，`getAndDelete()` 用 StringSerializer(纯文本)→equalsIgnoreCase 永远 false | CaptchaService 改用 `stringRedisTemplate.opsForValue().set()` |

### 测试脚本类 (#29-#32)

| # | 问题 | 现象 | 根因 | 解决 |
|:--:|------|------|------|------|
| 29 | **表名错误** | TRUNCATE `t_cart` → Table doesn't exist | Cart=`t_cart_item`，Order=分片表 `t_order_0/1/2/3` | SHOW TABLES 确认后修正 |
| 30 | **inventory init 缺必填字段** | 返回 400 校验失败 | DTO 有 `totalStock(@NotNull)`+`bucketCount`，脚本只发 `skuId` | 从 `t_sku.stock` 查库存值传入 |
| 31 | **feed 端点路径错误** | `/api/home/feed/dev/inbox-load` → 404 | 实际路径 `POST /api/home/test/push-inbox`(query params，非 JSON body) | grep Controller 源码确认 |
| 32 | **FeedTestController dev profile** | 端点 404 | `@Profile("dev")` 保护，默认 profile 不注册 | home+notification 以 `-Dspring.profiles.active=dev` 启动 |

### 数据类 (#33-#34)

| # | 问题 | 现象 | 根因 | 解决 |
|:--:|------|------|------|------|
| 33 | **testuser 密码不匹配** | 登录返回 40108(密码错误) | init.sql 的 BCrypt hash ≠ `Test@123456`(不同 salt) | 注册新用户 `testuser_new` |
| 34 | **验证码不在日志中** | grep `code=` 无结果 | 安全设计，日志只有 `key=xxx` 不含 code | 从 /api/user/auth/captcha 响应取 `captchaKey` → Python `r.get("myxhs:user:captcha:{key}")` |

### 工具类 (#35-#36)

| # | 问题 | 现象 | 根因 | 解决 |
|:--:|------|------|------|------|
| 35 | **Python -c 内联语法错误** | `python3 -c "d=0; for p..."` → SyntaxError | `-c` 不支持 `for`/`if` 在 `;` 后 | `python3 << 'EOF' ... EOF` heredoc |
| 36 | **redis.delete() 返回 int** | `len(r.delete(*k))` → TypeError | `delete()` 返回删除数量(int)，非 list | `d += r.delete(*k)` 直接累加 |

### 本会话新增预防措施

18. **Redisson spring.data.redis.sentinel.enabled 默认为 true** — 部署到远程 Sentinel 集群前必须验证 Sentinel 返回的 slave IP 可达性
19. **Gateway yml 缺 host 无法独立 fallback** — 禁用 Sentinel 后同时需 host，两处都可能缺
20. **属性名不匹配不能靠 export 解决** — `${management.admin-token}` ≠ `${ADMIN_TOKEN:}`，需 -D 系统属性
21. **测试脚本必须对照源码验证端点路径/DTO字段/表名/Profile条件** — 逻辑自洽≠能用
22. **CaptchaService 序列化器一致性** — 同一 key 的写读必须使用相同序列化器(RedisTemplate vs StringRedisTemplate)

---

## 第5会话新增 (#37-#38，2026-08-10)

> 本会话: 交接文档数据对账 + 搜索索引链路排查修复

### 代码Bug (#37)

| # | 问题 | 现象 | 根因 | 解决 |
|:--:|------|------|------|------|
| 37 | **IndexRebuildJob 跨库查询未限定 schema P0** | 重建后 `product_index` 恒为 0，商品搜索 total:0 | search 数据源指向 `my_xhs_content`，而 `t_spu` 在 `my_xhs_product` 库；`FROM t_spu` 查 `my_xhs_content.t_spu` 报 `Table doesn't exist`，被 catch 吞掉(不影响笔记/建议索引) | `IndexRebuildJob.java:212` 改 `FROM my_xhs_product.t_spu`，重编译+重启 search 后重建 product_index=18 |

### 部署类 (#38)

| # | 问题 | 现象 | 根因 | 解决 |
|:--:|------|------|------|------|
| 38 | **search 重启丢管理令牌** | 手动重启后 `POST /api/search/index/rebuild` → 403 "仅管理员" | `myxhs.admin.token=${ADMIN_TOKEN:}` 无默认，手动 java 启动未继承 start-all.sh 的 export | 重启时 `ADMIN_TOKEN=my-xhs-admin-token-2026` 环境变量传入 |

---

## 第6会话新增 (#39-#41，2026-08-10)

> 本会话: 前端反馈 3 个 BUG 复核 + 全部修复

### 安全类 (#39)

| # | 问题 | 现象 | 根因 | 解决 |
|:--:|------|------|------|------|
| 39 | **HMAC 写接口未强制（白名单污染）P0安全** | 写操作(加购/下单/支付/发笔记等)不带签名或伪造签名均 200 放行，HMAC 形同虚设 | gateway `application.yml` 的 `hmac-white-list` 被污染——把 /api/cart/**、/api/order/**、/api/payment/**、/api/note/**、/api/comment/**、/api/social/**、/api/inventory/tcc/** 等**全部业务写接口**都加进白名单，导致 isHmacWhiteListed 恒为 true、写操作免签 | 裁剪白名单：仅保留 认证 + 公开读 + 管理端点(JWT+Admin-Call 双层保护)，写接口一律要求 HMAC。已验证：无签名→403、伪造/过期签名→403、合法签名→200 |

### 功能类 (#40-#41)

| # | 问题 | 现象 | 根因 | 解决 |
|:--:|------|------|------|------|
| 40 | **订单 skuImage 为 null** | 订单明细 skuImage 恒 null，前端空图兜底 | `SkuVO` 无 image 字段(且 t_sku 表本无 image 列，图片在 t_spu.images)；OrderTransactionService 写死 `setSkuImage(null)` | SkuVO+SkuInfoDTO 加 image；SkuService.toSkuVO 从 SPU.images 取第一张作主图；OrderTransactionService 填真实图。已验证订单明细 skuImage=spu1_1.jpg |
| 41 | **订单地址 Mock 硬编码** | 订单 addressSnapshot 恒为"测试用户/北京市朝阳区xxx路xxx号"，未用用户真实地址 | OrderTransactionService 写死假地址快照；OrderCreateRequest 有 addressId 但未取真实值 | 新增 UserFeignClient(带X-User-Id)+UserAddressDTO，OrderService.createOrder 用 addressId 调 user 服务取真实地址经 context 传 executeLocalTransaction。已验证订单存真实地址(张三/广东省深圳市南山区科技园路100号，手机号脱敏) |

### 本会话新增预防措施

23. **HMAC 白名单必须只含"公开读/认证/管理端点"** — 写接口加入白名单=签名失效，测试后必须清理调试期的全量豁免
24. **写接口要测"负向"用例(缺/错签名)** — 只测带签名的正常路径发现不了签名未强制
25. **SKU 无图时从 SPU.images 继承** — t_sku 表无 image 列，图片归 SPU 所有，订单快照需透传
26. **订单地址快照必须取用户真实地址** — 有 addressId 就得调 user 服务取数，禁止硬编码 Mock

---

## 第7会话补充 (#42-#43，2026-08-10)

### 部署/启动类 (#42)

| # | 问题 | 现象 | 根因 | 解决 |
|:--:|------|------|------|------|
| 42 | **Gateway 启动失败: Cannot build a RedisURI** | gateway 无法启动，报 `Error creating bean with name 'hmacSignatureFilter' ... Cannot build a RedisURI. One of the following must be provided Host, Socket or Sentinel` | gateway 的过滤器(GatewayAuthFilter/HmacSignatureFilter)通过构造器**硬依赖 stringRedisTemplate**，bean 创建即需 Redis 连接；Redis/sentinel 不可达(旧 sentinel 报告 127.0.0.1:6380)时 redisConnectionFactory 无法构建 RedisURI → 启动失败 | 与 sentinel 修复同源：远程加 replica-announce-ip 后 Redis 可达，gateway 正常启动。**启示：gateway 是 Redis 硬依赖，Redis/sentinel 不可达则网关起不来** |

### 验证/查询类 (#43)

| # | 问题 | 现象 | 根因 | 解决 |
|:--:|------|------|------|------|
| 43 | **订单分片数据"查不到"** | 按 `USE my_xhs_order_0; SELECT ... WHERE id=...` 裸查订单返回空 | ① ShardingSphere 按 userId/orderId 路由到**物理库 my_xhs_order_2** + 表 t_order_0（不是 my_xhs_order_0）；② 下单走**事务消息异步落库**，接口返回 200 时本地事务可能尚未 commit，过早查询查不到 | 定位订单须跨全部物理库/分片表查询；事务消息下单需等本地事务提交后再验证。实测订单在 `my_xhs_order_2.t_order_0`，地址快照=真实地址、skuImage=spu1_1.jpg，均确认无误 |

### 操作注记（bash 工具重启）

- **网关/服务手工重启**：用 `start-all.sh` 最可靠（setsid 脱壳 + 等待健康）。手工 `setsid nohup java ...` 在命令行工具超时/进程组机制下可能被杀或 stdout 落到 socket 导致日志丢失，造成"重启失败"假象——这是**操作摩擦，非产品 bug**。
- **验证分片数据**：先 `SHOW DATABASES LIKE 'my_xhs_order%'` 确认物理库，再对每个物理库的每个分片表查询。
- **`pgrep/pkill -f 'xxx'` 会自匹配 shell**：命令行里含目标字符串时，`-f` 会把执行命令的 shell 也算进去，`kill -9` 会杀掉自己 shell 导致命令挂起超时。用 `pgrep -f '[m]y-xhs-...'`（括号技巧）避免自匹配。

---

## 第8会话补充 (#44，2026-08-10)

### 功能缺口类 (#44)

| # | 问题 | 现象 | 根因 | 解决 |
|:--:|------|------|------|------|
| 44 | **缺"可领券模板列表"公开接口** | 前端"领券中心"无法拿到可领券列表（不知道有哪些 templateId 可领），只能做占位页 | coupon 模块只有：template 创建/状态(管理)、template/{id}(单个详情)、claim、user/list、user/available、discount、use、return——**没有公开的"模板列表"端点**，test-plan(N01-N09)也未规划该端点，属从未实现 | 新增公开 `GET /api/coupon/template/list`（领券中心）：CouponService.listClaimableTemplates() 查询 上架+未删+剩余>0+在有效期内 的模板；gateway JWT 白名单加 `/api/coupon/template/list`（公开）。已验证无 JWT 返回 200 + 15 个模板 |

### 部署/重启类 (#45)

| # | 问题 | 现象 | 根因 | 解决 |
|:--:|------|------|------|------|
| 45 | **mvn clean 删除运行中服务的 jar → 懒加载类失败** | 重启服务后某端点偶发 500，如 `GET /api/user/me` → `java.util.NoSuchElementException at ServiceLoader$2.next() in GatewayAuthFilter.parseToken` | `mvn clean` 删除了运行中 JVM 已加载的 jar 文件；JVM 对未加载的类会**惰性从 jar 文件加载**，文件被删 → 解析 JWT(ServiceLoader 加载服务提供方)失败 | `mvn clean/package` 后**必须重启全部服务**让它们加载新 jar。本会话就是先 clean 后未重启，导致 me 500；重启后恢复 200。**教训：改完代码重打包后一定要重启对应服务** |

---

## 第9会话补充 (#46-#47，2026-08-10 Task2 链1-2)

| # | 问题 | 现象 | 根因 | 解决 |
|:--:|------|------|------|------|
| 46 | **spu-detail 的 skuList.image 为 null（不一致）** | `/api/product/spu/{id}` 的 skuList 里 image=null，而 `/api/product/sku/batch` 有 image | 存在**两个 toSkuVO**：SkuService.toSkuVO 设了 image，SpuService.toSkuVO 未设（spu-detail 用的是后者） | SpuService.toSkuVO 补 image（从 SPU.images 取主图），与 SkuService 一致。已验证 |
| 47 | **product 公开读端点需 JWT（与文档不符）** | test-plan 标 P09/P03/P04 "认证:无"，但无 token 访问返回 401 | gateway JWT 白名单未含 /api/product/**，故非真公开 | 带 token 可访问(200)；是否改网关使其真公开待确认（属配置 vs 文档不一致，非崩溃） |

---

## Task2 链3-5 发现问题 (2026-08-10)

| 链 | 端点 | 现象 | 结论 |
|:--:|------|------|------|
| 链3 | C05-check-all | body发`checked`→40001 | 参数是query(非body), 非bug |
| 链4 | N01-template-create | 传ISO日期"T"→40002 请求体格式错误 | LocalDateTime期望`yyyy-MM-dd HH:mm:ss`(空格); validStart须未来 |
| 链4 | N04-claim | 领券200但user/list即时查=0 | claim异步MQ落库, 需等消费后再查 |
| 链4 | N08-use | 用券30016"不满足使用条件" | 因模板被我测N02时下架; 上架后正常. 用券校验模板状态 |
| 链5 | D09/D10 | pay-success后payment记录status仍"待支付"(order status已变1) | 疑似payment记录状态未随pay-success更新, 待确认(次要) |
| 多链 | order/pay/cancel/refresh/check-all | body发参数→40001"缺少参数" | 多个端点用@RequestParam(query)非body; 测试需注意 |

---

## Task2 链6 发现问题 (2026-08-10)

| 端点 | 现象 | 结论 |
|:--:|------|------|
| LK01-like | bizType传"note"→40002 | bizType是int(1=笔记,2=评论) |
| FA01-favorite | 传bizType/bizId→"笔记ID不能为空" | favorite用noteId(非bizId) |
| CT01/CT02 | counter用bizType/bizId→40001/405 | counter用targetType/targetId/countType; batch-get是POST且body用queries数组 |
| S01-search-note | 中文keyword→JSONDecodeError/400 | 中文参数需URL编码(见pitfalls#12) |

---

## Task2 链7 发现问题 (2026-08-10)

| 端点 | 现象 | 结论 |
|:--:|------|------|
| N08/W06 online-count | 403 | 需X-Admin-Call头 |
| N09-test-send | 404 | 需dev profile(未启用), 符合文档; 因此N05/N06 mark-read缺通知无法实测 |
| WS-websocket | - | 需WebSocket客户端, curl不可测 |

---

## Task2 链7 真实BUG (#48，2026-08-10)

| # | 问题 | 现象 | 根因 | 解决 |
|:--:|------|------|------|------|
| 48 | **通知消费者崩溃，通知无法生成（P0）** | like/comment/follow 事件后 u1 无任何通知；日志 `通知消费失败 ... PushTemplateMapper.selectByType` reconsumeTimes:3 | ① `selectByType` SQL 引用不存在的 `deleted` 列(表无此列)→ERROR 1054；② DB type 存小写(like/comment/...)，而 NotificationType.getName() 返回大写(LIKE/...)，`type=#{type}` 匹配不上 | PushTemplateMapper.selectByType 改为 `WHERE LOWER(type)=LOWER(#{type}) AND status=1`(去掉deleted+大小写不敏感)。已验证: 通知成功生成,N04/N05/N06 全部通过 |

> **经验教训**: "没有通知源"不是测不了的理由——通知源=让 u2 对 u1 做动作(关注/点赞/评论),事件流自动生成。**测试前提必须显式构造**,这也是 test 文档的缺口(已补充)。

---

## Task2 链7 补充 (#49-#50，2026-08-10)

| # | 问题 | 现象 | 根因 | 解决 |
|:--:|------|------|------|------|
| 49 | **WebSocket 握手被 HMAC 拦截(回归)** | ws://.../api/im/ws 连接 → 403 签名校验失败 | #3 修复 HMAC 白名单时把 `/api/im/ws/**` 也移除了,但 WebSocket 握手(浏览器WS)无法带签名头,原设计就靠 ticket 内部两步鉴权 | 把 `/api/im/ws`、`/api/im/ws/**` 加回 HMAC 白名单,重启 gateway。已验证 WS handshake OK |
| 50 | **N09-test-send / dev 端点不可用** | `/api/notification/test/send` 404 | `NotificationTestController` 用 `@Profile("dev")`,默认 profile 不注册该测试端点 | start-all.sh 给 notification/home 加 `-Dspring.profiles.active=dev`(JAVA_OPTS_DEV)。已验证 N09 可用,u2 收到通知 |

> **经验**: dev profile 是 Spring Boot 环境隔离机制(@Profile("dev")=仅 dev 环境注册),用于隔离测试/模拟端点,防生产误用。要启用测试端点需给对应服务加 `-Dspring.profiles.active=dev`。

---

## 第10会话发现 (#51，2026-08-10)

| # | 问题 | 现象 | 根因 | 解决 |
|:--:|------|------|------|------|
| 51 | **product 的 /actuator/prometheus 响应极慢(67s) → Prometheus 抓取超时, product 指标缺失** | Prometheus target `21.214.97.212:19006` state=down, lastError="context deadline exceeded"; 本地 curl 该端点耗时 67s(非冷启动,反复触发均67s) | scrape_timeout=10s, 而 product 的 metrics 序列化耗时 67s; 59KB payload 却如此慢,疑似 product 服务资源/序列化性能问题 | 待查: 提高 scrape_timeout 或定位 product metrics 慢因; 当前 product 不在 Prometheus 监控内(L4 缺口) |

> **L4 正确查询**: Prometheus job 名是 **`my-xhs-services`**(非各服务名), 例:
> `sum(http_server_requests_seconds_count{job='my-xhs-services',instance='21.214.97.212:19001'})` → 已验证返回 user 请求计数 5628。

---

## 第10会话 深度排查 (#52，2026-08-10) — #51 根因确认

| # | 问题 | 根因（已确认） | 修复建议 |
|:--:|------|------|------|
| 52 | **product /actuator/prometheus 67s → 指标缺失（#51 根因）** | `DlqMetrics` 为 23 个 consumer group 各注册 Gauge `rocketmq.dlq.backlog`，取值函数 `getDlqBacklog()` 在**每次 prometheus 抓取时同步执行 RocketMQ 查询**(fetchSubscribeMessageQueues+offset)。product 上某 DLQ topic 查询 hang/超时(~60s),累计 67s。其他服务同 23 查询仅 0.015s,故仅 product 受影响。**设计缺陷:指标采集函数做同步 I/O** | ①正解:DLQ 积压改为定时任务(30s)计算缓存进 AtomicLong,Gauge 只读缓存,采集不做事I/O; ②或禁用/降频该指标; ③临时:调大 scrape_timeout(非根治) |

> **诊断方法**：线程栈显示 `PrometheusScrapeEndpoint.scrape → MicrometerCollector.collect → Gauge$$Lambda → DefaultGauge.value`（慢在 Gauge 取值）；对比各服务 `/actuator/prometheus` 耗时定位到仅 product(67s vs 0.015s)。

---
## #52 修复状态（已完成，2026-08-10）

- **改动**：`DlqMetrics.java` — Gauge 取值改为**只读 `backlogCache`**（缓存值）；定时任务(30s)真正执行 RocketMQ 查询并写入缓存。指标抓取全程无 I/O。
- **验证**：
  - product `/actuator/prometheus`：67s → **0.008s**
  - Prometheus product target：down → **up**
  - `rocketmq_dlq_backlog` 指标恢复采集(322)
- **注意**：common 变更需所有服务重打包后才生效；当前仅 product 已重启验证。其他服务(此前已快)仍用旧 common，但无实际影响；后续任意服务重启会带上该修复。

---
## #53 P0-A 优惠券下单从不核销（资损）— 已修复并验证（2026-08-11）

- **根因**：`OrderService.createOrder` 只调 `getCouponDiscount`（算折扣，不核销），**从不调用 `useCoupon`**（Feign/controller 死代码）→ 下单后 `user_coupon.status` 仍为 0、`used_order_id` 为空 → 同一张券可反复下单每单减额（资损）。
- **修复**：`OrderService.createOrder` 在订单事务提交、orderId 确定后，若 `couponId!=null` 同步调用 `couponFeignClient.useCoupon` 核销并绑定 orderId；核销失败则取消订单 + 释放幂等键 + 返回 `30016`。复用 coupon 侧 `markUsed` 乐观锁(WHERE status=0)+归属校验+X-Internal-Call，天然幂等。
- **验证**（order 已重打包重启）：
  - 下单(券满100减20, SKU199)：券 `status 0→1`，`used_order_id` 绑定，折扣20，实付179 ✅
  - 同券再下单：`30016 优惠券核销失败，订单已取消`，复用订单 `status=4(已取消)`，券仍绑定首单 ✅
- **注意**：编译测试类有预存错误（OrderServiceTest/OrderTransactionServiceTest 引用旧构造签名，与本次改动无关），打包用 `-Dmaven.test.skip=true`。

---
## #54 P0-B IM 会话ID哈希碰撞（串台）— 已修复并验证（2026-08-11）

- **根因**：`ChatService.generateConversationId = min*31 + max` 非单射。实测顺序 ID 1..3000 下 **440 万对**不同用户对算出同一 conversationId（如 (1,34)→65 与 (2,3)→65）。conversationId 同时作消息分片/持久化键与历史查询键 → 不同用户对共享聊天历史（私聊串台）。
- **修复**：改用**会话关系表持有全局唯一 conversationId**——`resolveConversationId` 优先从 `ChatUserRelation(userId,peerId)` 取已分配ID（含反向查，防并发首消息分叉），无则分配雪花 ID（`IdWorker.getId()`）；`getMessageHistory` 同样从关系表解析，无会话返回空页。
- **验证**（im 已重打包重启）：WS 发消息给碰撞对 (1→34) 与 (2→3) → DB 中 `conversation_id` 分别为 `2087150297253212161` 与 `2087150297974632450`（不同）；每对正反向复用同一ID。旧算法两者均为 65。

---
## #55 P0-C Feed 收件箱参数颠倒（推流恒空）— 已修复并验证（2026-08-11）

- **根因**：`FeedService.getFollowFeed` 用 `reverseRangeByScoreWithScores(inboxKey, minScore, 0, ...)` 读收件箱。
  Spring 签名 `(key, min, max,...)`，收件箱 score 是正数毫秒时间戳，传 `max=0` → 区间 [minScore,0] 恒空 → 普通用户推模式 Feed 恒空（只靠大V发件箱）。
- **修复**：改为 `reverseRangeByScoreWithScores(inboxKey, 0, minScore, 0, size)`（score∈[0,minScore]，minScore 为 lastScore 开区间上界，倒序取 size 条）。
- **验证**（home 已重打包重启）：向用户10002收件箱写入真实已发布笔记(最高分) → `GET /api/home/feed` 返回该笔记为唯一一条（修复前收件箱恒空，该笔记不出现）。

---
## #56 P1-2 补偿消费者忽略 action → 已支付订单库存/券泄漏 — 已修复并验证（2026-08-11）

- **根因**：`OrderCompensationConsumer` 对 RELEASE_STOCK/RETURN_COUPON 一律调 `closeTimeoutOrder`，其只处理 status==0；已付款/已退款/已取消订单被跳过 → 库存/券永久泄漏。
- **修复**：按 action 分发——`RELEASE_STOCK→compensateReleaseStock`、`RETURN_COUPON→compensateReturnCoupon`、默认 `closeTimeoutOrder`。新增 public 补偿方法，加载订单取 orderNo 后释放，**不依赖订单状态**（幂等：库存按 pseudoOrderId、券按 markUsed WHERE status=1 AND orderId）。
- **验证**（order 已重打包重启）：创建订单(sku4×2)预扣 → 订单状态置为5(已退款) → 向 ORDER_COMPENSATION_TOPIC 发 RELEASE_STOCK 补偿 → 消费者执行 `compensateReleaseStock 释放库存成功` → sku4 total 198→200、预扣记录清除。旧实现会因 status!=0 跳过。

---
## #57 P1-1 已取消订单可支付 / 竞态无退款（钱货两空）— 已修复并验证（2026-08-11）

- **根因**：`PaymentService.pay` 不回查订单状态，已取消/已支付订单仍可创建支付单；`onPaymentSuccess` 在订单状态已非待付款（乐观锁失败）时仅 return false，无退款 → 用户付了钱但订单已取消 → 钱货两空。
- **修复**：
  1. **支付前回查**：`pay()` 经 `orderFeignClient.getOrderStatus` 校验订单状态==0(待付款)，非待付款/无法确认均拒绝（30009/30008，资金安全优先）。新增 order 内部端点 `/api/order/status` + service 方法。
  2. **竞态自动退款**：order `notifyPaySuccess` 在订单非待付款时返回业务失败(30009)；payment `handlePaySuccessInternal` 收到该业务失败（区分瞬态503）→ 自动调 `refund()` 原路退回。
- **修复期发现**：payment 的 `InternalCallFeignConfig` 条件拦截器未覆盖 `/status` 路径 → order 返回403；已补 `|| path.contains("status")`。
- **验证**（order+payment 重打包重启）：
  - 待付款订单 → 支付成功创建支付单 ✅；已取消订单 → `30009 订单当前状态不允许支付` 拒绝 ✅
  - SQL 构造"订单已取消但支付回调成功" → 自动退款触发：`t_refund status=1(成功) 299.00 reason=订单已取消自动退款` ✅

---
## #58 P1-4 ES 版本域混用 → 补偿后增量更新被永久拒绝 — 已修复并验证（2026-08-11）

- **根因**：`IncrementalIndexSyncJob` 用 `System.currentTimeMillis()`(~1.7e12) 作 ExternalGte 版本；Canal 消费端用 `es`(小整数)。补偿一旦写入巨值版本 → 后续 Canal 增量(es 小)被 ES `version conflict` 永久拒绝，索引冻结在补偿快照。
- **修复**（search 重打包重启）：
  1. `NoteIndexSyncConsumer` Canal 版本改用 `ts`(毫秒)——与补偿统一版本域。
  2. `IncrementalIndexSyncJob` 版本改为 DB 行 `updated_at`(毫秒)，删除 `version()`=now。
  3. **顺带修复补偿必失败的既有缺陷**：`buildNoteDocument` 用 `Map.of` 遇 NULL 列(NPE)；bulk 用 `.withJson()`(错) 改 `.document(JsonData.of(map))`。
- **验证**：触发补偿 → ES `_version=1783827196000` == 笔记 `updated_at` ✅；删除毒版本文档后补偿成功写入正确版本。
- **注意**：已存在的高版本毒文档需依赖每日 4 点全量重建(IndexRebuildJob)恢复，新代码不再产生新的毒版本。

---
## #59 P1-5 商品补偿漏跨库前缀 → 商品补偿必失败 — 已修复并验证（2026-08-11）

- **根因**：`IncrementalIndexSyncJob.queryProductsByIds` 裸查 `FROM t_spu`，而 search 数据源默认 schema 是 `my_xhs_content`（t_note 所在库）→ 实际解析为 `my_xhs_content.t_spu` → `Table doesn't exist`，商品增量补偿必失败。
- **修复**：改 `FROM my_xhs_product.t_spu`（显式跨库前缀）。
- **验证**（search 重打包重启）：SPU1 入失败集合 → 补偿执行 `商品=1` 成功，`product_index/_doc/1` 写入（name=2026夏季新款连衣裙，version=spu updated_at 毫秒）。

---
## #60 P1-3 端口信任模型（X-User-Id 伪造越权）— 已修复并验证（2026-08-11）

- **根因**：服务端口(19001+)直连可达时，客户端可任意伪造 X-User-Id 头实现水平越权；服务端逐端点手工校验 X-Internal-Call 易漏配。
- **修复**：新增公共 Servlet 过滤器 `GatewayAuthTrustFilter`（gateway 为 WebFlux 自动排除）：
  1. X-Internal-Call 匹配 → 信任（内部 Feign）；
  2. Authorization Bearer 有效 access JWT → **以 JWT subject 覆盖 X-User-Id**（伪造头无效）；
  3. 其余 → **剥离 X-User-Id**（fail-closed，MissingRequestHeaderException 拒绝）。
  所有 14 个服务 yml 增加 `jwt.secret`（与 gateway/user 一致）。
- **验证**（全部服务重打包重启）：
  - 直连 + 伪造 X-User-Id=999999 无 JWT → 剥离告警 + 拒绝 ✅
  - 直连 + 真实 JWT(user10001) + 伪造 X-User-Id=1 → 返回 10001（无法越权）✅
  - 网关登录→/me → 正常 ✅；带 X-Internal-Call 内部下单 → 正常 ✅
- **注意**：
  - 部署必须保证各服务 `jwt.secret` 配置（已在 yml 固化）；未配置时 JWT 分支退化为仅剥离伪造头。
  - 被剥离请求返回 500（MissingRequestHeaderException 未映射 400）——安全目标已达成，错误码可后续优化。
  - 修复过程发现：Maven `package` 因 target 缓存陈旧导致部分服务 fat jar 内嵌旧 common，需 `rm -rf target` 重建（user/gateway/im 均受影响）。

---
## #61 O1 MDC userId 恒空 — 已修复并验证（2026-08-11）

- **根因**：`TraceIdConfig` 只 `MDC.put("traceId")`，从未写 userId（logback 已声明 userId 字段）→ Logstash 采集的 userId 恒空，日志无法按用户关联。
- **修复**：`TraceIdConfig.TraceContextInterceptor` 写入 `MDC.put("userId", ctx.getUserId())`（有值才写，afterCompletion 清理）；`MqTraceHelper.restoreTraceContext` 同样为 MQ 消费日志填充 userId。
- **验证**（全服务重打包重启）：登录后调 /me → `/logs/my-xhs-user.json` 中 `userId=2086729019870457858`（此前恒空）。

---
## #62 O2 异步线程池丢 traceId — 已修复并验证（2026-08-11）

- **根因**：product `SPU_ASYNC_EXECUTOR`/inventory `inventoryAsyncExecutor` 为裸 ThreadPoolExecutor（无 MDC 传播）；order 多处 `CompletableFuture.runAsync` 走 ForkJoinPool.commonPool；cart 对账单线程池裸用 → 异步任务（缓存刷新/延迟双删/补偿/联动释放）日志丢失 traceId，全链路断链。
- **修复**：新增 common `MdcAwareExecutorService`（每次 execute 捕获/恢复 MDC，参照 home 已验证实现），应用于：
  - product `SPU_ASYNC_EXECUTOR`、inventory `inventoryAsyncExecutor`
  - order 新增 `ORDER_ASYNC_EXECUTOR`，替换 5 处 `CompletableFuture.runAsync`（cancel/退款/关单联动、confirmInventoryDeduct）
  - cart `RECONCILE_EXECUTOR`（对账单线程池）
- **验证**：
  - 独立 Java 测试：异步线程正确继承 traceId/userId，线程复用也拿到新上下文 ✅
  - 端到端：带 traceId 下单→取消 → 异步释放库存运行于 `order-async` 线程且日志携带同一 traceId `o2e2e3-1786460315` ✅

---
## #63 P2-1 product batchGetSkuDetails N+1 — 已修复并验证（2026-08-11）

- **根因**：`SkuService.toSkuVO` → `resolveSpuImage` 对每个 SKU 单独 `spuMapper.selectById` 取 SPU 首图；`batchGetSkuDetails`/`listSkusBySpuId` 批量 SKU 时 N+1 次 SPU 查询（购物车/下单热路径放大）。
- **修复**：新增 `buildSpuImageMap`（distinct spuIds 一次 `selectBatchIds`），批量路径预取 SPU 首图 Map 后 `toSkuVO(sku, image)`；单查 `getSkuDetail` 保留原逻辑。
- **验证**（product 重打包重启）：`GET /api/product/sku/batch?skuIds=1,2,4,6`（跨 SPU 1/2/3）→ 各 SKU 返回正确首图（spu1_1/spu2_1/spu3_1.jpg）✅；SPU 查询由 N 次降为 1 次。

---
## #64 P2-2 product 下架商品详情仍可见 — 已修复并验证（2026-08-11）

- **根因**：`SpuService.loadSpuDetailFromDb` 只过滤 SKU 状态、不过滤 SPU 状态 → 下架/删除商品详情仍对外返回（与 listSpus 仅上架不一致）。布隆过滤器只增不减（RBloomFilter 不支持 remove）。
- **修复**：`loadSpuDetailFromDb` 增加 SPU 状态过滤（非 ON_SHELF → null → 404）；布隆旧条目经 updateSpuStatus 的 afterCommit evictSpuCache 后仅多一次缓存查询，命中空值缓存返回 null，不返回错误数据。
- **验证**（product 重打包重启）：SPU2 下架 + 清缓存 → 详情 `30001 商品不存在` + 日志"SPU 非上架状态"✅；恢复上架 → 200 ✅。

---
## #65 P2-9 登录锁定可被滥用为账号 DoS — 已修复并验证（2026-08-11）

- **根因**：`UserService.incrementLoginFail` 仅按用户名计数，5 次错密码即锁账号 15 分钟，无 IP 维度 → 攻击者可对任意账号恶意错密码将其锁死（账号 DoS）。
- **修复**：
  1. 新增 **IP 维度**：单 IP 失败 20 次 → 锁该 IP（`USER_LOGIN_LOCK_IP`），单源攻击先锁攻击者 IP 而非账号。
  2. **账号锁定条件收紧**：仅当失败次数 ≥5 **且来源 IP ≥2 个**（分布式暴力破解）才锁账号（`USER_LOGIN_FAIL_IPS` 记录来源 IP 集合）。
  3. 登录端点透传 `X-Forwarded-For`（gateway 已覆盖为真实连接 IP）。
- **验证**（common+user 重打包重启）：
  - 同 IP 5 次错密码 → 正确密码仍登录成功（账号未锁）✅
  - 同 IP 20 次 → IP 锁（40203），换 IP 正常（200）✅
  - IP-A 3 次 + IP-B 3 次 → 账号锁（40106 账号已锁定）✅
- **注意**：直连服务端口时 XFF 可伪造（P0-7 已由 gateway 覆盖防伪造；服务端口依赖 P1-3 信任模型）。

---
## #66 P2-13 NOTE_LIST_USER 死缓存键 — 已修复并验证（2026-08-11）

- **根因**：content `NoteService` 5 处（publish/saveDraft/update/delete/publishDraft）对 `NOTE_LIST_USER` 做 delayDoubleDelete，但 `getUserNotes/getMyNotes` 读路径**从不回填**该缓存 → 缓存键只删不填（死缓存，纯 DEL 开销无收益）。
- **修复**：移除 5 处无效失效调用 + 3 个随之无用的 finalUserId；`getUserNotes/getMyNotes` 保持直查 DB（避免引入分页缓存复杂度）。
- **验证**（content 重打包重启）：发布笔记 ✅、用户笔记列表 ✅、无报错 ✅。

---
## #67 P2-14 Notification 聚合窗口注释与实现不符 — 已修复并验证（2026-08-11）

- **根因**：`NotificationAggregator` 类注释/内联注释称"5 分钟窗口"，但实现 `getAggregateWindow()` 为"当天剩余秒数"（对齐 `idx_user_type_target(user_id,type,target_id,notify_date)` 按天聚合索引）——注释陈旧误导。
- **修复**：修正注释为"当天（自然日）窗口"（行为不变，实现本就是按天聚合，符合索引设计）。
- **勘误**：原 review 记的"模板 `{title}`/`{content}` 占位符语义错乱"**不成立**——经核实 `NotificationEventDTO.targetName` 由 CommentService 设为笔记标题（`notification.put("targetName", noteTitle)`），`{title}`→targetName、`{content}`→event.content 均正确，非 bug。
- **验证**（notification 重打包重启）：通知列表正常（200/5条）、无报错 ✅。

---
## #68 P-D30 部署包双 compose 漂移：deploy-cloud 仍是旧版（2026-08-12）

- **现象**：`config/deploy-cloud/docker-compose.yml` 与 `config/docker-compose.yml`（运行版基线）内容不一致——旧版从库挂 `init-all.sql`（→ GTID 冲突 1236 复制必失败）、仅 13 healthcheck、depends_on 无 healthy 条件。remote-upgrade.sh 默认 `COMPOSE_DIR` 直接引用同目录 compose → 用户试验部署会拿到旧版。
- **根因**：两轮改造（restart/healthcheck 增强）只改了 config/docker-compose.yml，deploy-cloud 目录未同步，DEPLOY-README 却指向 config/——"上传即用"一致性被破坏且无人校验。
- **修复**：config/docker-compose.yml 覆盖 deploy-cloud（diff 校验为零）；remote-upgrade.sh 增加第 2 步基线 diff 强校验（不一致即退出，`BASELINE_SKIP=1` 可绕过）。
- **教训**：任何"部署包"改动必须同步全部副本 + 脚本校验，单靠 README 指引不可靠。

## #69 P-B4 INTERNAL_TOKEN 公开默认值穿透端口信任模型（2026-08-12）

- **现象**：GatewayAuthTrustFilter 对 `X-Internal-Call == myxhs.internal.token` 即信任任意 X-User-Id（越权通道）；而所有服务 application.yml fallback 与 start-all.sh 均为公开已知值 `my-xhs-internal-token-2026` → 叠加 P-D28（微服务机端口裸露），攻击者直连 19001+ 可水平越权。
- **根因**：P1-3 信任模型只做了"机制"，凭据默认值公开 = 机制空转。ADMIN_TOKEN 因 yml 默认空而 fail-closed，INTERNAL_TOKEN 却留了默认值。
- **修复**：12 服务 yml `${INTERNAL_TOKEN:}`（fail-closed）+ FeignInternalCallInterceptor 空 token 不带头；start-all.sh 首次启动生成随机 token 持久化 `.secrets/tokens.env`（chmod 600）。
- **教训**：内部认证 token 一律 fail-closed（默认空），随机化注入；yaml 里的 fallback 默认值即漏洞。

## #70 部署包补包：FIX-PLAN 修复项须逐项核对是否已入 compose（2026-08-12）

- **现象**：FIX-PLAN 第一批"零代码"修复（P-D3 noeviction/P-D16 kibana key/P-D26 relay-log/TZ/P-D4 规则名/P-D6 remote_write 等）此前**全部未同步进部署包**，只有 restart/healthcheck/建表并入——用户上传即带病部署。
- **根因**：修复方案写在 docs（评审结论），未做"方案→部署包"落位核对；"上传即用"没有验收标准。
- **修复**：本轮逐项补入 config/docker-compose.yml + prometheus.yml + alert_rules（P-D3/D15/D16/D26/D8/T4/TZ/D13/D6/T3/D4），运维动作转脚本（apply-ilm/mysql-backup/init-xxljob/ops-fixes），DEPLOY-README 增"部署后脚本清单"。
- **教训**：评审出的配置修复，要么进部署包（文件级），要么进运维脚本（动作级），要么明确标注"第二批待联动"——不留"已评审未落位"的悬空项。

---
## #71 P-D36 search 模块数据源实为 content 库，my_xhs_search 是废弃库（2026-08-12）

- **现象**：远程机清单称"t_hot_search_snapshot 等 5 表未入初始脚本"——核对 init-all.sql 全在（前序已补录）；但发现该表在 init-all.sql **定义两处**（content 库段 315 行 VARCHAR(100) + search 库段 361 行 VARCHAR(128)），生产两库均有表。
- **根因**：search 模块 `application-datasource.properties` 的 master/slave jdbc-url 均为 **my_xhs_content** 库（非 my_xhs_search）——HotSearchService 实际写 content 库快照表（生产 1002 行、8/11 持续写入）；search 库 2 表（t_hot_search/t_hot_search_snapshot）无代码引用、无 canal 监听（canal 仅 inventory/note/product 3 instance）、8/7 后停写 → **死库**。
- **教训**：① 表"存在"不等于"在用"——判定归属必须查代码数据源 + 生产写入时间 + canal 监听三方；② init-all.sql 里"运行时补录"的表定义存在重复合并痕迹（同表两处定义、结构不一致），后续补录 DDL 需先确认所属库与结构唯一。
- **处置（已完成 2026-08-12）**：生产 DROP my_xhs_search（备份 /data/tmp/opencode/my-xhs-search-backup-20260812.sql）+ init-all.sql 删 search 段（两文件为硬链接一次生效）；t_inventory_compensation 生产结构仍旧版（P-D22 待 ALTER，DDL 同步教训仍成立）。

---
## #72 复盘：日志链路误判——只看 filebeat 挂载不看 logback appender（2026-08-12）

- **现象**：曾推断"微服务与中间件分机则 filebeat 管道恒空"（P-D35），后经实证推翻。
- **根因**：只检查了 filebeat 容器挂载（/logs:/logs:ro），未核对微服务 logback-spring.xml 的完整 appender 列表——微服务 root 实际有 5 个 appender，其中 **LOGSTASH（LogstashTcpSocketAppender → 21.130.247.89:15044）** 才是主链路（TCP 直推 logstash），filebeat 是冗余通道。
- **实证三连**：① logback-spring.xml 读 appender 全集；② ss 查微服务进程与 15044 的 ESTABLISHED 长连接（15 服务全连）；③ ES myxhs-logs-* 最新 @timestamp 实时（13:10）。
- **教训**：判断任何数据链路（日志/指标/trace）必须验证**生产端输出配置 + 运行期连接 + 存储端最新数据**三点，缺一即可能误判；filebeat/agent 存在≠主链路。

---
## #73 部署包 8 处 bug（对方部署实测发现，2026-08-12）

- 部署方实测修复 8 处部署包缺陷，需同步回本地包：① canal depends_on.rocketmq-broker 空映射（语法错）；② prom/redis-exporter 镜像不存在（应为 oliver006/redis_exporter）；③ redis-exporter 默认端口 9121 非 9151；④ alertmanager 默认 9093 非 19093（Prometheus alerting 目标随之）；⑤ prometheus/VM healthcheck 用 curl 但镜像无 curl；⑥ es-exporter v1.7.0 已移除 --es.cluster_settings；⑦ mysqld-exporter v0.15.1 走 .my.cnf + MYSQLD_EXPORTER_PASSWORD（DATA_SOURCE_NAME 无效）；⑧ OAP telemetry 绑 127.0.0.1 → Prometheus 抓 21.130.247.89:1234 失败（应抓 127.0.0.1:1234）。
- **已回传合并（2026-08-12）**：对方修复包无遗漏（8 处全到位 + 额外修 xxl-job/prometheus healthcheck curl→wget/TCP、SW_TELEMETRY_HOST 127.0.0.1→0.0.0.0、redis-exporter 显式 listen 9151、alertmanager 显式 listen 19093、mysqld-exporter 增 my.cnf+MYSQLD_EXPORTER_PASSWORD、deploy-cloud 移除冗余 compose 副本）；已合并本地 config/ 并重打最终 zip（my-xhs-deploy-package.zip，26 容器）。
- **教训**：① compose 语法/镜像名/默认端口必须本地校验（yaml 解析+镜像 tag 查证），不能只"写对样子"；② 镜像内工具可用性（curl/wget）影响 healthcheck——优先用镜像自带二进制或进程探测（或显式 listen-address 固定端口）；③ exporter 版本差异大（flag/env 变化），应锁定实测过的版本。

## #74 微服务重启中的进程陷阱（2026-08-12）

- pgrep -f "java.*my-xhs-" 会自匹配 bash 命令行 → 杀错/超时；应 `ps -eo args | grep "my-xhs-.*SNAPSHOT.jar"` 精确匹配。
- 环境遗留 27 个 java 进程（含重复实例 + 非本项目 dubbo 进程）——按 jar 路径白名单杀。
- pids/ 旧 pid 文件与真实进程不符会导致 start-all.sh "已在运行"误判——杀净后重跑。

---
## #75 垃圾数据清理 + P-D22 闭环（2026-08-12）

- **清理内容**（用户授权）：
  1. 4 笔僵尸支付单（pay_type=1 卡死、订单已不存在）→ status=2（支付失败）
  2. 8 条 ORDER_CREATED 本地消息（7/29-8/4 从未投递）→ DELETE（3 分片）
  3. t_inventory_compensation ALTER（P-D22）：备份表 t_inventory_compensation_bak_20260812 → 加 fail_reason/retry_count、删 action、加索引 ✅ 结构已与代码一致
  4. my_xhs_deploy_test 空库 → DROP
  5. xxl-job 5 个低频任务（cartReconcile/feedCleanup/recommend×3）→ trigger_status=1 启用（19 启用/2 停用=演示任务+重复项）
- **保留**：seata_lab（2 表 seata_account/undo_log——非本项目，疑其他项目库，不删）；旧 t_local_message 已不存在（P-D25 自动销号）。
- **P-D22 验证**：ALTER 后补偿任务连续 2 个周期无 ERROR（此前每 30s 一次 Unknown column 'retry_count'）；inventory reconcile 正常（28 SKU 对账）。
- **xxl-job 补建任务注意**：init-xxljob.sql 建的任务默认 trigger_status=0（新建即停用），且 couponExpireJob 曾落入 sample 组（INSERT 子查询时序问题）——部署后需核对任务组归属与启用状态。

---
## #76 P2 批量修复（2026-08-12 第十一轮）

- 修 8 项（P2-4/6/11/15/12/7/8/3），全部编译通过，待重启验证。
- 踩坑 3 个：① CartService 方法追加到类括号外（字符串拼接位置）→ 编译 "reached end of file"；② Spring Data Redis 3.1+ `hPut` 已改名 `hSet`（API 演进）；③ fallback 工厂批量替换时重复定义方法（先加后清）。教训：**大批量代码编辑后先 mvn package 验证再继续，String 拼接改代码易错——用精确锚点替换**。
- P2-12 注意：伪订单 ID 算法变更只影响新订单；"预扣旧算法/确认新算法"的进行中订单（30min 窗口）需人工核对。
- P2-3 批量接口：content 侧复用单条 getNoteDetail（缓存/防穿透语义一致），HTTP 从 N 次→1 次；home 侧整体降级为空（fallback）。

---
## #77 生产卫生 4 项收尾（2026-08-12）

- **P-D11**：broker.conf `SYNC_FLUSH` + `autoCreateTopicEnable=false`（防断电丢消息/typos 静默建 topic）——已入部署包；**试验机运行中 broker 需改配置重启才生效**（云主机全新部署自动生效）。
- **A7**：filebeat 僵尸容器已从 compose 删除（26→25 容器，日志主链路为 TCP 15044 直推，filebeat 无数据源）。
- **A9**：broker-slave.conf 死文件已删（无容器使用，避免"有主从"误导）。
- **P-D28**：15 服务 yml exposure 移除 `loggers`（P-D28 收尾）——重启后实测 order /actuator/loggers=404、gateway 不可写（此前 204 可远程改级别）。
- 重新打包 15 服务 + 重启 + 重新出最终 zip（my-xhs-deploy-package.zip，25 容器）。

---
## #78 测试脚本硬编码陈旧——跑测试前必须前置核对（2026-08-12）

- **现象**：full-chain-test-v3.sh 直接跑失败（Redis 连接拒绝）。核对发现 5 个测试脚本全部带陈旧硬编码：
  - v1/v2：`21.91.124.110:16379`（旧 IP + 8/11 前旧端口，双错）
  - smoke：`16379`（端口错）
  - v3：`REDIS_PORT=16379`（端口错，本次踩坑点）
  - pre-test-init：`X-Admin-Call: my-xhs-admin-token-2026`（P-B4 后旧 token 失效）
- **根因**：测试脚本不在"配置变更全链路核对"范围内——P-D23 的端口教训、P-B4 的令牌变更都只同步了运行配置/代码，没同步测试脚本。
- **修复**：全部脚本 IP→21.130.247.89、端口→6379、admin token→tokens.env 动态注入（`${ADMIN_TOKEN}`）。
- **教训（固化）**：**任何配置/凭据变更（端口/IP/令牌/密码）后，跑测试前必须先 grep 测试脚本的旧值**；测试脚本应纳入配置变更核对清单。**本次按用户指示只修复不执行，测试运行待用户确认。**

---
## #79 G1 深度 REVIEW 修复 7 bug + 2 系统性缺陷（2026-08-12）

- **修复**：T-005 禁用账号 refresh 续期（40107）；T-007 logout 缺 access 兜底；T-008 refresh 改 body；T-012 改密凭证失效；T-009/010/011 HMAC 签名升级（method|path|query|ts|nonce|bodyHash + BodyCacheFilter）；T-013 关注校验 target 存在（user 内部端点 + Feign）；T-016 拉黑拦截（block Set）。
- **T-017 系统性**：FeignInternalCallInterceptor 在 14/15 服务不生效——FeignClientFactoryBean 对 request-interceptors 配置类 **new 实例化（@Value 不注入）**，且 **全局 @Component RequestInterceptor 不被 Feign 收集**（javap 实证）→ P-B4 后内部调用 X-Internal-Call 全链路缺失（此前默认 token 掩盖）。修复：字段默认值读 env + 全部服务 yml 补配置 + 显式 configuration。
- **T-018**：block 跨服务读写序列化不一致（RedisOperator 引号 vs stringRedisTemplate）→ 统一。
- **user 服务此前缺 myxhs.internal.token 段**（P-B4 漏改）——内部端点/拦截器 token 恒空。
- **教训**：① 安全修复后必须验证"调用链两端"（头是否真的带上）；② Feign 拦截器配置必须显式（properties/configuration），@Component 不自动生效；③ RedisOperator 序列化带引号，跨服务直读 Redis 需统一存取方式。

---
## #80 common 变更后必须 rm -rf target 重建（2026-08-12）

- **现象**：common 修改（getString 剥引号、GlobalExceptionHandler 新 handler）install 成功、m2 jar 确认更新，但**各服务打包后的 BOOT-INF/lib 里仍是旧 common**——T-021 修了两次才生效。
- **根因**：spring-boot repackage 复用 target 里旧 BOOT-INF/lib（依赖解析缓存）；`mvn package` 不做全量重建。
- **修复**：rm -rf 全部模块 target → 重打包 → 重启（执行规范早有此条，未遵守）。
- **教训**：**修改 common 后，所有依赖服务必须 rm -rf target 重建**，不能只 mvn package；验证方式 = 解压 jar 的 BOOT-INF/lib 检查 class（javap）。

---
## #81 SkyWalking 采样率单位陷阱：SW_TRACE_SAMPLE_RATE 是万分比（2026-08-12）

- **现象**：16:02 后全部服务 SW segment 归零——误以为上报断链（TCP 11800 通、agent 正常、OAP 健康）。
- **根因**：P-T4 部署包配 `SW_TRACE_SAMPLE_RATE: 10`——**SkyWalking 采样率单位是万分比（10000=100%）**，10 = **0.1%**（不是 10%）→ OAP 重启后几乎不采样 → 测试量下 segment≈0。
- **修复**：改为 1000（10%）；运行态 OAP 需改 env 重启生效（部署包已改）。
- **教训**：① 可观测性配置改动后必须验证"存储端数据持续增长"；② 采样率/百分比类配置确认单位（万分比 vs 百分比）。

---
## #82 Java Properties 行内注释陷阱：RocketMQ 配置静默失效（2026-08-12）

- **现象**：对方运行态 `getBrokerConfig` 确认 `flushDiskType = SYNC_FLUSH # 注释` **静默回退 ASYNC_FLUSH**——行内 `#` 被 Properties 解析为值的一部分，枚举解析失败回退默认。
- **根因**：Java Properties 只认**行首 `#`/`!`** 为注释；行内 `#` 属于值。broker.conf 用了行内注释（我方原文件 + 对方修复版同样中招）。
- **修复**：全部注释拆独立行；`grep -E "^[a-zA-Z].*#"` 校验零行内注释；zip 重打。
- **教训**：RocketMQ/Java 系 properties 配置文件**禁止行内注释**；配置变更后运行态确认（getBrokerConfig 而非只看文件）。

---
## #83 方法论教训：静态 review"通过" ≠ 可部署（2026-08-13）

- **现象**：多轮静态 review 均称"没问题"，对方试验机实测暴露 20+ 运行态问题（镜像不存在/healthcheck 工具缺失/配置解析吞值/采样率单位/看板 uid 不替换等）。
- **根因**：无真实环境时把"静态自洽"当"可运行"结论；验证深度与结论强度不匹配。
- **沉淀**：docs/test-3/REVIEW-METHODOLOGY.md——三层验证法（L0 静态/L1 框架语义/L2 运行态）+ 部署包可运行性清单（镜像/解析/框架/数据四类）+ 结论措辞规范（禁止"没问题"）。
- **教训**：① review 必须先声明能力边界；② L1（官方文档/registry/源码）可避免约 60% 实测坑；③ 配置类必须查解析语义（Properties 注释、单位、reload 行为）。

---

## 第 11 会话新增（#84-#99，2026-08-13 G2/监控/部署）

### 指标/监控类
| # | 问题 | 根因 | 解决 |
|:--:|------|------|------|
| 84 | **业务指标恒 0（P1）**：Prometheus 只有预注册无标签序列，带标签业务序列被吞 | 预注册无标签 counter 与业务带标签同名 → Prometheus simpleclient 同名 label 集合冲突 → 带标签系列不输出（actuator 有数据、prometheus 无） | 预注册改为**带业务标签空值**（标签集合对齐）→ 验证 mq_consume_total{topic,consumerGroup,result} 出数 |
| 85 | **计数 key 首次创建 TTL=-1** | Lua 里 EXPIRE 在 INCRBY **之前** → key 不存在时 EXPIRE 无效 | EXPIRE 必须放在 INCRBY/写入**之后** |
| 95 | 看板指标名错：tomcat_threads_busy（实际 busy_threads）、max_threads（实际 config_max_threads）、jvm_buffer_pool_used_bytes（实际 jvm_buffer_memory_used_bytes）| Micrometer 命名差异 | javap/运行态 grep 实证指标名再写面板 |
| 98 | 动态桶无固定 le 边界（le="0.5" 恒空）| publishPercentileHistogram 指数桶 | 一律 histogram_quantile 查询 |
| 93 | micrometer 1.12.5 无 ProcessMetrics/JvmDeadlockMetrics | 版本无此 binder（Boot 3.5 也无自动配置）| enable 配置无效；自定义 MeterBinder（死锁已补）|
| 92 | Boot metrics.enable map 键含点不生效 | 点分隔被嵌套解析（enable["jvm.threads.deadlocked"]）| 方括号包裹键名（但无 binder 时仍无效——先确认版本有无该指标）|
| 99 | 看板审查提取"指标名"误报（rate/sum/by/id 被当指标）| 正则提取第一个 token 是函数名 | 排除函数名/操作符后提取 |

### 部署/构建类
| # | 问题 | 根因 | 解决 |
|:--:|------|------|------|
| 86 | **mvn install -q 吞编译错误** → 后续打包用旧 common | -q 下 BUILD FAILURE 不显示 | install 不加 -q 看 BUILD SUCCESS |
| 87 | **外层 unzip -l 看不到 BOOT-INF/lib 嵌套 jar 内部类** | 嵌套 jar 条目不展开 | 解压 BOOT-INF/lib/my-xhs-common-*.jar 再查（#80 补充）|
| 88 | **pgrep/pkill -f 自匹配杀自己 shell** | -f 匹配完整命令行（含自身）| 用 `[b]in/bash`/`[s]tart` 括号技巧 |
| 89 | **curl 无 --max-time 挂起** | 端口监听但无响应 → curl 永久等待 | 一律 `--max-time 2` |
| 90 | **start-all.sh 变量引号外 -D 静默失效** | `VAR="..." -Dxxx` 语法错误（bash 当命令执行）→ 变量空/噪音；python line[:-1] 修复会吞引号 | 引号必须闭合；修改后 bash -n + 全行引号配对校验 |
| 91 | **start-all.sh 末尾 wait 永不返回** | gateway 前台调用后的多余 wait 无 job 可等但阻塞 | 删除末尾多余 wait |

### 环境/操作类
| # | 问题 | 根因 | 解决 |
|:--:|------|------|------|
| 94 | **gateway 不依赖 common**（独立实现）| 模块 pom 无 my-xhs-common | 指标无 application 标签 → 模块内 MeterRegistryCustomizer；无 myxhs_http 指标 → http.server.requests bucket + 看板正则指标 `{__name__=~"myxhs_http...|http_server_requests..."}` |
| 96 | 限流窗口跨用例共享（业务失败请求也计数）| @RateLimit 在 AOP 层先执行 | 测试前 DEL 限流 key 隔离；压力类请求限速（200ms+）|
| 97 | ps 进程计数含 bash 自身（"双实例"伪影）| ps -eo args 显示 bash -c 命令行 | 用 pgrep -f "[m]y-xhs-.*jar" + Nacos instance 数双重确认 |

### 看板/面板审查方法（防误报）
- 指标存在性：Prometheus `/api/v1/query?query={指标名}` 实测（非只 grep 文件）
- application label 存在性：面板带 `application=~"$application"` 时须确认指标有该标签（gateway 曾全缺——#94）
- 全量表达式真实执行：替换变量后 Prometheus 查询（0 语法错 + 空面板定性：无流量 vs 指标缺失）
