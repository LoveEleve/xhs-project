# 02. 内容与社交域（my-xhs-content-social）业务逻辑

> 5 Controller | 34 端点 | X-User-Id Header | 10@RateLimit | 2@Idempotent | RocketMQ SOCIAL_TOPIC/FEED_TOPIC

## 一、业务定位
**平台内容支柱的核心**：笔记创作与发布、评论、点赞、收藏、关注，构成内容分发与社交关系网络，是平台的护城河。

## 二、核心状态机
```
草稿(0) ──发布/发草稿──→ 已发布(1) ──删除──→ 逻辑删除(2)
  草稿可含敏感词、字段可空；发布必须完整 + 过DFA敏感词
公开可见 = status=1 AND is_deleted=0
```

## 三、关键业务规则 / 不变量
1. **DFA 敏感词**：发布/评论前检测 title+content+images；命中即拒绝；**草稿不做检测**。
2. **游标分页**：评论/楼中楼用 `id < cursor` 而非 offset（offset 翻页会重复/漏数据）。
3. **限流与幂等**：发布 60s/5、评论 60s/10、点赞/收藏 30/min、关注 20/min；点赞/收藏 5s 幂等防重。
4. **计数异步**：10 类事件经 MQ → CounterEventConsumer 更新 like/collect/comment/share 计数；Redis SETNX 24h 幂等去重。
5. **Feed 收件箱**：发布同步扫粉丝写 `t_user_feed_inbox`；关注/取关时增减 `feed:timeline`。
6. **计数与 Feed 递送分离**：CounterEventConsumer 只计数，FeedConsumer 只递送。

## 四、异常路径
- 内容含敏感词 → 明确报错并列出命中词。
- 已发布笔记不能走"发草稿"流程。
- 计数消费者失败需 removeMark 后重试（否则重试窗口归零）。

## 五、对 AI 项目（指标/诊断）的价值
| 指标 | 来源 | 数据就绪度 |
|------|------|-----------|
| 日发布量、审核通过率、草稿转化率 | t_note | ⚠️ 缺独立 published_at/audited_at |
| 互动率（赞/藏/评/分享） | t_note 计数 + t_counter | ✅ |
| 粉丝/关注净增长 | t_follow | ⚠️ 现 Redis ZSet，非事件流水 |
| 热门话题 | t_topic | ✅ |
| 内容分发（Feed 曝光→互动） | feed_inbox + 行为 | ⚠️ 需漏斗数据 |

> **诊断价值最高域之一**：内容互动/增长/审核异常是全平台最该被 AI 服务的业务问题（PLAN V1 却未覆盖）。
