# my-xhs 模块梳理交接文档

> 更新时间：2026-07-24
> 当前进度：3/16 模块完成

## 完成模块

### 01-user — 用户模块
- 架构文档：`docs/test-2/01-user/01-user-module.md` ✅
- curl 测试：`docs/test-2/01-user/02-user-test.md` — 2.1~2.3 完成（验证码/注册/登录）
- 已修复：pom.xml 清理 4 个无用依赖

### 02-content — 内容模块
- 架构文档：`docs/test-2/02-content/01-content-module.md` ✅
- curl 测试：`docs/test-2/02-content/02-content-test.md` — 3.1~3.11 全部通过
- 深度文档：12 份（03~13），覆盖 DFA/@Transactional/本地消息表/CacheAside/MQ可靠性/雪花ID/笔记生命周期/编辑删除/文件上传/评论系统/全链路一致性
- 已修复：publishDraft 缓存 Bug（NOTE_DETAIL 未清除→发表后草稿显示 404）

### 03-analytics — 数据分析模块
- 架构文档：`docs/test-2/03-analytics/01-analytics-module.md` ✅
- curl 测试：`docs/test-2/03-analytics/03-analytics-test-record.md` — 4.1~4.19 全部通过
- 深度文档：8 份（03~10），覆盖 Lua两阶段/同步回滚/DuplicateKeyException幂等/Pipeline优化/ZINTER交集/对账修复/ZSet关注关系/三数据结构共存
- 已修复：DELETE unlike/unfavorite 缺 @RateLimit、FollowMapper SQL 不兼容、Unlike Javadoc 错误、LikeEvent createdAt 一致性、publishDraft SQL DISTINCT+ORDER BY Bug、selectMaxIdByLastId SQL Bug
- XXL-Job Admin 部署完成：`http://21.130.247.89:18080/xxl-job-admin`（admin/123456），任务 followCounterRepairJob 已配置（每小时执行）

## 待梳理模块

| 编号 | 模块 | 端口 | 数据库 |
|:--:|------|:--:|------|
| 04 | 计数服务 counter | 19004 | 同 content |
| 05 | 商品服务 product | 19006 | my_xhs_product (13307) |
| 06 | 购物车 cart | 19008 | my_xhs_cart (13307) |
| 07 | 库存服务 inventory | 19009 | my_xhs_inventory (13308) |
| 08 | 优惠券 coupon | 19010 | my_xhs_coupon (13307) |
| 09 | 订单服务 order | 19011 | my_xhs_order (13308, 分库分表) |
| 10 | 支付服务 payment | 19012 | my_xhs_payment (13308) |
| 11 | 通知服务 notification | 19013 | my_xhs_notification (13309) |
| 12 | 即时通讯 im | 19014 | my_xhs_im (13309) |
| 13 | 首页 BFF home | 19015 | 无 (聚合层) |
| 14 | 搜索服务 search | 19016 | Elasticsearch 8.12 |
| 15 | 公共模块 common | — | — |
| 16 | Gateway | 19000 | 无 |

## 09-test-1 目录

```
docs/test-1/  — 历史文档归档（旧 docs/arch + docs/architecture 等）
docs/test-2/  — 新梳理文档（模块分析 + curl测试 + 深度文档）
```

## 环境信息

- 宿主机：21.130.247.89
- MySQL：13306(user)/13307(content/product/cart/coupon)/13308(order/payment/inventory)/13309(notification/im)
- Redis：16379(Sentinel master) / 16380(Cache) / 16381(Business)
- ES：19200，elastic / Xhs@2026#Elastic
- Nacos：18848
- 服务通过 `start-all.sh`（java -jar）启动，非 Docker

## 关键代码修复（本轮未提交的）

1. `my-xhs-content/.../NoteService.java:400` — publishDraft 加 NOTE_DETAIL 缓存清除
2. `my-xhs-analytics/.../LikeController.java:56` — DELETE unlike 加 @RateLimit
3. `my-xhs-analytics/.../FavoriteController.java:55` — DELETE unfavorite 加 @RateLimit
4. `my-xhs-analytics/.../FollowMapper.java:27-34` — selectDistinctUserIds + selectMaxIdByLastId SQL 修复
5. `my-xhs-analytics/.../LikeService.java:121-123` — unlike Javadoc 修复
6. `my-xhs-analytics/.../LikeEvent.java:37` — 加 actionTime 字段
7. `my-xhs-analytics/.../LikeConsumer.java:55` — createdAt 使用 event.actionTime
8. `my-xhs-analytics/.../FollowService.java` — 新增 repairUserRelationships 方法
9. `my-xhs-analytics/.../FollowMapper.java:37-38` — 新增 selectFollowUserIdsByUserId + deleteById
10. `my-xhs-analytics/.../FollowCounterRepairJob.java` — 扩展调用 repairUserRelationships
11. `my-xhs-analytics/.../XxlJobConfig.java` — 新增 XXL-Job Executor Bean 配置
12. `my-xhs-analytics/.../application.yml` — 新增 XXL-Job 连接配置

## 下一步

**04-counter（计数服务）** — 端口 19004，CounterBuffer 攒批刷盘设计，Redis INCR/DECR 实时更新，MQ 事件驱动，定时对账修复。
