# 异步落库链路三处对照专项（2026-08-15）

> 承接 G2/G3 回归后的收敛建议：对全部 MQ 异步落库链路做 Redis/DB/权威源三处对照断言。
> 用户：gv1_=2088591507507412993 / gv2_=2088591508161724417（已清理）

## 验证矩阵（全部 ✅）

| 链路 | Redis | DB | 权威源 | 结果 |
|---|---|---|---|---|
| like 增 | counter:1:{n}:1=1 | t_counter(1,n,1)=1 | like:note SCARD=1 | ✅ |
| like 减 | =0 | =0 | SCARD=0 + t_like=0 | ✅ |
| favorite 增/减（T-114 回归 3 轮）| counter=0/ZSet=0 | t_counter=0/t_favorite=0 | 每轮 UNFAVORITE deleted=1 | ✅ |
| comment 增/减（级联）| counter:1:{n}:3=3→1 | t_counter=3→1 | t_comment=3→1 | ✅ |
| share/view | counter 4=1/5=2 | t_counter 同 | — | ✅ |
| follow 关注 | fans/list ZSet=1 | t_counter(2,uid,6/7)=1 | t_follow=1 | ✅ |
| follow 取关 | ZSet=0 | =0 | t_follow=0 | ✅ |
| notification | unread=3 | 3 行聚合（评论3/点赞2/关注2）| agg key 对应 | ✅ |
| counter 对账（T-113 回归）| 漂移后 Redis=1 | DB=999→修复=1 | like:set SCARD=1 | ✅ |

## T-118 发现与修复

- **缺陷**：NoteIndexSyncConsumer 对物理 DELETE 事件也只写 status=-1 标记（不删 ES 文档）→ G2 清理物理删的 15 篇笔记在 ES 永久残留（全量重建只 upsert 不删多余、无 DeleteByQuery）
- **修复**：`physicallyDeleteNote()`（esClient.delete）+ DELETE 分支改用；逻辑删除保留标记（T-042 语义）
- **验证**：发布→物理删→ES found=false ✅；逻辑删→status=-1 ✅；逻辑删后再物理删→ES 删除 ✅
- search 已打包重启（**坑：restart-service.sh 超时 120s 会杀掉启动中的 search**——169MB jar 需更长 timeout）

## 清理（已完成）
- MySQL/ES/Redis 全部归零（t_counter 保留历史测试计数行=持久化层正常产物）
- ISSUES.md 登记 T-118
