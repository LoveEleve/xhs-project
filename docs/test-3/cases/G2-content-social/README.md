# G2 内容与社交 — 组总览

> 服务：content(19002) + analytics(19003) | 入口：**gateway(19000)**
> 依赖：G1 登录（token/hmacSecret）
> 时间引用：矩阵 #2（FeedMessageRetryJob 30s/60s）、#23/24（缓存）、#32（HotSku）

## 业务范围
笔记 CRUD（草稿/发布/删除/详情/**批量详情 P2-3**/列表/分享/图片上传）+ 评论（创建/列表/子评论/计数）+ 社交（**点赞/收藏** + 计数联动）+ **Feed 推送链路**（发布→本地消息表→MQ→home 收件箱）+ **canal→ES 索引同步**

## 数据关注矩阵（代码实证 2026-08-13）
| 用例域 | Redis key | MySQL | MQ |
|---|---|---|---|
| 笔记详情 | `myxhs:note:detail:{id}`（缓存+空值 2min）| `my_xhs_content.t_note` | — |
| 发布 Feed | 本地消息表锁 `myxhs:lock:feed:retry:*` | `t_local_message_*`（content 库）| **FEED_TOPIC** |
| 用户笔记列表 | `myxhs:note:list:user:{uid}` | t_note | — |
| 评论 | `myxhs:comment:list:{noteId}`、`comment:count:{noteId}` | `t_comment` | — |
| 点赞 | `myxhs:like:{targetType}:{targetId}`（Set）| `my_xhs_analytics.t_like` | SOCIAL_TOPIC:LIKE |
| 收藏 | `myxhs:favorite:{userId}`（Set）| `t_favorite` | SOCIAL_TOPIC:FAVORITE |
| ES 索引 | canal 版本 key | t_note binlog | **NOTE_INDEX_TOPIC** |

## 归属定时/联动任务
- FeedMessageRetryJob（content，30s/60s，本地消息补发）
- Feed 消费端 home FeedPushConsumer（收件箱 ZSet）
- **canal→ES**：note_instance → NOTE_INDEX_TOPIC → search NoteIndexSyncConsumer（L2：发笔记后 ES 可查，等 1-2s）
- HotSearchService（60s）、HotSkuDetector（30s）

## 用例文件
- **G2-01-note.md**：笔记 CRUD + 发布 Feed 链路 + P2-3 批量详情 + 图片上传
- **G2-02-comment.md**：评论创建/列表/子评论/计数 + 通知
- **G2-03-like-favorite.md**：点赞/收藏/取消 + 计数 + MQ + 幂等

## 人工观察点（🔍）
| 用例后 | 控制台 | 看什么 |
|---|---|---|
| 发笔记 | RocketMQ | FEED_TOPIC 消息投递 |
| 发笔记 | ES (Kibana) | 搜索能查到（canal→ES 链路）|
| 点赞/收藏 | SkyWalking | 链路 analytics→Redis→MQ→counter |
| 全流程 | Grafana | feed.push.total、mq_consume_total 指标 |

## 执行记录（2026-08-15 全量回归 43/43）
| 文档 | 结果 | 问题 |
|---|---|---|
| G2-01-note.md | 15 ✅ | T-037 修复确认（>5MB→40002）；T-100/101/102/104 运行态确认；firstImage 实证 |
| G2-02-comment.md | 13 ✅ | **T-114 发现修复（favorite actionTime）**；T-115 文档差异（unlike 有 @Idempotent）；T-116 观察（favorite 静默假成功）|
| G2-03-like-favorite.md | 15 ✅ | T-114 修复验证（UNFAVORITE deleted=1）；T-117 观察（重启竞态通知多计）；一级评论数修正（11 非 12）|

> 全部小问题登记 ISSUES.md T-114~117；本轮回测数据已清理（见清理清单）
