# my-xhs-content 测试重点

## 测试范围

本目录只记录 content 后续测试依据和执行入口，不替代源码分析。AI 不属于本轮范围。

## L1 业务

- 笔记发布、草稿保存、草稿发布、已发布更新、删除
- 笔记详情、批量详情、用户笔记列表、分享
- 评论创建、回复、一级评论、子评论、删除
- 敏感词命中、动态词库 reload
- 图片上传、文件类型、大小和资源访问

## L2 数据

- MySQL：`t_note`、`t_comment`、`t_local_message`、`t_note_event` schema 和状态
- Redis：详情缓存、评论计数、敏感词 Set/PubSub、Feed progress、补偿锁
- MQ：FEED、SOCIAL、NOTIFICATION 的 topic、消费、失败和重试
- ES/Canal：笔记发布/更新/删除索引同步，明确属于外部链路验证

## L3 质量

- 重复发布草稿、并发更新、并发删除
- 评论回复目标被删除/并发变化
- Feed 重试重复发送、状态回退、死信
- Redis/MQ/Feign 故障及降级
- 批量详情 N+1、深分页、相关子查询
- 大文件上传内存压力与多实例文件可见性

## L4 可观测性

- TraceId 跨 content、User Feign、MQ 消费链路
- NoteEvent/MQ 失败、补偿任务、敏感词 reload 失败指标
- 日志中的用户信息、文件路径和消息内容脱敏
- Prometheus、SkyWalking、ELK 证据

## 当前状态与阻断

- content 测试构造器已修复，9 个测试全部通过
- content 已重新打包并重启，19002 健康检查 `UP`
- 需要通过 `SHOW CREATE TABLE` 确认 `t_note_event` 和 `t_local_message` 实际结构
- 需要确认 15 个核心微服务已经启动后再做跨服务请求
- 测试必须按 L1 → L2 → L3 → L4 执行，不能用健康检查代替业务验证
- Feed/MQ/Canal/ES、DFA 动态词库、文件共享和真实并发测试仍未执行
