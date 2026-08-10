# 15 内容与 Feed

> 复审维度 15 | 覆盖模块：02-content, 12-home | 领域专属检查项
>
> 你是谁：分布式系统审查员。不凭记忆，逐条执行以下指令。本维度覆盖内容发布/Feed流推送/审核/级联删除等独有的问题域。
> 通用规则：MQ可靠性见 04、缓存见 05、输入校验见 01。

---


**执行本维度后，必须在审查报告中输出 `[15] 15 内容与 Feed：发现 N 项`。缺失此标题 = 本维度未被执行。执行每条检查项时，标注来源维度编号（如 [15]）。**
## 检查项

### 15.1 DFA 敏感词过滤 | 透镜：业务/工程/盲区

**必须检查**：敏感词过滤的初始化是否可靠、过滤失败是否静默放行、匹配模式是否可更新。

**怎么查**：
```bash
grep -rn 'DFA\|SensitiveWord\|sensitive\|dirtyWord\|blacklist\|filter' my-xhs-content/src/main/java/com/myxhs/content/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 过滤异常静默放行 | DFA 词库加载失败→过滤返回 `true`（通过）→所有敏感词绕过 |
| 词库不可热更新 | 词库写死在代码→新增敏感词需重启 |
| 仅过滤中文漏英文 | 中文敏感词 "sb" 匹配不到 "s_b" / "s b"→被空格绕过 |
| 无过滤失败监控 | DFA 异常无 metric→敏感词已经绕过团队不知道 |

**案例**：02-content `DFAFilter` 异常时静默返回 true→敏感词全线放行（修复加 metrics 告警 + fail-closed 逻辑：异常→标记待人工审核非直接放行）。

---

### 15.2 Feed 推拉模式正确性 | 透镜：微服务/性能

**必须检查**：Feed 流是推模式（用户发帖→推给粉丝 TimeLine）还是拉模式（用户刷新→拉关注者帖子）。推拉的失效和补偿是否完备。

**怎么查**：
```bash
grep -rn 'timeline\|feed\|pushFeed\|pullFeed\|fanout\|broadcast.*feed' my-xhs-content/src/main/java/ my-xhs-home/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 推模式漏人 | 用户发帖→推给 10000 粉丝→推过程中死掉→后面的粉丝看不到 |
| 拉模式性能 | 用户 5000 关注→刷新拉 5000 人最新帖→聚合慢 |
| 热门用户双模式无切换 | 粉丝 >10000 仍用推模式→发一条帖 10000 次 MQ→风暴 |
| Feed 和 Timeline 不一致 | 同一用户的 Feed 和 Timeline 数据不同源→刷 Feed 看到的数据和 Timeline 不同 |

**案例**：`sendNoteFeedPush` 大 V 发帖逐条推送给粉丝→10000 粉丝 = 10000 MQ 消息→需大 V 自动切拉模式（热门用户的粉丝列表不触发推，读时聚合拉取）。

---

### 15.3 断点续推与补偿 | 透镜：工程/生产级

**必须检查**：推 Feed 过程中断后是否有断点续推机制——记录推到哪里、从断点继续。

**怎么查**：
```bash
grep -rn 'pushCursor\|pushTotal\|pushProgress\|compensateIncompletePush\|continuePush\|resumePush' my-xhs-content/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 无断点记录 | 推到第 5000 个粉丝死掉→重新从头推→前 5000 重复 |
| cursor 非原子更新 | 推了一个后在 catch 中 update cursor→但如果整个 JVM crash→cursor 未更新 |
| 补偿无去重 | 断点续推 + 补偿双路径→同一个粉丝收到两次推送 |

**案例**：`compensateIncompletePush` 多处散落未统一→02-content 修复统一 asyncSend + cursor 记录。

---

### 15.4 级联删除完整 | 透镜：业务/分布式

**必须检查**：删除笔记→是否级联删除了评论/点赞/收藏/Feed 推送记录。

**怎么查**：
```bash
grep -rn 'deleteNote\|removeNote\|delete.*Note' my-xhs-content/src/main/java/ -A10
```
画级联图：每个关联数据是否有删除动作。

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 漏删评论 | 删笔记→笔记删除→评论 Consumer 还在读已删笔记 npe→但评论没删 |
| 漏删点赞 | 笔记删除→点赞 Set 残留→计数器永远不会降 |
| 删除顺序错误 | 先删笔记后发 MQ→MQ Consumer 读笔记 null→评论 Consumer 报错不删 |

**案例**：`deleteNote` 不统一发 deleteCommentEvent→评论成孤儿（修复统一 `compensateIncompletePush` 补全级联）。

---

### 15.5 内容审核流 | 透镜：业务/工程

**必须检查**：内容发布后是否经过审核流程——机审（DFA/图片检测）→人审→发布→可编辑。

**怎么查**：
```bash
grep -rn 'audit\|review\|auditing\|verify\|publishDraft\|draft.*to.*publish\|审核' my-xhs-content/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 跳过审核 | `publishDraft` 允许 AUDITING→PUBLISHED→审核中笔记可直接发布 |
| 驳回无理由 | 审核驳回不返回原因→作者不知道改什么→体验差 |
| 无 AI 审核 | 纯人工审核→审核高峰延迟→积压 |

**案例**：`publishDraft` 曾允许 AUDITING→PUBLISHED 跳过审核，修复加 `canTransitTo(PUBLISHED)` 状态机检查（`NoteService.java:392`）。

---

### 15.6 富文本与多媒体安全 | 透镜：工程/安全

**必须检查**：用户上传的图片/视频是否有内容安全审核；富文本中是否有 XSS 脚本注入。

**怎么查**：
```bash
grep -rn 'HtmlUtils\|Jsoup\|StringEscapeUtils\|clean\|sanitize\|upload.*image\|upload.*video' my-xhs-content/src/main/java/
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| 图片无审核 | 上传色情/暴力图片→直接发布→合规风险 |
| 富文本未转义 | 用户输入 `<script>alert(1)</script>`→写入 DB→展示时执行→XSS |
| 视频无转码 | 上传任意编码的视频→其他用户无法播放 |

**案例**：（全特性面预置检查项——02-content 的富文本转义和图片审核机制需逐路径审计。）

---

## 跨维度引用

| 相关概念 | 主维度 | 说明 |
|---------|:--:|------|
| 状态机完整性 | 01.7 | AUDITING→PUBLISHED 状态跳转 |
| SQL注入/XSS | 01.15 | 富文本 HTML 注入 |
| 级联MQ可靠性 | 04 | deleteNote 的 MQ 发送 |
| 文件上传 | 01.10 | 图片/视频大小类型校验 |

---

## 验证命令汇总

```bash
mvn compile test-compile -pl my-xhs-content,my-xhs-home -am
mvn test -pl my-xhs-content,my-xhs-home

# DFA 敏感词
grep -rn 'DFA\|SensitiveWord\|sensitive\|filter' my-xhs-content/src/main/java/com/myxhs/content/

# Feed 推拉
grep -rn 'timeline\|feed\|pushFeed\|pullFeed\|fanout' my-xhs-content/src/main/java/ my-xhs-home/src/main/java/

# 断点续推
grep -rn 'pushCursor\|pushProgress\|compensateIncompletePush\|continuePush' my-xhs-content/src/main/java/

# 级联删除
grep -rn 'deleteNote\|removeNote\|delete.*Note' my-xhs-content/src/main/java/ -A10

# 审核流
grep -rn 'audit\|review\|auditing\|publishDraft\|draft' my-xhs-content/src/main/java/

# 富文本安全
grep -rn 'HtmlUtils\|Jsoup\|StringEscapeUtils\|sanitize' my-xhs-content/src/main/java/
```
