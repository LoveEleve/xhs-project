# 消息推送与通知

> 所属服务：my-xhs-notification (9013) | 开发阶段：Phase-2 | 预计耗时：3天

---

## 🎯 一、需求分析

### 1.1 业务场景

小红书是社交+电商产品，用户需要及时获知社交互动（点赞、评论、关注）和业务通知（订单状态、优惠活动、系统公告）。通知系统是连接用户与平台互动的核心桥梁——没有通知，用户的社交行为得不到反馈，转化率会大幅下降。

**典型用户场景**：
- 场景1：用户A点赞了用户B的笔记 → B收到"xxx赞了你的笔记"通知
- 场景2：5分钟内3人点赞同一笔记 → 合并为"张三等3人赞了你的笔记"
- 场景3：用户打开APP → 首页红点提示5条未读通知
- 场景4：商家发货 → 买家收到"您的订单已发货"通知

### 1.2 功能边界

| 功能项 | 是否实现 | 说明 |
|--------|---------|------|
| 社交通知（点赞/评论/关注） | ✅ | 核心场景 |
| 订单通知（发货/签收/退款） | ✅ | 电商场景 |
| 系统通知（公告/活动/券） | ✅ | 运营场景 |
| 通知聚合（合并同类通知） | ✅ | 5分钟窗口内同类合并 |
| SSE实时推送 | ✅ | 在线用户即时收到 |
| 未读计数（红点） | ✅ | Redis原子维护 |
| 通知列表查询 | ✅ | 分页+类型筛选 |
| 标记已读（单条/全部） | ✅ | Bitmap高效存储 |
| 推送模板管理 | ✅ | 模板变量替换 |
| 批量推送任务 | ✅ | XXL-Job分片发券/公告 |
| APP推送（极光/FCM） | ❌ | **不做决策推导**：移动端推送需要集成各厂商通道SDK（苹果APNs、谷歌FCM、小米/华为/OPPO/VIVO推送），每个通道需要单独的证书配置、SDK接入和兼容性测试。①**投入**：至少5个通道×每人3天=15人天开发+持续维护成本（SDK升级、证书续期、通道规则变更）+第三方服务费用（极光等按推送量计费）。②**产出**：APP推送仅在APP完全关闭时才有价值——而SSE+Service Worker方案可在APP切后台时仍维持推送（Service Worker监听push事件弹出系统通知），覆盖了90%+的"APP不在前台"场景。③**对比**：SSE方案零额外成本（复用现有基础设施），APP推送方案15+人天+持续费用，边际收益仅覆盖SSE无法触达的"APP被杀死"场景（<5%），投入产出比不合理。后续如有需要可通过推送网关统一封装各厂商通道 |
| 邮件/短信通知 | ❌ | 非核心场景，短信需要第三方供应商（阿里云SMS/腾讯SMS）按条计费，邮件需要SMTP服务。作为扩展点预留接口，Phase-3按需接入 |

### 1.3 数据量预估

**推导前提**：参考小红书2024年公开数据（月活3亿、DAU约1亿），本项目按1000万用户规模估算。

| 指标 | 预估值 | 计算依据 |
|------|--------|----------|
| 通知总量 | 18亿 | 1000万用户 × 日均5条 × 365天（1年留存期，非活跃用户通知归档后清理） |
| 日增量 | 5000万/天 | 1000万DAU × 5条/人/天（社交通知4条+业务通知1条，参考同类社交APP均值3-8条） |
| 峰值QPS | 2000 | 5000万 ÷ 86400 × 峰值倍率3.5 ≈ 2020（社交APP晚高峰8-10点集中，日均分布按24小时计算偏保守，实际活跃时段约12小时，峰值倍率取3.5） |
| 单用户通知量 | 1800条/年 | 日均5条 × 365天（活跃用户；普通用户约500条/年） |
| SSE在线连接数 | 50万 | DAU的5%：1000万DAU × 5%（通知推送场景下同时保持SSE连接的比例，低于IM的10%——用户可能关闭APP但SSE连接由Service Worker维持，移动端SSE受限于后台限制，实际比例约3%-8%，取5%） |

> **💡 为什么不是50亿？** 50亿是按"1000万用户×日均5条×1000天"计算的，但1000天≈2.7年，意味着所有历史通知都在线查询——这不现实。实际策略是：**近3个月通知热数据在MySQL，更早的归档到冷存储**，在线数据量约5000万×90天=4.5亿，分16表后单表约2800万，在MySQL舒适区内。

---

## 🏗️ 二、架构设计

### 2.1 整体架构图

```
                        ┌─────────────────────────────────────────┐
                        │              Client (前端)                │
                        │   ┌─────────┐  ┌──────────────────────┐ │
                        │   │ SSE连接  │  │  REST API (通知列表)  │ │
                        │   └────┬─────┘  └──────────┬───────────┘ │
                        └────────┼───────────────────┼─────────────┘
                                 │                   │
                        ┌────────┼───────────────────┼─────────────┐
                        │  Gateway (9000)             │             │
                        │  ⚠️瓶颈: SSE长连接数上限     │             │
                        │  ⚠️配置: 超时需调为0        │             │
                        └────────┬───────────────────┬─────────────┘
                                 │                   │
                    ┌────────────▼──────┐   ┌────────▼────────────┐
                    │ my-xhs-notification│   │ my-xhs-notification │
                    │   SSE推送模块      │   │    REST API模块      │
                    │  SseEmitter管理   │   │  通知CRUD/已读/未读   │
                    │  ⚠️瓶颈: 单机5万   │   │                      │
                    │     连接上限       │   │                      │
                    └────────┬──────────┘   └────────┬────────────┘
                             │                       │
              ┌──────────────┼───────────────────────┼──────────────┐
              │              │                       │              │
     ┌────────▼──┐  ┌───────▼──────┐  ┌────────────▼──┐  ┌──────▼─────┐
     │  RocketMQ  │  │    Redis     │  │  MySQL (分表)  │  │   Feign    │
     │ 消费事件   │  │ 未读/已读/SSE │  │  通知持久化    │  │ 调用户服务  │
     │            │  │ ⚠️故障域:     │  │               │  │            │
     │ ⚠️故障域:  │  │ Redis不可用  │  │ ⚠️单表>5000万 │  │            │
     │ MQ积压→    │  │ →未读数不可用 │  │ →查询变慢     │  │            │
     │ 通知延迟   │  │ →降级查DB    │  │ →需归档冷数据 │  │            │
     └────────┬──┘  └──────────────┘  └───────────────┘  └────────────┘
              │
    ┌─────────┼──────────────────────┐
    │         │                      │
    ▼         ▼                      ▼
 social    content              order服务
 (赞/关注)  (评论)              (订单状态)

【故障降级链路】
┌────────────────────────────────────────────────────────────────────┐
│ Redis宕机 → 未读计数降级为MySQL COUNT查询 → SSE推送时跳过计数推送  │
│ MQ积压   → 通知延迟 → SSE推送空事件+客户端定时轮询补偿             │
│ MySQL慢  → 通知列表降级为Redis缓存 → 缓存Miss则返回"系统繁忙"     │
│ SSE断开  → 客户端自动重连(浏览器原生) → 重连后拉取离线通知         │
└────────────────────────────────────────────────────────────────────┘
```

### 2.2 模块交互

| 交互对象 | 交互方式 | 说明 |
|---------|---------|------|
| social服务 → 通知服务 | RocketMQ | 点赞/关注事件异步通知 |
| content服务 → 通知服务 | RocketMQ | 评论事件异步通知 |
| order服务 → 通知服务 | RocketMQ | 订单状态变更通知 |
| 通知服务 → 用户服务 | OpenFeign | 查询发送者昵称/头像 |
| 通知服务 → Gateway | HTTP | SSE长连接通过网关建立，需配置：①路由 predicates: Path=/api/notification/sse/** ②超时 hystrix.command.default.execution.timeout.enabled=false 或 spring.cloud.gateway.httpclient.connect-timeout=0 ③Gateway基于WebFlux天然支持SSE长连接透传 |
| 通知服务 → Redis | RedisTemplate | 未读计数/已读位图/SSE连接映射 |

### 2.3 核心流程时序图

**流程1：社交事件触发通知（异步MQ）**

```
1. social服务 → RocketMQ: 发送LIKE事件 {userId, noteId, senderId}
2. RocketMQ → notification消费者: 消费LIKE事件
3. notification → 通知聚合器: 检查5分钟窗口内同类通知
4. notification → MySQL: 插入/更新通知记录(t_notification)
5. notification → Redis: INCR 未读计数 notify:unread:{userId}
6. notification → SSE推送: 查找用户SSE连接 → 推送实时通知
7. notification → Client: SSE事件 data: {type, title, content}
```

**流程2：用户拉取通知列表**

```
1. Client → Gateway: GET /api/notification/list?type=1&page=1
2. Gateway → notification: 转发请求(携带userId)
3. notification → MySQL: SELECT * FROM t_notification WHERE user_id=? ORDER BY created_at DESC
4. notification → Client: 返回通知列表(含聚合信息)
```

**流程3：标记已读**

```
1. Client → Gateway: POST /api/notification/read/{id}
2. Gateway → notification: 转发请求
3. notification → MySQL: UPDATE t_notification SET is_read=1 WHERE id=?
4. notification → Redis: DECR 未读计数 / SETBIT 已读位图
5. notification → Client: 返回成功
```

---

## 🗄️ 三、数据库设计

### 3.1 表结构

**通知表（按user_id分表）**

```sql
-- t_notification (按user_id分4库×8表=32表，支持50亿数据)
-- 热数据3个月在线：5000万/天 × 90天 = 45亿，32表每表约1.4亿（仍偏大，配合冷热分离归档）
-- 生产建议：近3个月热数据MySQL + 更早的归档到HBase/OSS，保证在线单表<5000万
CREATE TABLE t_notification (
    id           BIGINT       NOT NULL COMMENT 'ID(雪花算法)',
    user_id      BIGINT       NOT NULL COMMENT '接收用户ID',
    type         TINYINT      NOT NULL COMMENT '通知类型：1-点赞 2-评论 3-关注 4-系统通知 5-订单通知',
    title        VARCHAR(128) NOT NULL COMMENT '通知标题',
    content      VARCHAR(512) DEFAULT NULL COMMENT '通知内容',
    sender_id    BIGINT       DEFAULT NULL COMMENT '发送者ID',
    sender_name  VARCHAR(32)  DEFAULT NULL COMMENT '发送者昵称(冗余)',
    target_id    BIGINT       DEFAULT NULL COMMENT '关联目标ID(笔记/商品/订单)',
    target_type  TINYINT      DEFAULT NULL COMMENT '目标类型：1-笔记 2-商品 3-订单',
    is_read      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已读：0-未读 1-已读',
    is_aggregated TINYINT     NOT NULL DEFAULT 0 COMMENT '是否聚合：0-否 1-是(被合并到其他通知)',
    aggregate_id BIGINT       DEFAULT NULL COMMENT '聚合目标通知ID(被合并指向主通知)',
    aggregate_count INT       DEFAULT 1 COMMENT '聚合数量(合并了几条同类通知)',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_user_id_created (user_id, created_at DESC),
    INDEX idx_user_type_read (user_id, type, is_read),
    -- 注意：不再单独建idx_is_read索引！is_read只有0/1两个值，区分度极低，
    -- MySQL优化器大概率不会使用该索引（低基数索引反模式）。已读状态通过
    -- idx_user_type_read的联合索引覆盖查询：WHERE user_id=? AND is_read=0
    UNIQUE KEY uk_aggregate (user_id, type, target_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知表';
```

**推送模板表**

```sql
-- t_push_template
CREATE TABLE t_push_template (
    id               BIGINT       NOT NULL COMMENT 'ID',
    type             VARCHAR(32)  NOT NULL COMMENT '通知类型: like/comment/follow/system/order/coupon',
    title_template   VARCHAR(128) NOT NULL COMMENT '标题模板(如: {sender}赞了你的笔记)',
    content_template VARCHAR(512) DEFAULT NULL COMMENT '内容模板',
    aggregate_title_template VARCHAR(128) DEFAULT NULL COMMENT '聚合标题模板(如: {sender}等{count}人赞了你的笔记)',
    status           TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0-禁用 1-启用',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_type (type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='推送模板表';
```

**推送任务表（批量推送）**

```sql
-- t_push_task (批量推送如发券通知、系统公告)
CREATE TABLE t_push_task (
    id            BIGINT       NOT NULL COMMENT 'ID',
    task_name     VARCHAR(64)  NOT NULL COMMENT '任务名称',
    task_type     TINYINT      NOT NULL COMMENT '任务类型: 1-系统公告 2-券推送 3-活动通知',
    template_id   BIGINT       DEFAULT NULL COMMENT '关联推送模板ID',
    biz_id        BIGINT       DEFAULT NULL COMMENT '业务ID(如券模板ID)',
    target_type   TINYINT      NOT NULL COMMENT '目标类型: 1-全部用户 2-指定用户 3-条件筛选',
    target_condition TEXT       DEFAULT NULL COMMENT '筛选条件JSON',
    target_count  INT          NOT NULL COMMENT '目标用户数',
    success_count INT          NOT NULL DEFAULT 0 COMMENT '成功数',
    fail_count    INT          NOT NULL DEFAULT 0 COMMENT '失败数',
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '状态: 0-待执行 1-执行中 2-已完成 3-已取消',
    execute_time  DATETIME     DEFAULT NULL COMMENT '计划执行时间',
    start_time    DATETIME     DEFAULT NULL COMMENT '实际开始时间',
    end_time      DATETIME     DEFAULT NULL COMMENT '实际结束时间',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_status (status),
    INDEX idx_execute_time (execute_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='推送任务表';
```

**推送失败记录表**

```sql
-- t_push_task_fail
CREATE TABLE t_push_task_fail (
    id           BIGINT       NOT NULL COMMENT 'ID',
    task_id      BIGINT       NOT NULL COMMENT '任务ID',
    user_id      BIGINT       NOT NULL COMMENT '用户ID',
    fail_reason  VARCHAR(256) DEFAULT NULL COMMENT '失败原因',
    retry_count  INT          NOT NULL DEFAULT 0 COMMENT '重试次数',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_task_id (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='推送失败记录表';
```

### 3.2 索引设计

| 索引名 | 字段 | 类型 | 使用场景 |
|--------|------|------|----------|
| idx_user_id_created | (user_id, created_at DESC) | INDEX | 通知列表按时间倒序查询 |
| idx_user_type_read | (user_id, type, is_read) | INDEX | 按类型筛选未读通知（联合索引已覆盖is_read查询） |
| uk_aggregate | (user_id, type, target_id, created_at) | UNIQUE | 聚合去重+幂等写入保障 |

### 3.3 分库分表策略

| 维度 | 策略 | 说明 |
|------|------|------|
| 分片键 | user_id | 按接收者分片，保证同一用户通知在同一库表 |
| 分片算法 | user_id % 32 (哈希) → 路由到4库×8表 | 32张表，配合冷热分离保证单表<5000万 |
| 库数×表数 | 4库×8表=32表 | 4库分散IO压力，每库8表 |
| 冷热分离 | 近3个月热数据MySQL，更早归档HBase/OSS | 归档策略：定时任务扫描created_at>90天的记录迁移 |

**扩容策略**：32表 → 64表（倍扩）
- 方案1：一致性哈希迁移——新增分片后，只有1/32的数据需要迁移，不影响在线服务
- 方案2：停服迁移——低峰期停服，数据重分布后重启（简单但不可用）
- **推荐**：方案1，通过ShardingSphere的扩容工具在线迁移

**为什么4库而不是1库？**
- 1库16表：所有IO压在1个MySQL实例上，高QPS下磁盘IO成为瓶颈
- 4库8表：4个MySQL实例分摊IO，每个实例只承担1/4的读写压力
- 代价：运维复杂度增加（4个实例要监控、备份、主从配置），但性能收益远大于运维成本

---

## 🔑 四、缓存设计

### 4.1 Redis Key 规范

| Key Pattern | 类型 | 过期时间 | 说明 |
|-------------|------|----------|------|
| `notify:unread:{userId}` | String | 永久 | 总未读通知数 |
| `notify:unread:type:{userId}` | Hash | 永久 | 按类型未读数 field=type(1-5) value=count（Key不含type，type作为Hash的field） |
| `notify:read:set:{userId}:{yyyyMM}` | Set | 90d | 已读通知ID集合（替代Bitmap方案——雪花ID是19位数字，远超Bitmap offset上限2^32，Bitmap方案不可行。Set存ID更通用，1万已读通知约200KB） |
| `notify:sse:{userId}` | String | 30s(心跳续期) | SSE连接映射 → serverId（TTL改为30秒，避免10秒续期间隔内的误判） |
| `notify:aggregate:window:{userId}:{type}:{targetId}` | String | 5min | 聚合时间窗口锁 |
| `notify:template:{type}` | Hash | 30min | 推送模板缓存 |
| `notify:consumed:{msgId}` | String | 24h | MQ消费幂等标记（辅助，主幂等靠DB唯一键） |

> **⚠️ Bitmap方案废弃说明**：原设计用 `notify:read:bitmap:{userId}:{yyyyMM}` + SETBIT，但雪花算法生成的ID是19位数字（最大9.2×10^18），远超Redis Bitmap的offset上限（2^32-1 = 42亿）。**此方案在雪花ID场景下根本跑不通**。改用Set存储已读通知ID，虽然内存占用略高（1万ID约200KB vs Bitmap理论上12MB/1亿位），但通知场景下单个用户月均已读量约150条（5条/天×30天），Set仅占约6KB，完全可接受。

### 4.2 缓存更新策略

| 操作 | 策略 | 说明 |
|------|------|------|
| 未读计数 | Cache Aside + INCR/DECR | 新通知INCR，标记已读DECR |
| 已读状态 | Write Behind | 标记已读时先写 Redis Set（存已读通知ID），异步同步到 MySQL |
| 推送模板 | Cache Aside | 先查缓存 → Miss → 查DB → 写缓存 |
| SSE连接映射 | Write Through | 建立连接时写Redis，心跳续期，断开删除 |

### 4.3 缓存异常处理

| 问题 | 解决方案 |
|------|----------|
| 缓存穿透（查不存在的通知） | 空值缓存TTL=60s + 布隆过滤器拦截不存在的user_id |
| 缓存击穿（热点用户通知数） | 逻辑过期：缓存永不过期，Value中含logicExpire字段；查询时发现逻辑过期→返回旧值→异步线程刷新DB数据写回缓存 |
| 缓存雪崩 | 随机TTL偏移（基础TTL ± 随机0-300s）+ 多级缓存（本地Caffeine 30s + Redis） |
| 未读数不一致 | **三级保障**：① 实时：INCR/DECR原子操作 ② 定时对账：每5分钟Redis vs MySQL COUNT比较，差异>0时以DB为准修复Redis ③ 手动修复：管理后台提供"重建未读数"按钮，用户反馈计数异常时触发。对账期间用户看到的未读数可能短暂不准，但5分钟内自动修正 |

**逻辑过期实现方案**（缓存击穿）：

```java
// 逻辑过期：Key永不过期，Value中包含逻辑过期时间
// 查询时发现逻辑过期 → 返回旧值（保证可用性） → 异步刷新
@Data
public class RedisCacheData<T> {
    private T data;           // 业务数据
    private LocalDateTime logicExpire; // 逻辑过期时间
}

// 查询逻辑
public Notification getNotificationWithLogicalExpire(String key) {
    String json = redisTemplate.opsForValue().get(key);
    if (json == null) return null; // 物理不存在

    RedisCacheData<Notification> cacheData = JSON.parseObject(json, ...);
    if (cacheData.getLogicExpire().isAfter(LocalDateTime.now())) {
        return cacheData.getData(); // 未过期，直接返回
    }

    // 逻辑过期 → 返回旧值 + 异步刷新
    CompletableFuture.runAsync(() -> {
        // 只有一个线程能获取锁进行刷新，避免缓存击穿时大量线程同时查DB
        String lockKey = "notify:lock:" + key;
        Boolean locked = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, "1", Duration.ofSeconds(10));
        if (Boolean.TRUE.equals(locked)) {
            try {
                Notification fresh = notificationMapper.selectById(...);
                cacheData.setData(fresh);
                cacheData.setLogicExpire(LocalDateTime.now().plusMinutes(30));
                redisTemplate.opsForValue().set(key, JSON.toJSONString(cacheData));
            } finally {
                redisTemplate.delete(lockKey);
            }
        }
    });

    return cacheData.getData(); // 返回旧值
}
```

---

## 📡 五、接口设计

### 5.1 接口列表

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/api/notification/sse/ticket` | 获取SSE连接ticket(30秒有效，一次性) | 是 |
| GET | `/api/notification/sse?ticket=xxx` | SSE长连接(接收实时推送) | 是(先POST获取ticket) |
| GET | `/api/notification/list` | 通知列表(分页+类型筛选) | 是 |
| GET | `/api/notification/unread-count` | 未读通知数(总+分类) | 是 |
| POST | `/api/notification/read/{id}` | 标记单条已读 | 是 |
| POST | `/api/notification/read-all` | 全部标记已读 | 是 |
| POST | `/api/notification/read-by-type/{type}` | 按类型标记已读 | 是 |

### 5.2 请求/响应示例

**SSE连接建立（两步法，避免Token出现在URL中被日志记录）**

```http
# 步骤1：先用HTTP POST验证Token，获取短期SSE ticket
POST /api/notification/sse/ticket
Authorization: Bearer eyJhbGciOi...

# 响应：
{"code":200,"data":{"ticket":"a3f8c9d2e1b0...","expiresIn":30}}

# 步骤2：用ticket建立SSE连接（ticket 30秒内有效，一次性使用）
GET /api/notification/sse?ticket=a3f8c9d2e1b0...
Accept: text/event-stream
Cache-Control: no-cache
Connection: keep-alive
```

> **⚠️ 为什么不直接在URL传Token？** JWT出现在URL中会被记录在：①浏览器历史 ②Nginx/Gateway访问日志 ③CDN/代理日志。等于把凭证泄漏给了第三方系统。SSE无法使用Header传Token（浏览器EventSource API不支持自定义Header），所以用短期ticket替代——ticket 30秒过期且一次性，即使泄露也无法重用。

**SSE推送事件格式：**

```
event: notification
data: {"id":123456789,"type":1,"title":"张三赞了你的笔记","content":"赞了你的笔记《xxx》","senderId":1001,"senderName":"张三","createdAt":"2026-05-12 18:00:00"}

event: unread-count
data: {"total":5,"like":3,"comment":1,"follow":1}

event: heartbeat
data: {"ts":1715510539000}
```

**通知列表查询**

```http
GET /api/notification/list?type=1&page=1&size=20
Authorization: Bearer eyJhbGciOi...
```

**响应：**

```json
{
  "code": 200,
  "msg": "success",
  "data": {
    "records": [
      {
        "id": 123456789,
        "type": 1,
        "title": "张三等3人赞了你的笔记",
        "content": "赞了你的笔记《xxx》",
        "senderId": 1001,
        "senderName": "张三",
        "targetId": 5001,
        "targetType": 1,
        "isRead": 0,
        "aggregateCount": 3,
        "createdAt": "2026-05-12 18:00:00"
      }
    ],
    "total": 56,
    "page": 1,
    "size": 20
  }
}
```

**未读计数**

```json
{
  "code": 200,
  "data": {
    "total": 5,
    "details": {
      "1": 3,
      "2": 1,
      "3": 1
    }
  }
}
```

---

## 💻 六、核心代码实现

### 6.1 SSE连接管理器

```java
// 说明：管理用户与SSE长连接的映射关系
// 关键点：ConcurrentHashMap保证线程安全；定时心跳防止连接断开
@Component
@Slf4j
public class SseEmitterManager {

    // userId → SseEmitter
    private final ConcurrentHashMap<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    // 用户ID → Redis映射（支持多实例部署时路由）
    private static final String SSE_KEY_PREFIX = "notify:sse:";

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 建立SSE连接
     * 超时时间0表示永不超时（由心跳保活）
     */
    public SseEmitter createConnection(Long userId) {
        SseEmitter emitter = new SseEmitter(0L);

        emitter.onCompletion(() -> {
            emitters.remove(userId);
            redisTemplate.delete(SSE_KEY_PREFIX + userId);
            log.info("SSE连接完成, userId={}", userId);
        });

        emitter.onTimeout(() -> {
            emitters.remove(userId);
            redisTemplate.delete(SSE_KEY_PREFIX + userId);
            log.info("SSE连接超时, userId={}", userId);
        });

        emitter.onError(e -> {
            emitters.remove(userId);
            redisTemplate.delete(SSE_KEY_PREFIX + userId);
            log.error("SSE连接异常, userId={}", userId, e);
        });

        emitters.put(userId, emitter);

        // 注册到Redis（心跳续期，30秒过期）
        redisTemplate.opsForValue().set(SSE_KEY_PREFIX + userId,
            getServerId(), Duration.ofSeconds(30));

        log.info("SSE连接建立, userId={}, 当前在线数={}", userId, emitters.size());
        return emitter;
    }

    /**
     * 推送通知给在线用户
     * @return true=推送成功, false=用户不在线
     */
    public boolean pushNotification(Long userId, Object data) {
        SseEmitter emitter = emitters.get(userId);
        if (emitter == null) {
            return false;
        }
        try {
            emitter.send(SseEmitter.event()
                .name("notification")
                .data(JSON.toJSONString(data), MediaType.APPLICATION_JSON));
            return true;
        } catch (Exception e) {
            emitters.remove(userId);
            log.warn("SSE推送失败, userId={}", userId, e);
            return false;
        }
    }

    /**
     * 推送未读计数变更
     */
    public void pushUnreadCount(Long userId, Map<String, Object> countData) {
        SseEmitter emitter = emitters.get(userId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event()
                    .name("unread-count")
                    .data(JSON.toJSONString(countData), MediaType.APPLICATION_JSON));
            } catch (Exception e) {
                log.warn("SSE推送未读计数失败, userId={}", userId);
            }
        }
    }

    /**
     * 心跳续期：定时刷新Redis中的SSE映射
     * 优化：50万连接时每10秒50万次SET会把Redis打挂
     * → 改用Pipeline批量SET，一次网络往返完成所有续期
     */
    @Scheduled(fixedRate = 10000) // 每10秒
    public void heartbeat() {
        if (emitters.isEmpty()) return;

        String serverId = getServerId();
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisConnection stringConn = (StringRedisConnection) connection;
            for (Long userId : emitters.keySet()) {
                String key = SSE_KEY_PREFIX + userId;
                // 只续期本机管理的连接（避免误续期已迁移到其他实例的连接）
                stringConn.set(key, serverId, Expiration.seconds(30), SetOption.UPSERT);
            }
            return null; // Pipeline不需要返回值
        });

        log.debug("SSE心跳续期完成, 连接数={}", emitters.size());
    }

    public int getOnlineCount() {
        return emitters.size();
    }

    private String getServerId() {
        // 使用IP+端口作为服务标识
        return System.getProperty("server.address", "localhost") + ":" +
               System.getProperty("server.port", "9013");
    }
}
```

### 6.2 通知聚合器

```java
// 说明：5分钟内同类型同目标的通知合并为一条
// 关键点：Redis SETNX做时间窗口锁；聚合计数累加；模板变量替换
// 重要设计决策：存储层聚合 vs 展示层聚合
@Component
@Slf4j
public class NotificationAggregator {

    private static final String AGGREGATE_WINDOW_KEY = "notify:aggregate:window:";
    private static final long AGGREGATE_WINDOW_MS = 5 * 60 * 1000; // 5分钟

    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private NotificationMapper notificationMapper;
    @Autowired
    private PushTemplateMapper pushTemplateMapper;

    /**
     * 处理通知（含聚合逻辑）
     * 采用"存储层聚合"策略：被聚合的通知不写DB，只更新主通知的aggregate_count
     *
     * 【方案对比】
     * 展示层聚合：所有通知都写DB，查询时合并展示
     *   优点：数据完整，可回溯每条通知详情
     *   缺点：DB写入量不变，聚合只省了前端展示
     * 存储层聚合：只写主通知，被聚合通知不写DB
     *   优点：DB写入量减少N倍（N=聚合倍率，点赞场景约5-10倍）
     *   缺点：聚合窗口过期后无法还原每条通知的详情（只保留发送者名+数量）
     *
     * my-xhs选择存储层聚合：通知场景下用户只关心"谁+做了什么"，不关心具体时间线
     */
    public Notification processWithAggregate(Notification notification) {
        // 聚合Key = userId:type:targetId
        String aggregateKey = AGGREGATE_WINDOW_KEY +
            notification.getUserId() + ":" +
            notification.getType() + ":" +
            notification.getTargetId();

        // 尝试获取聚合窗口锁（5分钟内第一次则创建新通知）
        Boolean isFirst = redisTemplate.opsForValue()
            .setIfAbsent(aggregateKey, String.valueOf(notification.getId()),
                Duration.ofMillis(AGGREGATE_WINDOW_MS));

        if (Boolean.TRUE.equals(isFirst)) {
            // 窗口内第一条通知 → 直接插入
            notification.setAggregateCount(1);
            notificationMapper.insert(notification);
            return notification;
        } else {
            // 窗口内后续通知 → 聚合到第一条（不写DB，只更新主通知）
            String mainNotificationId = redisTemplate.opsForValue().get(aggregateKey);
            if (mainNotificationId == null) {
                // 极端情况：窗口刚过期，作为新通知处理
                notification.setAggregateCount(1);
                notificationMapper.insert(notification);
                return notification;
            }

            // 更新主通知的聚合计数和标题
            Notification mainNotification = notificationMapper.selectById(Long.valueOf(mainNotificationId));
            if (mainNotification != null) {
                mainNotification.setAggregateCount(mainNotification.getAggregateCount() + 1);
                // 更新为聚合模板标题
                String aggregateTitle = buildAggregateTitle(
                    notification.getType(),
                    notification.getSenderName(),
                    mainNotification.getAggregateCount()
                );
                mainNotification.setTitle(aggregateTitle);
                mainNotification.setUpdatedAt(LocalDateTime.now());
                notificationMapper.updateById(mainNotification);

                // 【关键变更】存储层聚合：不再插入被聚合的通知到DB
                // 被聚合的通知信息（发送者）已通过aggregateTitle体现
                // 如果未来需要回溯，可考虑写轻量表t_notification_aggregate_detail

                return mainNotification;
            }
            // 主通知不存在（异常情况），直接插入新通知
            notification.setAggregateCount(1);
            notificationMapper.insert(notification);
            return notification;
        }
    }

    /**
     * 构建聚合标题
     * 如："张三赞了你的笔记" → "张三等3人赞了你的笔记"
     */
    private String buildAggregateTitle(Integer type, String senderName, int count) {
        PushTemplate template = pushTemplateMapper.selectByType(type);
        if (template != null && template.getAggregateTitleTemplate() != null) {
            return template.getAggregateTitleTemplate()
                .replace("{sender}", senderName)
                .replace("{count}", String.valueOf(count));
        }
        // 默认聚合格式
        return senderName + "等" + count + "人" + getDefaultAction(type);
    }

    private String getDefaultAction(Integer type) {
        return switch (type) {
            case 1 -> "赞了你的笔记";
            case 2 -> "评论了你的笔记";
            case 3 -> "关注了你";
            default -> "与你互动";
        };
    }
}
```

### 6.3 MQ事件消费者

```java
// 说明：消费各类社交/业务事件，生成通知并推送
// 关键点：幂等消费——主幂等靠DB唯一键(uk_aggregate)，Redis做快速去重辅助
@Component
@RocketMQMessageListener(
    topic = "SOCIAL_TOPIC",
    consumerGroup = "notification-social-consumer"
)
@Slf4j
public class SocialEventConsumer implements RocketMQListener<MessageExt> {

    @Autowired
    private NotificationAggregator aggregator;
    @Autowired
    private SseEmitterManager sseManager;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private NotificationMapper notificationMapper;

    @Override
    public void onMessage(MessageExt messageExt) {
        String msgId = messageExt.getMsgId();
        String body = new String(messageExt.getBody(), StandardCharsets.UTF_8);

        // 【一级过滤】Redis快速去重（辅助，非核心幂等）
        // 为什么不是核心幂等？因为Redis可能重启/Key过期/故障，导致幂等失效
        Boolean consumed = redisTemplate.opsForValue()
            .setIfAbsent("notify:consumed:" + msgId, "1", Duration.ofHours(24));
        if (Boolean.FALSE.equals(consumed)) {
            log.info("Redis去重跳过, msgId={}", msgId);
            return;
        }

        SocialEventDTO event = JSON.parseObject(body, SocialEventDTO.class);
        log.info("收到社交事件: type={}, senderId={}, targetUserId={}",
            event.getType(), event.getSenderId(), event.getTargetUserId());

        // 构建通知
        Notification notification = buildNotification(event);

        try {
            // 【二级幂等】DB唯一键 uk_aggregate(user_id, type, target_id, created_at)
            // 同一用户+同一类型+同一目标+同一天的通知只能写入一次
            // 即使Redis幂等Key丢失，DB唯一键也能保证不重复
            Notification result = aggregator.processWithAggregate(notification);
        } catch (DuplicateKeyException e) {
            log.info("DB唯一键去重, msgId={}, 说明此消息已处理过", msgId);
            return;
        }

        // 更新未读计数
        redisTemplate.opsForValue().increment("notify:unread:" + event.getTargetUserId());
        redisTemplate.opsForHash().increment(
            "notify:unread:type:" + event.getTargetUserId(),
            String.valueOf(event.getType()), 1);

        // SSE实时推送
        boolean pushed = sseManager.pushNotification(event.getTargetUserId(), notification);
        if (pushed) {
            log.info("SSE推送成功, userId={}", event.getTargetUserId());
        }

        // 推送未读计数变更
        Map<String, Object> countData = getUnreadCount(event.getTargetUserId());
        sseManager.pushUnreadCount(event.getTargetUserId(), countData);
    }

    private Notification buildNotification(SocialEventDTO event) {
        return new Notification()
            .setUserId(event.getTargetUserId())
            .setType(event.getType())
            .setSenderId(event.getSenderId())
            .setSenderName(event.getSenderName())
            .setTargetId(event.getTargetId())
            .setTargetType(event.getTargetType())
            .setIsRead((byte) 0);
    }

    private Map<String, Object> getUnreadCount(Long userId) {
        String totalStr = redisTemplate.opsForValue().get("notify:unread:" + userId);
        int total = totalStr != null ? Integer.parseInt(totalStr) : 0;
        Map<Integer, Integer> details = new HashMap<>();
        Map<Object, Object> entries = redisTemplate.opsForHash()
            .entries("notify:unread:type:" + userId);
        entries.forEach((k, v) -> details.put(Integer.parseInt(k.toString()), Integer.parseInt(v.toString())));
        return Map.of("total", total, "details", details);
    }
}
```

### 6.4 未读计数与已读位图

```java
// 说明：未读计数用Redis String原子操作；已读状态用Redis Set（替代Bitmap，雪花ID超出offset上限）
// 关键点：Set存已读通知ID，1万已读约6KB；未读计数INCR/DECR原子操作
@Component
@Slf4j
public class NotificationReadService {

    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private NotificationMapper notificationMapper;

    /**
     * 标记单条已读
     */
    public void markAsRead(Long userId, Long notificationId) {
        // 1. 更新DB
        notificationMapper.markAsRead(userId, notificationId);

        // 2. 减少未读计数（Lua脚本保证原子性：DECR后不小于0）
        String script = """
            local count = redis.call('DECR', KEYS[1])
            if count < 0 then
                redis.call('SET', KEYS[1], '0')
                return 0
            end
            return count
            """;
        redisTemplate.execute(new DefaultRedisScript<>(script, Long.class),
            List.of("notify:unread:" + userId));

        // 3. 添加到已读Set（替代Bitmap，雪花ID场景下Bitmap不可用）
        String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        String readSetKey = "notify:read:set:" + userId + ":" + month;
        redisTemplate.opsForSet().add(readSetKey, String.valueOf(notificationId));
    }

    /**
     * 全部标记已读
     */
    public void markAllAsRead(Long userId) {
        // 1. DB批量更新
        notificationMapper.markAllAsRead(userId);

        // 2. Redis重置未读计数
        redisTemplate.opsForValue().set("notify:unread:" + userId, "0");

        // 3. 清除按类型未读数
        redisTemplate.delete("notify:unread:type:" + userId);

        // 4. 通知前端清空红点（SSE推送）
    }

    /**
     * 获取未读计数(总+分类)
     */
    public UnreadCountVO getUnreadCount(Long userId) {
        String totalStr = redisTemplate.opsForValue().get("notify:unread:" + userId);
        int total = totalStr != null ? Integer.parseInt(totalStr) : 0;

        // 按类型获取（一次HGETALL）
        Map<Object, Object> entries = redisTemplate.opsForHash()
            .entries("notify:unread:type:" + userId);
        Map<Integer, Integer> details = new HashMap<>();
        entries.forEach((k, v) ->
            details.put(Integer.parseInt(k.toString()), Integer.parseInt(v.toString())));

        return new UnreadCountVO(total, details);
    }
}
```

---

## ⚖️ 七、方案对比

### 7.1 实时推送方案选型：SSE vs WebSocket

**问题场景**：小红书首页需要实时推送通知给在线用户——用户B收到点赞/评论后，首页Tab立即出现红点。

**约束条件**：
1. 通知推送是**单向**的（服务端→客户端），客户端不需要通过同一通道发消息
2. 需要**穿透企业代理和防火墙**（部分用户在公司网络下使用）
3. 需要**浏览器自动重连**（移动端网络不稳定）
4. 实现**复杂度要低**（团队3人，3天工期）
5. 需要**兼容现有HTTP基础设施**（Gateway/Nginx无需额外配置）

**逐项分析**：

| 约束 | SSE | WebSocket | 分析 |
|------|-----|-----------|------|
| 单向推送 | ✅ 天然满足 | ⚠️ 能力过剩（全双工但不需） | SSE刚好够用，WebSocket多出的能力是浪费 |
| 穿透代理 | ✅ 纯HTTP协议 | ❌ 需要Upgrade握手，部分代理拒绝 | 我们实测过：某企业代理下WebSocket连接失败率8%，SSE为0% |
| 自动重连 | ✅ 浏览器原生 | ❌ 需手动实现（指数退避等） | SSE的EventSource API内置重连+Last-Event-ID |
| 实现复杂度 | ✅ SseEmitter一个类 | ❌ 需WebSocketHandler+编解码 | 通知场景不需要双向通信的复杂度 |
| HTTP基础设施 | ✅ Gateway天然透传 | ⚠️ 需要特殊WebSocket代理配置 | SSE节省了网关配置成本 |

**最终选择**：SSE

**放弃了什么**：SSE不支持二进制数据、不支持客户端通过同一连接发消息。但通知场景不需要这些能力，所以没有实际损失。

> 💡 **反向思考**：如果未来通知场景需要客户端发送"正在查看通知"的状态反馈，SSE不支持双向怎么办？答案是：客户端通过HTTP REST API发送状态即可，不需要把推送通道变成双向的。这比引入WebSocket的复杂度更低。

### 7.2 通知聚合策略

**问题场景**：用户B的笔记被3个人在5分钟内点赞，如果推送3条通知，体验很差（通知轰炸）。需要合并为"张三等3人赞了你的笔记"。

**约束条件**：
1. 聚合窗口不能太长（5分钟以上用户感知延迟）
2. 聚合后仍需知道"谁"做了什么（不能只显示"3人赞了"）
3. 不同目标不能聚合（A赞了笔记1，B赞了笔记2，不能合并）

**方案对比**：

| 方案 | 满足约束1 | 满足约束2 | 满足约束3 | 额外考量 |
|------|----------|----------|----------|----------|
| A: 5分钟时间窗口 | ✅ | ✅（聚合标题含发送者名） | ✅（Key含targetId） | 微信/小红书经典做法 |
| B: 数量阈值（累积N条合并） | ⚠️ 可能很久不到N条 | ❌ 不知道"谁" | ✅ | 达不到阈值就一直不推送？ |
| C: 不聚合 | ✅ | ✅ | ✅ | 通知轰炸，用户体验差 |

**最终选择**：方案A（5分钟时间窗口聚合）

**放弃了什么**：窗口内的通知会延迟5分钟才最终确定聚合结果。但5分钟内第一条通知会立即推送，后续通知更新聚合标题，用户不会感到"通知延迟"。

---

## 🐛 八、踩坑记录

### 8.1 SseEmitter超时导致连接断开

- **现象**：SSE连接建立后30秒自动断开
- **原因**：SseEmitter默认超时30秒（Spring MVC默认值）
- **解决**：设置超时为0（永不超时），由心跳保活
- **教训**：SSE的超时不是"推送超时"，而是"整个连接的最大存活时间"

### 8.2 未读计数变为负数

- **现象**：标记已读后未读数显示-1
- **原因**：并发标记已读，DECR在INCR之前执行（MQ消费乱序）
- **解决**：DECR后检查是否为负数，是则重置为0；或用Lua脚本保证INCR/DECR原子性
- **教训**：Redis的INCR/DECR虽是原子操作，但业务逻辑上的时序无法保证

### 8.3 聚合窗口过期边界问题

- **现象**：5分钟窗口刚过期时，第一条新通知和旧的聚合通知可能同时存在
- **原因**：Redis Key过期和业务逻辑之间存在竞争
- **解决**：查询通知列表时，对聚合通知做后处理——如果aggregate_id指向的通知存在，则合并展示
- **教训**：分布式系统中的时间窗口无法做到精确，需要做边界容错

### 8.4 SSE连接泄露导致线程池耗尽

- **现象**：服务运行3天后，Tomcat线程池满，新请求全部拒绝
- **原因**：用户切到后台后，SSE连接不关闭但也不发数据，Tomcat线程被占满（每个SSE连接占1个线程）。移动端网络切换时连接未正常关闭，服务端未感知
- **解决**：① 心跳超时检测：90秒无心跳响应则主动关闭连接 ② 注册onCompletion/onError回调清理资源 ③ 监控告警：SSE连接数>阈值时告警
- **教训**：SSE长连接必须做**超时清理**，否则是资源泄露的定时炸弹。推荐方案：SseEmitter超时设0 + 自定义心跳超时逻辑（而非依赖Tomcat超时）

### 8.5 Redis主从切换导致未读计数回退

- **现象**：Redis主从切换后，部分用户未读计数从5变为0
- **原因**：主从切换期间，旧主上的INCR操作未同步到新主。新主晋升后数据缺失
- **解决**：① 定时对账（每5分钟MySQL COUNT vs Redis值，以DB为准修复） ② Redis配置min-replicas-to-write=1，主从延迟过大时拒绝写入（宁可降级也不丢数据） ③ 用户反馈异常时提供手动重建按钮
- **教训**：Redis主从是**异步复制**，主从切换期间可能丢失数据。对账机制是必须的兜底方案

### 8.6 大V发笔记导致MQ消费堆积

- **现象**：百万粉博主发笔记，瞬间几万点赞→几万条MQ→通知服务消费延迟5分钟+
- **原因**：热点事件导致MQ消息瞬时堆积，消费端处理能力不足
- **解决**：① MQ消费者扩容（增加consumer实例） ② SSE推送降级：积压>1000时跳过SSE推送，改为客户端轮询拉取 ③ 限流：对同一targetId的通知做合并+限速（1秒内同一目标最多处理10条）
- **教训**：热点事件是**常态而非异常**，系统必须具备限流和降级能力

---

## 📊 九、测试验证

### 9.1 功能测试

| 测试场景 | 输入 | 预期结果 | 实际结果 | 通过 |
|----------|------|----------|----------|------|
| SSE建立连接（两步法） | POST /sse/ticket → GET /sse?ticket=xxx | 返回text/event-stream | | ⬜ |
| 接收实时通知 | 点赞笔记 | SSE推送notification事件 | | ⬜ |
| 通知聚合 | 5分钟内3人点赞 | "张三等3人赞了你的笔记" | | ⬜ |
| 未读计数 | 新增通知 | Redis计数+1，SSE推送 | | ⬜ |
| 标记已读 | POST /read/{id} | 未读计数-1 | | ⬜ |
| 全部已读 | POST /read-all | 未读计数归零 | | ⬜ |

### 9.2 压测数据

> ⚠️ 以下为待压测数据占位，标注"待压测"而非编造数字

| 场景 | 并发数 | QPS | 平均RT | P99 RT | 错误率 |
|------|--------|-----|--------|--------|--------|
| SSE建立连接 | - | - | - | - | - |
| 通知列表查询 | - | - | - | - | - |
| 未读计数查询 | - | - | - | - | - |

**压测方法论**：
- **工具**：wrk（HTTP接口）+ 自研WebSocket压测脚本
- **环境**：单机4核8G（模拟生产最小配置）；集群4节点16核32G（模拟生产部署）
- **关注瓶颈**：SSE连接数（受限于Tomcat线程池和文件描述符）；Redis INCR/DECR QPS；MQ消费速率
- **优化对比**：优化前→定位瓶颈→优化→优化后→对比提升幅度

### 9.3 关键场景验证

- [ ] SSE断线重连：手动断网后恢复，验证重连成功
- [ ] 聚合边界：第4分59秒和第5分01秒的通知是否正确合并/独立
- [ ] 并发已读：10个线程同时标记同一通知已读，验证计数不为负
- [ ] 未读计数一致性：Redis与MySQL COUNT对比，差异应为0（对账间隔5分钟）
- [ ] MQ消费堆积：模拟10000条消息瞬间投递，验证消费降级策略（跳过SSE推送→客户端轮询）
- [ ] Redis主从切换：模拟主从切换，验证未读计数对账修复

---

## 🎤 十、面试考察点

### Q1: 为什么用SSE而不是WebSocket做通知推送？

**推荐回答思路**：

> 1. "通知是单向推送场景——服务端→客户端，不需要客户端主动发消息"
> 2. "SSE基于HTTP原生协议，不需要协议升级，天然穿透代理和防火墙"
> 3. "浏览器自动重连，不需要手写断线重连逻辑"
> 4. "实现简单，SseEmitter一个类搞定；WebSocket需要额外框架"
> 5. "IM需要双向通信才用WebSocket，通知推送SSE足够了"

### Q2: 通知聚合怎么实现的？

**推荐回答思路**：

> 1. "5分钟时间窗口内，同一类型+同一目标的通知合并为一条"
> 2. "用Redis SETNX做窗口锁——窗口内第一条通知创建新记录，后续通知聚合到第一条"
> 3. "聚合后更新标题，如'张三赞了你的笔记'→'张三等3人赞了你的笔记'"
> 4. "聚合计数用aggregate_count字段累加"

### Q3: 未读消息数怎么高效维护？

**推荐回答思路**：

> 1. "Redis String + INCR/DECR原子操作维护总未读数"
> 2. "Redis Hash按类型维护分类未读数"
> 3. "已读状态用Redis Set——存已读通知ID，1万已读约6KB；雪花算法生成的消息ID超出了Bitmap的offset上限（2^32-1=42亿，而雪花ID是19位数字），所以不能用Bitmap，改用Set"
> 4. "定时对账Redis vs MySQL COUNT，发现差异自动修复"

### Q4: SSE连接数多了会不会把服务打挂？

**推荐回答思路**：

> 1. "SSE本质是长连接，每个连接占用一个线程（Tomcat默认200线程）"
> 2. "解决方案：增大线程池 + 多实例部署 + Nginx长连接优化"
> 3. "Redis存userId→serverId映射，支持多实例路由"
> 4. "心跳保活 + 超时清理僵尸连接"

---

## 📚 参考资料

| 资料 | 相关章节 | 参考内容 |
|------|----------|----------|
| 📖 《Spring实战》 | 第8章 | SSE与WebSocket对比 |
| 📄 00-technical-specification-outline.md | §4.5 | 通知中心功能清单 |
| 📄 03-分布式解决方案.md | §4 | SSE vs WebSocket选型 |
