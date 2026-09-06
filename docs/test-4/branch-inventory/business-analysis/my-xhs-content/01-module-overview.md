# my-xhs-content 模块总览

## 1. 当前模块定位

`my-xhs-content` 是内容域核心微服务，负责笔记、评论、敏感词和内容事件，是 G2 内容/社交链路的核心写入端，也是 Feed、通知、计数、搜索索引等下游链路的事件来源。

核心职责：
- 笔记发布、草稿、更新、详情、列表、删除、分享和图片上传
- 评论创建、删除、一级评论/子评论查询
- DFA 敏感词检测与动态词库 reload
- 本地消息表和 Feed 投递补偿
- 浏览、分享、评论、删除等计数事件发送
- 评论通知事件发送
- User Feign 读取用户展示信息

## 2. 业务位置

```text
客户端
  -> Gateway
  -> content
       -> MySQL: note/comment/local_message/note_event
       -> Redis: note cache/comment count/feed progress/sensitive words
       -> RocketMQ: FEED/SOCIAL/NOTIFICATION
       -> User Feign: 用户昵称/资料
  -> Feed/Counter/Notification/Search 等下游
```

## 3. 关键业务分组

- G2：笔记、评论及内容相关事件；点赞/收藏由其他业务模块处理，本服务没有对应 Controller/Service
- G6：内容数据最终进入搜索、首页、推荐
- G7：评论/社交事件最终进入通知与计数

## 4. 当前重要事实

- 发布代码当前直接设置 `status=PUBLISHED`、`auditStatus=APPROVED`，没有真正的人工审核状态机
- Feed 发布有本地消息和补偿设计，但计数/通知/删除等多数事件没有同等可靠性保障
- 内容服务当前源码没有 Canal Client 或 Elasticsearch Client，索引同步属于外部部署链路
- 文件上传为本地磁盘资源，不能天然支持多实例共享
