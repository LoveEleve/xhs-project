# my-xhs-search 测试用例矩阵（L1-L4）

## L1 业务

| ID | 用例 | 请求/数据 | 预期 | 状态 |
|---|---|---|---|---|
| S-L1-01 | 笔记搜索 | GET /api/search/note?keyword | ES 命中+高亮 | ✅ |
| S-L1-02 | 商品搜索 | GET /api/search/product?keyword | ES 命中+高亮 | ✅ |
| S-L1-03 | 搜索建议 | GET /api/search/suggest?prefix= | 建议词(ES completion) | ✅ prefix参数200，空索引返回空 |
| S-L1-04 | 搜索历史 | GET /api/search/history | 用户历史 | ✅ 200 |
| S-L1-05 | 热搜 | GET /api/search/hot | 热门词 | ✅ 200 |
| S-L1-06 | 热搜记录 | POST /api/search/hot/record?keyword= | 写入热搜 | ✅ query param 200 |
| S-L1-07 | 推荐 Feed | GET /api/recommend/feed | 个性化推荐 | ✅ 200 |

## L2 数据与索引

| ID | 验证点 | 证据 | 状态 |
|---|---|---|---|
| S-L2-01 | note_index 同步 | 发布笔记→ES 文档 | ✅ 4文档 |
| S-L2-02 | product_index 同步 | 商品→ES 文档 | ✅ 5文档 |
| S-L2-03 | 版本控制 | 乱序不覆盖新数据 | ⬜ |
| S-L2-04 | 增量补偿 Job | IncrementalIndexSyncJob | ✅ 商品=3 |
| S-L2-05 | 索引重建 | POST /index/rebuild | ✅ 重建17条 note9/product9 |

## L3 质量

| ID | 用例 | 预期 | 状态 |
|---|---|---|---|
| S-L3-01 | 脏 SPU 不阻塞批次 | 无 SKU SPU | 跳过其余正常索引 | ✅ 修复 |
| S-L3-02 | ES 版本防乱序 | 旧消息低版本 | 不覆盖 | ⬜ |
| S-L3-03 | 高亮/相关性 | 关键词命中 | 返回正确 | ✅ |
| S-L3-04 | 推荐行为落库 | behavior→RECOMMEND_BEHAVIOR_TOPIC→t_user_behavior | ✅ 修复归属库后落库 |
| S-L3-05 | LocalDateTime 索引 | createdAt 序列化 | 不抛异常 | ✅ 修复 |

## L4 可观测

| ID | 验证点 | 状态 |
|---|---|---|
| S-L4-01 | 索引失败/DLQ | ✅ 修复后无异常 |
| S-L4-02 | 搜索耗时 | ⬜ |
| S-L4-03 | TraceId 跨 MQ | ⬜ |

## 已实测
- S-L1-01/02、S-L2-01/02/04、S-L3-01/03/05 ✅（搜索/索引/脏数据/序列化修复）
- 建议/历史/热搜/推荐质量/索引重建需专项（行为数据量 + ES 数据）
