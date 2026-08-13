# D3 状态（RAG 与上下文）

> 版本：2026-08-10 | 状态：**核心交付完成（检索三路 + 回答闭环 + 拒答），剩余见 §六**
> 定位：D3「RAG 与上下文工程」进度记录。

---

## 一、已完成

### 1. RAG 第一切片（BM25 基线）✅
- `RagKnowledgeService`（ES REST，零新依赖）+ `MetricDictionaryIngester`（指标字典入库）+ `RagController`。
- 环境：ES 21.130.247.89:19200（elastic 认证，8.19.19）；TeamoRouter 无 embedding → 先 BM25。

### 2. dense 检索（Agent Plan embedding）✅
- **Agent Plan key 必须用专属 URL** `https://ark.cn-beijing.volces.com/api/plan/v3`（含 `/plan`）。
- `doubao-embedding-vision-large`（2048 维）可用；索引 dense_vector + kNN cosine。
- 实测：语义检索"数据库慢查询排查"→ 慢查询数命中（无词面全匹配也命中）。

### 3. 混合检索（BM25 + dense + RRF）✅
- `searchHybrid`：双路 top 3N + RRF（k=60，并列按 docId 决胜）。
- **健壮性**：dense 失败降级 BM25（WARN，不 500）；RagController 统一 error JSON。
- 实测：词面+语义查询 hybrid 正确第一；纯语义查询 dense 单路更准（RRF 局限，rerank 是方向）。

### 4. RAG 回答闭环 ✅
- `RagAnswerService`：hybrid 检索 top3 → 带来源上下文 → 模型回答 + 引用。
- **拒答 Gate**：检索无命中 → "知识库中未检索到相关内容"（不编造）。
- 实测：口径问答带 `[来源: metric-dictionary.md#A2]` ✅；知识库外问题拒答 ✅。

---

## 二、踩坑记录（写码不踩）
| 坑 | 解决 |
|----|------|
| ES 401 | elastic 凭据（env MYXHS_ES_PASS）|
| Agent Plan key 在普通 URL 认证失败 | 必须用 `/api/plan/v3` 专属 URL |
| 多数 embedding 不支持 plan | `doubao-embedding-vision-large`（2048 维）|
| dense_vector similarity 写成对象 | 应为字符串 `"cosine"` |
| embedding 用 `put` 写成字符串 | `valueToTree(float[])` → 数组 |
| bulk 200 但逐条失败 | 解析 items 校验 per-item 错误 |
| ingester 解析质量差（表头/B面/分隔行错抓）| 表头识别指标列 + 两级标题 + 噪音过滤 |

---

## 三、深度 Review 记录（两轮，均已闭环）

### 第一轮：入库质量（2026-08-10）
- 初版 25 条里 ~14 条噪音（表头行/`B1`×2 section 错标/`#`/`:--:`）→ 重写解析器：**表头行识别指标列**（B 面表=场景列后一列）、支持 `##`+`###`、分隔行/纯数字过滤。
- 验证：入库 **19 条全为有效指标**；`MetricDictionaryIngesterTest` 3 用例。

### 第二轮：混合检索（2026-08-10）
| 问题 | 修复 |
|------|------|
| embedding 失败时 hybrid 500 | dense try-catch → 降级 BM25（实测坏 key 不 500）|
| RRF 并列分排序不确定 | 并列按 docId 决胜（确定性）|
| RagController 无错误包装 | `safe()` → error JSON |
| RRF 无测试 | 抽出 `rrfFuse()` + `RrfFusionTest` 4 用例 |

---

## 四、D3 Gate 核对（PLAN v6）
| Gate | 状态 |
|------|:--:|
| 口径可点击回原文（引用机制）| ✅（路径+章节；UI 层链接化待做）|
| 更换 embedding 模型全量重嵌入（ingest 删建全量）| ✅ |
| 检索不到可信内容时拒答/声明不确定 | ✅ |

## 五、测试
- **47/47**（tools 13 + app 28 + mcp 6）；RAG 相关：MetricDictionaryIngesterTest 3 + RrfFusionTest 4。

## 六、剩余（如实）
| # | 项 | 归属 |
|:--:|----|------|
| 1 | **rerank 精排**（解决 RRF 噪音并列）| D3/D4 |
| 2 | **检索评测基线**（recall@k/MRR：bm25 vs dense vs hybrid）| D3 评测 |
| 3 | 知识库扩展（Runbook/Schema，不止指标字典）| D3 |
| 4 | ACL-aware 检索（按角色过滤）| D2 权限模型 |
| 5 | 索引/文档版本化 | D3 计划 |
| 6 | UI 引用链接化（回原文点击）| D7/UI |
| 7 | 语义缓存（高频查询省 embedding 调用）| D6 |

## 七、下一步建议
- **D4（受限诊断 Agent）**：三能力面主线——Agent 用上 RAG（口径问答）+ MCP 工具（指标/排障）
- 或先补 **检索评测基线**
