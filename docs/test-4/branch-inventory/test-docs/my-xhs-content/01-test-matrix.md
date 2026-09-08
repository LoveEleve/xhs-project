# my-xhs-content 测试用例矩阵（L1-L4）

## L1 业务

| ID | 用例 | 请求/数据 | 预期 | 状态 |
|---|---|---|---|---|
| CT-L1-01 | 发布笔记 | POST /api/note/publish | t_note + 本地消息 + Feed | ✅ |
| CT-L1-02 | 草稿保存/发布 | POST /draft, /{id}/publish | 草稿→发布 | ✅ 草稿保存成功 |
| CT-L1-03 | 笔记详情 | GET /note/detail/{id} | 详情+缓存 | ✅ |
| CT-L1-04 | 批量详情 | POST /batch-detail | 多笔记 | ✅ |
| CT-L1-05 | 用户笔记列表 | GET /note/user/{uid} | 分页 | ✅ |
| CT-L1-06 | 我的笔记 | GET /note/my | 本人笔记 | ✅ 200 |
| CT-L1-07 | 删除笔记 | DELETE /note/{id} | 软删+索引清理 | ✅ |
| CT-L1-08 | 分享 | POST /note/{id}/share | 已发布分享+计数 | ✅ 草稿20001拒/已发布200 |
| CT-L1-09 | 评论创建 | POST /api/comment | t_comment+计数 | ✅ |
| CT-L1-10 | 子评论/回复 | POST comment(parentId) | 层级 | ✅ 200+children列表 |
| CT-L1-11 | 评论列表/子列表 | GET /list,/children,/page | 分页 | ✅ |
| CT-L1-12 | 评论删除 | DELETE /comment/{id} | 软删deleted=1 | ✅ |
| CT-L1-13 | 评论计数 | GET /count/{noteId} | 计数 | ✅ |
| CT-L1-14 | 敏感词拦截 | 含敏感词发布 | 拒绝 | ⬜ |

## L2 数据

| ID | 验证点 | 证据 | 状态 |
|---|---|---|---|
| CT-L2-01 | t_note/comment 软删 | deleted 标志 | ✅ |
| CT-L2-02 | 本地消息表 | FEED_TOPIC outbox | ✅ |
| CT-L2-03 | 笔记详情缓存 | Redis | ✅ |
| CT-L2-04 | 索引同步 | NOTE_INDEX_TOPIC→ES | ✅ |
| CT-L2-05 | 评论计数 | counter countType=3 | ✅ |

## L3 质量

| ID | 用例 | 预期 | 状态 |
|---|---|---|---|
| CT-L3-01 | 发布事务原子 | 笔记+本地消息同事务 | ✅ 修复 |
| CT-L3-02 | 草稿重复发布 | 幂等 | ⬜ |
| CT-L3-03 | 评论回复目标已删 | 拒绝/容错 | ⬜ |
| CT-L3-04 | Feed 重试/死信 | 本地消息补发 | ✅ |
| CT-L3-05 | 敏感词动态加载 | reload | ⬜ |
| CT-L3-06 | 并发删除/更新 | 乐观锁 | ⬜ |

## L4 可观测

| ID | 验证点 | 状态 |
|---|---|---|
| CT-L4-01 | 发布/删除审计 | ✅ |
| CT-L4-02 | Feed 失败指标 | ⬜ |
| CT-L4-03 | TraceId 跨 content/MQ | ✅ |

## 已实测
- CT-L1-01/03/04/07/09/11/13、L2-01/02/03/04/05、L3-01/04 ✅
- 草稿/子评论/分享/用户列表/评论删除/敏感词/并发 待专项
