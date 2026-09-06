# my-xhs-content 数据、分布式与工程问题

## 1. MySQL

核心表为 `t_note`、`t_comment`、`t_local_message` 和预期的 `t_note_event`。当前 SQL 需要重点核对：

- `t_note` 查询用户笔记使用 `user_id + status + created_at`，现有索引不足以完全匹配
- 评论游标使用 `note_id/parent_id/id`，单列索引可能造成额外扫描
- `t_local_message` 的 push progress 字段在根 `V1 + V2` 路径上存在迁移依赖；部署包的 `mysql-content-init.sql` 已直接包含这些字段，必须区分两套初始化来源
- 根 migration 和部署包初始化 SQL 均未发现 `t_note_event` 建表语句；代码写入异常会被吞掉，这是已确认的部署/代码契约缺陷

## 2. Redis

Redis 参与笔记详情缓存、评论计数缓存、动态敏感词、Pub/Sub reload、Feed 推送进度和补偿任务分布式锁。详情使用普通 Cache Aside，不使用带锁回源，热点过期时会并发打 DB。

`CacheHelper.delayDoubleDelete` 只有 RedisUnavailableException 才会直接退出；普通删除返回 false 仍会进入延迟二次删除，第二次异常才尝试发送缓存失效消息。Redis 连接故障时二次删除和 MQ 兜底不会执行；Redis 故障时空值缓存也无法回填，热点不存在数据可能持续打数据库。

## 3. RocketMQ 与本地消息

Feed 发布使用本地消息表，其他浏览、分享、评论、通知和删除事件多数直接异步发送。直接发送失败只记录日志，没有统一 Outbox 或补偿，因此事件可靠性不一致。

Feed 补偿存在：
- 查询后直接发送，缺少 claim/in-flight 状态
- 锁租约可能短于异步发送耗时
- Redis 进度与 MySQL push_status 双写非原子
- 回调可能覆盖已完成状态
- push_status=3 没有自动重试入口
- MQ 发送重试有 `MAX_RETRY=3`；但 Feed 未完成状态的定时补偿没有独立最大次数/人工处理入口，可能持续重投

## 4. Canal 与 ES 边界

content 源码没有 Canal Client 和 Elasticsearch Client。Canal 到 RocketMQ/ES/Redis 的链路属于外部部署和其他消费者，不能把索引同步写成 content 已实现能力。需要运行态确认笔记更新/删除是否最终进入搜索索引。

## 5. Feign

评论通知依赖 User Feign 获取昵称，失败会降级为“某用户”，但调用位于事务提交后的回调链，仍可能消耗同步等待时间。当前未见明确 fallback、隔离和独立重试策略。

## 6. 文件存储

本地上传使用 `/data/uploads`，映射 `/uploads/**`。magic bytes 校验前使用 `file.getBytes()`，并发大文件会产生内存压力；上传资源没有所有权绑定、孤儿清理和多实例共享存储契约。

## 7. 问题分类

### 代码 Bug
- 本地消息表 body 在设置 `localMsgId` 前序列化，持久化 payload 缺少 ID；即时 MQ 使用后续补写的事件对象，不能写成即时消息也缺少 ID
- `t_note_event` 缺失/写入异常被吞
- 多处 update/delete 忽略影响行数
- 评论回复目标二次查询可能 NPE
- Feed 补偿回调可能覆盖状态
- 文件 magic bytes 校验全量读取

### 业务逻辑
- 发布直接审核通过，审核枚举未形成真实状态机
- 已发布笔记更新不重新审核、不通知 Feed/ES
- 批量详情部分失败静默跳过
- 子评论查询边界不足
- 公开读只校验 status 不校验 auditStatus

### 分布式
- 本地消息缺 claim、版本/CAS 和完整状态机
- Redis/MySQL Feed 进度双写不原子
- 计数/通知/删除事件缺可靠补偿
- 延迟双删第一次失败无补偿

### 微服务
- User Feign 无明确 fallback/隔离
- content 依赖外部 Canal/ES 链路但边界未固化
- 直连 19002 时内部 Header 信任需要部署层保护

### 性能
- 批量详情逐条回源
- 热点详情无锁
- 评论相关子查询成本高
- 深分页
- 缺少匹配查询的联合索引
- 文件全量读入内存
- 同步 JSON 日志写入

### 工程
- 测试构造器与当前生产代码可能漂移
- Dockerfile 依赖外部基础镜像契约
- V1/V2 migration 依赖关系需明确
- 配置含固定 IP 和明文凭据

### 可观测性
- NoteEvent/MQ/补偿失败缺少统一指标
- Feed 滞留、死信、重复发送无完整指标
- 动态敏感词加载失败无指标
- 本地消息缺最后错误与尝试时间
