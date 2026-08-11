# 计数模块（my-xhs-counter）

> 来源：`my-xhs-counter` 源码直读。

## 一、业务边界
- 点赞数、收藏数、评论数、分享数等维度计数。
- **前端不直接调**；计数通过 home 聚合接口内嵌返回。

## 二、接口（内部/管理为主）
```
GET  /api/counter/get            (内部)
POST /api/counter/batch-get      (内部)
POST /api/counter/reconcile      (对账, 管理)
```

## 三、前端接入注意汇总
1. 计数在 `NoteCardVO`（likeCount/collectCount/commentCount）与 `NoteDetailAggVO` 中直接带好。
2. 点赞/收藏/评论等操作后，前端**重新拉详情**即可拿到最新计数（后端通过事件异步更新）。
3. 前端不要调 `/api/counter/**`。
