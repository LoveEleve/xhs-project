# my-xhs 项目文档目录大纲

> 对标生产环境的小红书项目，专业级项目文档体系

---

## 📚 文档结构总览

```
my-xhs/docs/
│
├── 📁 architecture/              # 架构设计（6篇）
│   ├── 00-document-directory-outline.md    # 文档目录大纲
│   ├── 00-technical-specification-outline.md # 技术规格大纲
│   ├── 07-architecture-knowledge-tracing.md  # 架构知识溯源
│   ├── 08-architecture-decision-critical-analysis.md # 架构决策分析
│   ├── 10-service-dependency-graph.md       # 服务间调用依赖图
│   └── 20-project-panorama-summary.md       # 🆕 项目全景透视与亮点难点总结
│
├── 📁 business/                  # 业务模块设计（4篇）
│   ├── 01-project-overview.md              # 项目总览
│   ├── 02-module-detailed-design.md         # 模块详细设计
│   ├── 12-content-audit-system-design.md    # 内容审核系统设计
│   └── 16-note-topic-and-tag-system-design.md # 笔记标签/话题系统
│
├── 📁 distributed/               # 分布式解决方案（3篇 + 11个深度技术点）
│   ├── 03-distributed-solutions.md         # 分布式解决方案（含15-25章深度技术点）
│   ├── 17-seller-order-query-solution.md    # 卖家维度订单查询方案
│   └── 19-incremental-data-reconciliation.md # 数据增量对账方案
│
│   # 03-distributed-solutions.md 中新增的深度技术点（华仔项目未涉及）：
│   # §15 请求级超时预算（Timeout Budget）— Google SRE实践
│   # §16 自动降级决策引擎 — 多指标综合→L0-L3自动降级→半开恢复
│   # §17 数据倾斜检测与自动再均衡 — 分片监控+在线扩分片
│   # §18 幂等性增强：返回上次成功结果 — 对标支付宝幂等设计
│   # §19 慢查询自动发现与治理闭环 — slow_log→ES→Grafana→自动EXPLAIN→周报
│   # §20 数据生命周期自动化管理 — XXL-Job分批归档+ES ILM Policy
│   # §21 JVM调优参数模板 — G1 GC科学计算+GC日志分析
│   # §22 连接池参数科学计算 — HikariCP/Lettuce/RocketMQ公式推导
│   # §23 API版本兼容性矩阵 — 版本生命周期管理+废弃通知机制
│   # §24 Service Mesh预留设计 — Spring Cloud→Istio演进路径
│   # §25 事件溯源（Event Sourcing）— 订单事件表+快照表互补
│
├── 📁 infrastructure/            # 基础设施与运维（4篇）
│   ├── 04-infrastructure-and-deployment.md  # 基础设施与部署
│   ├── 14-capacity-and-infra-consistency-analysis.md # 量级与基础设施自洽性
│   ├── 15-database-migration-management.md  # 数据库变更管理
│   └── 18-full-chain-stress-test-baseline.md # 全链路压测基线
│
├── 📁 bff/                       # API聚合层（1篇）
│   └── 11-bff-aggregation-layer-design.md   # BFF聚合层详细设计
│
├── 📁 production/                # 生产决策与运维手册（3篇）
│   ├── 06-production-decision-and-expression-handbook.md # 生产决策与表达手册
│   ├── 09-technical-pitfall-guide.md        # 技术踩坑指南
│   └── 13-service-degradation-playbook.md   # 服务降级预案手册
│
├── 📁 verification/              # 验证与对比（2篇）
│   ├── 05-feature-coverage-comparison.md    # 功能覆盖比对
│   └── 99-coverage-verification-report.md   # 覆盖验证报告
│
├── 📁 dev/                       # 开发日志（7个Phase 47篇）
│   └── Phase-5/24-performance-optimization-and-stress-testing/
│       └── massive-data-generation.md  # 🆕 亿级测试数据生成方案
├── 📁 references/                # 参考资料（3篇）
└── 📁 design/                    # 服务详细设计（规划中）
└── 📁 interview/                 # 面试专题（规划中）
```

---

## 一、服务详细设计文档 (design/)

### 1.1 用户服务 (user-service.md)

```
一、业务场景
  1.1 核心功能：注册/登录/个人信息/收货地址
  1.2 业务约束：手机号唯一、邮箱唯一、收货地址上限20个
  1.3 数据量预估：用户表1000万级

二、数据库设计
  2.1 用户表 (t_user)
  2.2 收货地址表 (t_user_address)
  2.3 索引设计

三、缓存设计
  3.1 Redis Key设计
      - user:info:{userId} → Hash (用户基本信息)
      - user:token:{userId} → String (JWT Token)
      - user:address:default:{userId} → String (默认地址ID)
  3.2 缓存更新策略：Cache Aside + 延迟双删
  3.3 缓存一致性方案

四、接口设计
  4.1 用户注册 POST /api/user/register
  4.2 用户登录 POST /api/user/login
  4.3 Token刷新 POST /api/user/refresh
  4.4 获取用户信息 GET /api/user/info
  4.5 收货地址CRUD

五、核心实现要点
  5.1 图形验证码 + @RateLimit防刷
  5.2 邮箱验证码 + Redis存储 + 5分钟过期
  5.3 密码加密：BCrypt
  5.4 JWT双Token机制：Access 30min + Refresh 7d
  5.5 分布式锁保证注册唯一性

六、技术亮点（vs huazai）
  - 双Token机制（huazai单Token）
  - Gateway统一鉴权（huazai在业务服务鉴权）
  - @RateLimit注解防暴力破解

七、生产决策与表达
  Q: 用户登录怎么做的？
  Q: Token过期了怎么办？
  Q: 如何防止暴力破解？
```

---

### 1.2 笔记服务 (note-service.md)

```
一、业务场景
  1.1 核心功能：笔记发布/编辑/删除/详情/评论
  1.2 业务约束：标题50字、正文5000字、图片9张
  1.3 数据量预估：笔记表5000万级、评论表1亿级

二、数据库设计
  2.1 笔记表 (t_note)
  2.2 评论表 (t_comment) - 楼中楼设计 parent_id
  2.3 索引设计

三、缓存设计
  3.1 Redis Key设计
      - note:info:{noteId} → Hash (笔记详情)
      - note:comment:time:{noteId} → ZSet (评论时间序)
      - note:comment:hot:{noteId} → ZSet (评论热度序)
  3.2 逻辑过期防缓存击穿
  3.3 评论双ZSet方案（时间序+热度序）

四、接口设计
  4.1 发布笔记 POST /api/note/publish
  4.2 笔记详情 GET /api/note/{noteId}
  4.3 发表评论 POST /api/note/comment
  4.4 评论列表 GET /api/note/comment/list

五、核心实现要点
  5.1 RocketMQ顺序消息保证评论顺序
  5.2 DFA敏感词过滤（10万词库毫秒级匹配）
  5.3 评论审核状态机
  5.4 评论计数异步更新

六、技术亮点（vs huazai）
  - DFA敏感词算法（huazai无）
  - 楼中楼评论设计（huazai仅一级评论）
  - 双ZSet评论排序

七、生产决策与表达
  Q: 评论系统怎么设计的？
  Q: 敏感词过滤怎么做的？
  Q: 评论排序怎么实现？
```

---

### 1.3 社交服务 (social-service.md)

```
一、业务场景
  1.1 核心功能：关注/取关/粉丝列表/关注列表/点赞/收藏
  1.2 业务约束：关注上限5000人
  1.3 数据量预估：关注关系10亿级

二、数据库设计
  2.1 关注关系表 (t_follow)
  2.2 点赞表 (t_like)
  2.3 收藏表 (t_favorite)

三、缓存设计
  3.1 Redis Key设计
      - social:following:{userId} → ZSet (关注列表，score=关注时间)
      - social:follower:{userId} → ZSet (粉丝列表)
      - social:like:note:{noteId} → Set (笔记点赞用户集合)
      - social:like:user:{userId} → Set (用户点赞的笔记集合)
  3.2 ZSet替代List的优势

四、接口设计
  4.1 关注 POST /api/social/follow
  4.2 取关 POST /api/social/unfollow
  4.3 关注列表 GET /api/social/following
  4.4 粉丝列表 GET /api/social/follower
  4.5 点赞/取消点赞 POST /api/social/like

五、核心实现要点
  5.1 Lua脚本原子操作（关注+计数）
  5.2 RocketMQ异步落库
  5.3 推拉结合Feed流
      - 推模式：关注流（写扩散）
      - 拉模式：发现流（读扩散）
  5.4 大V关注热点处理

六、技术亮点（vs huazai）
  - ZSet替代List（huazai用List）
  - 推拉结合Feed流（huazai仅推模式）
  - Lua原子操作

七、生产决策与表达
  Q: 关注列表用什么数据结构存？
  Q: 微博/朋友圈Feed流怎么设计？
  Q: 大V发动态，千万粉丝怎么推送？
```

---

### 1.4 计数服务 (counter-service.md)

```
一、业务场景
  1.1 核心功能：点赞数/收藏数/评论数/关注数/粉丝数
  1.2 业务约束：允许模糊计数（延迟1分钟内）
  1.3 数据量预估：计数记录10亿级，QPS 10万+

二、数据库设计
  2.1 计数表 (t_counter) - bizType + bizId + count
  2.2 计数流水表 (t_counter_log) - 用于对账

三、缓存设计
  3.1 Redis Key设计
      - counter:{bizType}:{bizId} → String (计数值)
      - counter:buffer → Hash (本地缓冲区)
  3.2 Caffeine本地缓存（热点计数）

四、核心实现要点
  4.1 Buffer-Trigger批量写
      - 攒批：100条或1秒
      - 合并：同一Key的增减合并
      - 批量写入DB
  4.2 对账修复机制
      - XXL-Job定时扫描
      - Redis vs DB差异检测
      - 自动修复
  4.3 模糊计数展示（10万+、100万+）

五、架构设计
  5.1 为什么独立服务？
      - 解耦业务逻辑
      - 独立扩容
      - 统一计数口径
  5.2 高可用设计

六、技术亮点（vs huazai）
  - 独立服务（huazai耦合在social）
  - Buffer-Trigger（huazai直接写）
  - 对账修复机制（huazai无）

七、生产决策与表达
  Q: 10万赞怎么存？
  Q: 高频计数怎么优化？
  Q: Redis和DB不一致怎么办？
```

---

### 1.5 通知中心 (push-service.md)

```
一、业务场景
  1.1 核心功能：系统通知/互动通知
  1.2 通知类型：点赞、评论、关注、@提及、系统公告
  1.3 数据量预估：通知消息100亿级

二、数据库设计
  2.1 通知模板表 (t_notification_template)
  2.2 通知消息表 (t_notification) - 分库分表
  2.3 已读状态：Bitmap存储

三、缓存设计
  3.1 Redis Key设计
      - notify:unread:{userId} → String (未读数)
      - notify:read:bitmap:{userId}:{month} → Bitmap (已读状态)
      - notify:list:{userId} → ZSet (通知列表)

四、核心实现要点
  4.1 MQ异步分发
      - 点赞/评论/关注 → 触发通知
      - RocketMQ广播消费
  4.2 通知聚合
      - "张三等10人赞了你的笔记"
      - 时间窗口聚合
  4.3 SSE实时推送（生产选型，优于WebSocket）
  4.4 Bitmap已读状态

五、技术亮点（vs huazai）
  - 完整通知中心（huazai无）
  - Bitmap已读状态（空间节省99%）
  - SSE实时推送

六、生产决策与表达
  Q: 微信/小红书通知系统怎么设计？
  Q: 未读数怎么统计？
  Q: 已读状态怎么存储？
```

---

### 1.6 商品服务 (product-service.md)

```
一、业务场景
  1.1 核心功能：SPU/SKU管理、商品详情、分类
  1.2 数据量预估：SPU 100万、SKU 1000万

二、数据库设计
  2.1 SPU表、SKU表、分类表、属性表、规格表
  2.2 索引设计

三、缓存设计
  3.1 多级缓存架构
      - L1: Caffeine本地缓存（热点商品）
      - L2: Redis分布式缓存
      - L3: MySQL
  3.2 HotKey探测 + 本地缓存升级
  3.3 缓存预热

四、核心实现要点
  4.1 商品详情页聚合
  4.2 逻辑过期防击穿
  4.3 Canal监听商品变更 → 删缓存/同步ES

五、技术亮点
  - 多级缓存（Caffeine+Redis）
  - HotKey探测自动升级
  - Canal实时同步

六、生产决策与表达
  Q: 商品详情页QPS多少？怎么扛住的？
  Q: 缓存击穿怎么解决？
  Q: 热点商品怎么处理？
```

---

### 1.7 搜索服务 (search-service.md)

```
一、业务场景
  1.1 核心功能：商品搜索、笔记搜索、搜索建议、热搜榜
  1.2 数据量预估：索引文档1000万级

二、ES索引设计
  2.1 商品索引 (product_index)
  2.2 笔记索引 (note_index)
  2.3 搜索建议索引 (suggest_index) - Completion类型

三、核心实现要点
  3.1 Canal + MQ增量同步
  3.2 全量重建（XXL-Job + 分页 + 断点续传）
  3.3 搜索建议（Completion Suggester）
  3.4 热搜榜（ZSet + 滑动窗口）

四、技术亮点
  - 独立搜索服务（huazai耦合在product）
  - 双索引设计（商品+建议）
  - 热搜榜实时统计

五、生产决策与表达
  Q: 搜索为什么独立服务？
  Q: ES和MySQL怎么同步？
  Q: 搜索建议怎么实现？
```

---

### 1.8 购物车服务 (cart-service.md)

```
一、业务场景
  1.1 核心功能：加购/删除/修改数量/清空/选中状态
  1.2 业务约束：单品上限99、总数上限200

二、缓存设计
  2.1 Redis Key设计
      - cart:items:{userId} → Hash (商品ID→数量)
      - cart:checked:{userId} → Set (选中商品ID)
      - cart:sort:{userId} → ZSet (排序)

三、核心实现要点
  3.1 HSETNX防重复加购
  3.2 匿名购物车 → 登录后合并
  3.3 商品失效标记（下架/无库存）
  3.4 价格变动提醒
  3.5 Redis→MySQL异步落库（防Redis故障丢购物车）

四、技术亮点
  - 3种Redis结构协同
  - 匿名购物车合并
  - 异步持久化三层保障

五、生产决策与表达
  Q: 购物车用什么数据结构存？
  Q: 未登录加购怎么处理？
  Q: Redis故障购物车会丢吗？
```

---

### 1.9 库存服务 (inventory-service.md)

```
一、业务场景
  1.1 核心功能：库存初始化/扣减/回退/查询
  1.2 数据量预估：SKU 1000万，秒杀QPS 10万+

二、核心实现要点
  2.1 分桶预扣减
      - 按SKU拆分N个桶
      - 按userId路由到固定桶
      - 单桶扣减失败 → 尝试其他桶
  2.2 Lua原子扣减
  2.3 三级库存扣减
      - L1: Redis分桶预扣
      - L2: MQ异步扣DB
      - L3: 对账修复
  2.4 预扣超时自动回退

三、技术亮点
  - 分桶策略（huazai有）
  - 桶间自动均衡（huazai无）
  - 预扣超时回退（huazai无）

四、生产决策与表达
  Q: 10万人抢1个Key怎么办？
  Q: 库存扣减怎么保证不超卖？
  Q: 预扣了但没下单怎么办？
```

---

### 1.10 优惠券服务 (coupon-service.md)

```
一、业务场景
  1.1 核心功能：券模板创建/领券/用券/退券
  1.2 数据量预估：券模板1万、用户券10亿

二、数据库设计
  2.1 券模板表 (t_coupon_template)
  2.2 用户券表 (t_user_coupon) - 分库分表

三、核心实现要点
  3.1 责任链校验（领券资格）
  3.2 Lua原子领券（扣库存+绑定用户）
  3.3 XXL-Job推送引擎
      - 任务分片
      - 消息合并
  3.4 ShardingSphere分库分表

四、技术亮点
  - Lua原子领券（huazai分两步）
  - 推送引擎任务分片

五、生产决策与表达
  Q: 秒杀券怎么防超领？
  Q: 千万用户发券怎么推送？
```

---

### 1.11 订单服务 (order-service.md)

```
一、业务场景
  1.1 核心功能：下单/取消/模拟支付/发货/收货
  1.2 订单状态：待支付→已支付→已发货→已收货→已完成/已取消/已退款
  1.3 数据量预估：订单10亿级

二、数据库设计
  2.1 订单主表 (t_order)
  2.2 订单明细表 (t_order_item)
  2.3 分库分表：按userId分片

三、核心实现要点
  3.1 状态机设计（Spring StateMachine）
  3.2 事务消息下单
      - Half消息 → 本地事务 → Commit/Rollback
      - 本地消息表兜底
  3.3 延时消息关单（30分钟）
  3.4 模拟支付（接口抽象+Mock实现，生产切支付宝/微信）
  3.5 Canal + MQ同步ES
  3.6 CompletableFuture异步编排

四、技术亮点
  - 状态机配置化（huazai用Squirrel）
  - 本地消息表兜底（huazai无）
  - 死信队列处理
  - 支付接口抽象（可无缝切换）

五、生产决策与表达
  Q: 订单状态流转怎么设计？
  Q: 下单怎么保证一致性？
  Q: 超时未支付怎么关单？
  Q: 为什么用模拟支付？
```

---

### 1.12 网关服务 (gateway-service.md)

```
一、核心功能
  1.1 路由转发
  1.2 JWT统一鉴权
  1.3 HMAC签名验证（防篡改+防重放）
  1.4 Sentinel限流熔断
  1.5 灰度路由（Nacos元数据）
  1.6 API版本路由（Header: X-Api-Version）
  1.7 TraceId注入
  1.8 CORS跨域

二、核心实现要点
  2.1 GlobalFilter链
  2.2 自定义路由断言
  2.3 限流规则配置

三、技术亮点
  - HMAC签名验证（huazai无）
  - 灰度路由（huazai无）
  - API版本路由（huazai无）

四、生产决策与表达
  Q: 网关做了什么？
  Q: 灰度发布怎么实现？
  Q: 接口安全怎么保证？
```

---

## 二、架构方案专题 (architecture/)

### 2.1 多级缓存方案 (multi-level-cache.md)
```
一、方案对比
  - 方案1：Cache Aside Pattern
  - 方案2：Read/Write Through
  - 方案3：Write Behind
  - 方案4：Refresh Ahead

二、my-xhs采用方案
  - L1: Caffeine本地缓存
  - L2: Redis分布式缓存
  - L3: MySQL

三、各层职责与配置

四、代码实现

五、生产决策与表达
```

### 2.2 缓存一致性方案 (cache-consistency.md)
```
一、问题分析
  - 先更新DB还是先删缓存？
  - 延迟双删原理
  - Canal监听方案

二、my-xhs采用方案
  - 写操作：更新DB → 删Redis → 延迟双删
  - 异步兜底：Canal监听binlog → MQ → 删缓存

三、代码实现

四、生产决策与表达
```

### 2.3 分布式锁方案 (distributed-lock.md)
```
一、方案对比
  - Redis SETNX
  - Redisson
  - Zookeeper

二、my-xhs采用方案
  - Redisson + @DistributedLock注解

三、使用场景
  - 用户注册
  - 库存扣减
  - 订单创建

四、代码实现

五、生产决策与表达
```

### 2.4 消息可靠性方案 (mq-reliability.md)
```
一、问题分析
  - 消息丢失场景
  - 消息重复场景

二、my-xhs采用方案
  - 生产端：事务消息 + 本地消息表
  - 消费端：幂等消费 + 死信队列

三、死信队列处理流程

四、代码实现

五、生产决策与表达
```

### 2.5 分库分表方案 (sharding-strategy.md)
```
一、分片策略
  - 订单表：按userId分片
  - 优惠券表：按userId分片

二、ShardingSphere配置

三、分片算法实现

四、读写分离配置

五、生产决策与表达
```

### 2.6 热点Key解决方案 (hotkey-solution.md)
```
一、问题分析
  - 热点Key导致单节点压力

二、解决方案
  - JD-HotKey探测
  - 本地缓存升级
  - Key分片

三、my-xhs实现

四、生产决策与表达
```

### 2.7 限流降级方案 (rate-limit-degrade.md)
```
一、Sentinel配置
  - 流控规则
  - 熔断规则
  - 热点参数限流

二、自定义降级响应

三、@RateLimit注解实现

四、生产决策与表达
```

### 2.8 分布式事务方案 (distributed-transaction.md)
```
一、方案对比
  - 2PC/3PC
  - TCC
  - 事务消息
  - 本地消息表
  - Seata AT

二、my-xhs采用方案
  - 订单创建：事务消息 + 本地消息表兜底

三、代码实现

四、生产决策与表达
```

---

## 三、基础设施指南 (infra/)

### 3.1 Docker Compose一键启动 (docker-compose.md)
```
一、服务清单
  - MySQL主从、Redis哨兵、RocketMQ、ES、Nacos、XXL-Job...

二、docker-compose.yml配置

三、启动步骤

四、常见问题
```

### 3.2 K8s部署指南 (k8s-deployment.md)
```
一、Deployment配置
二、Service配置
三、ConfigMap/Secret
四、滚动更新策略
五、优雅停机配置
```

### 3.3 Jenkins流水线 (jenkins-pipeline.md)
```
一、Jenkinsfile完整配置
二、多分支流水线
三、自动化部署流程
```

### 3.4 监控告警配置 (prometheus-grafana.md)
```
一、Prometheus配置
  - scrape_configs
  - alerting_rules

二、Exporter部署
  - node_exporter
  - redis_exporter
  - mysql_exporter
  - elasticsearch_exporter
  - rocketmq_exporter

三、Grafana仪表盘
  - JVM监控：4701
  - MySQL监控：7362
  - Redis监控：18345
  - ES监控：14191
  - RocketMQ监控：14612

四、告警规则
  - 错误率告警
  - RT告警
  - 消费堆积告警
```

### 3.5 链路追踪配置 (skywalking-setup.md)
```
一、SkyWalking部署（OAP + UI）
二、Agent接入
三、自定义Span
四、告警配置
```

### 3.6 性能测试指南 (performance-test.md)
```
一、JMeter测试计划
二、GoReplay流量录制
  - 录制命令
  - 回放命令
  - 流量放大
三、压测指标解读
四、性能优化checklist
```

### 3.7 日志体系 (logging-system.md)
```
一、日志规范
  - JSON结构化格式
  - TraceId必须包含
  - 敏感信息脱敏

二、日志采集架构
  - Promtail Agent
  - Loki存储
  - Grafana查询

三、日志告警规则
  - 错误日志突增
  - 关键业务异常
  - OOM检测

四、日志与链路追踪关联
```

### 3.8 配置中心规范 (nacos-config.md)
```
一、Nacos配置分组
  - COMMON_GROUP(公共配置)
  - SERVICE_GROUP(服务配置)
  - SENTINEL_GROUP(限流规则)

二、多环境隔离
  - Namespace划分
  - 配置继承

三、配置热更新
  - @RefreshScope使用
  - 功能开关
```

### 3.9 测试策略 (testing-strategy.md)
```
一、测试金字塔
  - 单元测试(JUnit5+Mockito)
  - 集成测试(Testcontainers)
  - 契约测试(Spring Cloud Contract)
  - E2E测试

二、覆盖率要求
  - 核心业务逻辑 ≥ 80%

三、Testcontainers示例
  - MySQL容器
  - Redis容器
  - 完整流程测试
```

---

## 四、面试专题 (interview/)

> 此目录专门用于面试准备，与生产设计文档分开

### 4.1 高频面试题 (hot-questions.md)
```
按模块整理50道高频问题及回答思路
```

### 4.2 项目介绍话术 (project-introduction.md)
```
一、30秒版本
二、1分钟版本
三、3分钟版本
四、常见追问及回答
```

### 4.3 技术亮点话术 (technical-highlights.md)
```
26个技术亮点的详细讲解话术
```

### 4.4 简历写法建议 (resume-writing.md)
```
一、项目描述模板
二、技术栈描述
三、亮点提炼
四、数据量化
```

---

## 五、文档编写优先级

| 优先级 | 文档 | 原因 |
|--------|------|------|
| P0 | user-service.md | 核心技术点全面 |
| P0 | counter-service.md | 独立亮点，差异化 |
| P0 | social-service.md | Feed流是核心亮点 |
| P0 | cache-consistency.md | 架构重点 |
| P0 | mq-reliability.md | 架构重点 |
| P1 | order-service.md | 状态机+事务消息 |
| P1 | inventory-service.md | 分桶方案 |
| P1 | search-service.md | 独立服务亮点 |
| P1 | push-service.md | 独立亮点 |
| P1 | 40-生产踩坑速查/ | 面试高频、12组件42坑 |
| P1 | 37-限流降级方案/ | 面试高频、4算法+分层限流 |
| P1 | 38-消息可靠性全链路/ | 面试高频、6环节+死信队列 |
| P2 | 其他服务设计文档 | |
| P2 | 29-混沌工程与故障演练/ | 生产验证、ChaosBlade |
| P2 | 30-安全合规体系/ | 生产必备、HMAC/BCrypt/RBAC |
| P2 | 31-日志体系与可观测性/ | 生产必备、TraceId关联 |
| P2 | 36-高可用与故障预案/ | 架构重点、P0-P3分级 |
| P2 | 39-分布式ID方案/ | 分库分表必会 |
| P2 | 41-全链路压测基线/ | 性能工程、基线对比 |
| **P0** | **🆕 massive-data-generation.md** | **亿级测试数据生成（参考华仔41/46/98篇）** |
| **P1** | **🆕 20-project-panorama-summary.md** | **项目全景透视（参考华仔95篇）** |
| P3 | 32-CI/CD与自动化部署/ | DevOps实践 |
| P3 | 33-测试策略与质量保障/ | 工程实践 |
| P3 | 34-数据备份与容灾/ | 生产必备但频率低 |
| P3 | 35-配置中心与多环境管理/ | 工程规范 |
| P3 | interview/下所有文档 | |

---

## 六、补充文档（从功能/架构/技术专家/真实性维度补充）

> 从四个维度审视文档体系完整性，补充了10个关键缺失文档。

### 6.1 服务间调用依赖图 (architecture/10-service-dependency-graph.md)

```
一、依赖图总览（ASCII图）
二、严格依赖矩阵（15×15服务调用矩阵）
三、调用层级定义
  - Layer 0: Gateway
  - Layer 1: Home(聚合层)
  - Layer 2: 业务服务层
  - Layer 3: 基础服务层
  - Layer 4: 基础设施层
  - 层级规则：禁止同层循环、禁止反向调用
四、核心调用链路详解
  - 下单链路（6个服务）
  - Feed流链路（6个服务并行）
  - 笔记发布链路
  - 社交互动链路
五、循环依赖检测与预防
六、Feign Client定义规范
七、服务间数据共享策略
八、生产决策与表达
```

### 6.2 API聚合层/BFF详细设计 (bff/11-bff-aggregation-layer-design.md)

```
一、为什么需要BFF层（有/无BFF对比）
二、BFF层架构设计（my-xhs-home定位）
三、核心聚合实现
  - 首页Feed聚合（CompletableFuture并行6个服务）
  - 商品详情聚合
四、降级策略（按服务重要性分级P0/P1/P2）
五、线程池配置（独立ThreadPoolExecutor，不用ForkJoinPool）
六、监控指标
七、生产决策与表达
```

### 6.3 内容审核系统设计 (business/12-content-audit-system-design.md)

```
一、审核体系总览（三层审核）
二、第一层：机器审核（实时）
  - 文本审核：DFA敏感词+模糊匹配增强
  - 图片审核：接口抽象+Mock实现（生产切云API）
  - 风控审核：注册时间/频率/IP/历史违规
三、第二层：人工审核
  - 审核队列设计（t_audit_task表）
  - 审核优先级（风险等级1-3）
  - 审核员工作台
四、第三层：用户举报
  - 举报流程+处罚体系+申诉机制
五、审核与发布流程集成
六、审核数据统计与运营
七、数据库表设计补充（t_audit_task + t_violation_record）
八、生产决策与表达
```

### 6.4 服务降级预案手册 (production/13-service-degradation-playbook.md)

```
一、降级原则（5条原则+业务P0-P3分级）
二、逐服务降级方案
  - 搜索服务（L1缓存→L2热门→L3分类）
  - 计数服务（L1缓存→L2返回0→L3隐藏）
  - 通知服务（L1延迟→L2关闭SSE→L3下线）
  - 推荐系统（L1简化路数→L2纯热门→L3时间倒序）
  - Feed流（L1推荐填充→L2纯热门→L3分类浏览）
  - 商品/库存服务、订单服务
三、中间件降级方案（Redis/MySQL/RocketMQ/ES）
四、降级开关管理（Nacos配置+操作规范）
五、降级演练清单（ChaosBlade 7个场景）
六、生产决策与表达
```

### 6.5 量级目标与基础设施自洽性分析 (infrastructure/14-capacity-and-infra-consistency-analysis.md)

```
一、问题定义（100万DAU vs 单节点ES/Redis不自洽）
二、逐组件自洽性校验
  - Redis：单主10万QPS < 峰值24万QPS → 修正为Cluster 3分片
  - ES：单节点无高可用 → 修正为3节点集群
  - MySQL：1主1从够用（QPS 200 << 3000）
  - RocketMQ：单主无高可用 → 修正为1主1从同步双写
三、修正后基础设施清单（9台→16台）
四、容量水位线定义（安全/警告/危险/爆满）
五、峰值QPS推算表（各接口+各服务实例数）
六、生产决策与表达
```

### 6.6 数据库变更管理方案 (infrastructure/15-database-migration-management.md)

```
一、为什么需要DDL Migration管理（5个血泪教训）
二、方案选型：Flyway
三、Flyway集成方案（依赖+配置+脚本命名规范）
四、DDL变更规范
  - 安全变更vs危险变更
  - 变更流程（6步法）
  - 大表DDL变更策略（pt-osc/gh-ost/Instant DDL）
五、回滚方案（回滚策略矩阵+脚本编写规范+数据备份策略）
六、多服务Schema管理
七、生产决策与表达
```

### 6.7 笔记标签/话题系统设计 (business/16-note-topic-and-tag-system-design.md)

```
一、话题系统功能概述
二、数据库设计
  - t_topic话题表
  - t_note_topic关联表
  - t_note增加tags字段
  - tags vs topics区别
三、核心功能设计
  - 笔记发布添加话题（#话题#提取+自动创建）
  - 话题页面聚合
  - 话题搜索（输入#自动联想）
  - 热门话题（Redis ZSet+滑动窗口）
四、ES索引设计
五、缓存设计
六、生产决策与表达
```

### 6.8 卖家维度订单查询方案 (distributed/17-seller-order-query-solution.md)

```
一、问题分析（buyer_id分片后卖家查单需跨库）
二、方案对比（ES宽表/双写/全局表/异构索引）
三、推荐方案：ES宽表+异构索引双保险
  - ES宽表：Canal同步，按seller_id查询
  - 异构索引：t_order_seller_idx表，4库并行查询20ms
四、数据一致性保障
  - ES增量对账（每小时）
  - 异构索引强一致（同事务）
五、卖家订单列表查询实现（优先ES，降级走异构索引）
六、生产决策与表达
```

### 6.9 全链路压测基线与性能边界 (infrastructure/18-full-chain-stress-test-baseline.md)

```
一、压测方法论（4种压测类型+环境标准+指标定义）
二、单接口性能基线（12个核心接口QPS/RT/瓶颈）
三、核心链路端到端性能
  - 下单链路（RT分解+瓶颈分析）
  - 首页Feed链路
  - 笔记发布链路
四、系统容量边界
  - 单实例容量表
  - 集群容量表（2实例/服务）
  - 系统整体天花板
五、JMeter压测脚本模板
六、性能优化Top5
七、生产决策与表达
```

### 6.10 数据增量对账方案 (distributed/19-incremental-data-reconciliation.md)

```
一、问题分析（Canal→MQ→ES三级串联5种不一致场景）
二、增量对账方案设计
  - 对账架构（MySQL→对账引擎←ES→告警）
  - 对账策略（按业务不同频率5min-1h）
  - 对账SQL模板
三、对账引擎实现（ReconcileEngine通用框架）
四、定时对账任务（订单5min/商品10min/笔记30min）
五、全量重建方案（兜底，零停机别名切换）
六、监控与告警（6个对账指标+Prometheus告警规则）
七、生产决策与表达
```

---

## 七、文档编写规范

1. **每个服务文档必须包含**：
   - 业务场景 + 数据量预估
   - 数据库设计 + 索引
   - Redis Key设计（命名规范、数据结构、TTL）
   - 核心接口时序图
   - 技术亮点（vs huazai的差异）
   - 生产决策与表达（Q&A形式）

2. **架构方案文档必须包含**：
   - 多方案对比
   - my-xhs采用的方案及原因
   - 核心代码实现
   - 生产决策与表达

3. **代码示例要求**：
   - 关键代码片段（非完整代码）
   - 注释说明设计意图
   - 标注性能/可靠性考量点
