# 后端排查任务书：t_user_behavior.behavior_type 语义不一致

> 交给后端 AI 排查。目的：确认 my-xhs 现有代码里 `t_user_behavior.behavior_type` 的**真实语义定义**，以及历史数据是否被"错标"。这是 AI 诊断 Agent（A5 漏斗 / A3/A6 曝光互动）能信任该表的前提。
> 请逐项排查并回报，不要改代码。

---

## 一、已定位的矛盾（请确认）

**A. 代码侧枚举**：`my-xhs-search/.../dto/BehaviorRequest.java:20`
```
1=曝光 2=点击 3=点赞 4=收藏 5=评论 6=分享 7=停留
```
（第 24 行 `@Max(value=7)`；第 27 行注释：停留时长仅 behaviorType=7 有效）

**B. 建表注释**：`sql/mysql-user-init.sql`（`t_user_behavior` 表）
```
behavior_type TINYINT COMMENT '行为类型：1-浏览 2-点赞 3-收藏 4-评论 5-分享 6-搜索'
```
（第 78-92 行；第 89 行 `duration` 注释"仅浏览行为"）

**矛盾**：代码 `behavior_type=1` 定义为"曝光"，建表注释定义为"浏览"；且代码 7 个枚举 vs 建表 6 个枚举。

---

## 二、请后端 AI 排查并回答的问题

### Q1. 写库路径用的哪套枚举？
- 写点：`my-xhs-search/.../consumer/BehaviorReportConsumer.java:58-64`（INSERT t_user_behavior）
  → 传入的 `behavior_type` 值来自哪？是否直接用 `BehaviorRequest.behaviorType`（代码枚举）？
  → 确认：**实际写进库的 `behavior_type` 是按"代码枚举"（1=曝光）还是"建表注释"（1=浏览）？**

### Q2. 读库路径怎么解释这些值？
- 找到所有**读取/消费** `t_user_behavior.behavior_type` 的地方（如推荐/ItemCF/报表），列出 file:line。
  → 它们用哪套语义解释 `behavior_type`？与写库枚举是否一致？
  → 若写是"曝光"、读按"浏览"解释 → 是否有隐性 bug？

### Q3. 现有历史数据的真实语义？
- 数据库当前 `t_user_behavior` 里的 `behavior_type` 各值（1-7）分布如何？
  → 抽查几条：`behavior_type=1` 的记录，结合 `duration`（若 >0 说明是浏览/停留？）判断它是"曝光"还是"浏览"。
  → 结论：**现存数据到底以哪套语义为准？是否已"错标"？**

### Q4. 7 个 vs 6 个枚举，哪个是对的？
- 建表只有 6 个（无"停留"），代码有 7 个（含"停留"）。
  → `behavior_type=7`（停留）有没有实际写入/读取？会不会超出 TINYINT 范围或未定义语义？
  → 建表是否需要补枚举注释/补 7=停留？

### Q5. 推荐/曝光链路确认（A6 相关）
- `RecommendService.reportBehavior`（176 行）→ `RECOMMEND_BEHAVIOR_TOPIC` → `BehaviorReportConsumer`。
  → 这个"曝光"是**推荐流**曝光还是**关注流(Feed)**曝光？与 `FeedService.getFollowFeed`（关注流）是不是两套表面？

### Q6. 你建议的统一口径
- 结合以上，**推荐统一用哪套枚举**（建议保留代码的 7 值枚举并补建表注释），以及**如何对账/修正现存历史数据**（重映射 or 标注 or 清洗）。

---

## 三、请回报格式
- 每问：结论 + 证据(file:line) + 置信度(高/中/低)
- 末了给一份：`behavior_type` 最终应统一的枚举定义（含每值含义 + 是否与现有表/代码兼容）
- 明确标注：哪些是"确认"，哪些"需人工决策"
