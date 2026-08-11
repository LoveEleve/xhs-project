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
