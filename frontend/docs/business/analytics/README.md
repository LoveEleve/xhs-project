# 社交底层（my-xhs-analytics）

> 来源：`my-xhs-analytics` 源码直读。

## 一、业务边界
- 点赞、收藏、关注三类社交关系的底层存储与计数（Redis Set/ZSet + 异步落库）。
- 对外即 `/api/social/**`，业务细节见 [content-social](../content-social/README.md)。

## 二、与前端的关系
- 前端所有点赞/收藏/关注操作都打 `/api/social/**`（见 content-social 模块）。
- analytics 会异步消费事件更新计数并可能触发通知。

## 三、前端接入注意汇总
1. 点赞/收藏的 `bizType`：1=笔记，2=评论。
2. 收藏请求体是 `{noteId}`（不是 bizId/bizType）。
3. 关注/粉丝/关系列表的返回结构与分页对象不同（见 content-social）。
4. 所有社交写操作需登录。
