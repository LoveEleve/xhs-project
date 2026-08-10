# 笔记生命周期：状态机设计 + 流转校验 + 缓存一致性

> **源码**: NoteStatus(62行) + NoteService(400行生命周期方法)  
> **模式**: 有限状态机(FSM) + `canTransitTo()` 守卫  
> **关键修复**: 草稿发布通知 Feed(M2)、编辑已发布内容重做 DFA

---

## 1. 状态机总览

```
                    ┌─────────────┐
                    │   DRAFT(0)  │ ← saveDraft()
                    │    草稿      │
                    └──────┬──────┘
                           │ publishDraft() + DFA
                    ┌──────▼──────┐     ┌──────────────┐
                    │ PUBLISHED(2)│ ←── │ AUDITING(1)  │ (future)
                    │   已发布     │     │   审核中      │
                    └─────────────┘     └──────────────┘

所有状态的删除: deleteNote() → @TableLogic → SET deleted=1
（status 值不变，查询自动过滤 deleted=1 的行）
```

**四种状态**：草稿(0)、审核中(1)、已发布(2)、已下架(3)

---

## 2. NoteStatus 枚举：自校验的 FSM

```java
// NoteStatus.java:16-47
@Getter
@AllArgsConstructor
public enum NoteStatus {
    DRAFT(0, "草稿"),
    AUDITING(1, "审核中"),
    PUBLISHED(2, "已发布"),
    OFFLINE(3, "已下架");

    // 合法流转映射
    private static final Map<NoteStatus, Set<NoteStatus>> TRANSITIONS = Map.of(
            DRAFT,     Set.of(AUDITING, PUBLISHED),   // 草稿 → 审核中/直接发布
            AUDITING,  Set.of(PUBLISHED, OFFLINE),     // 审核中 → 通过/拒绝
            PUBLISHED, Set.of(OFFLINE),                 // 已发布 → 下架
            OFFLINE,   Set.of()                         // 终态，不可流转
    );

    public boolean canTransitTo(NoteStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }
}
```

### 为什么状态机需要在枚举里，而不是 Service 层 if-else？

```
Service 层 if-else: 每个方法里写 if (status != DRAFT) throw...
    → 新增状态时要改所有方法
    → 容易遗漏检查

枚举 FSM: 状态流转规则集中在 TRANSITIONS Map 里
    → 新增状态只改枚举
    → canTransitTo() 统一入口
```

**对比**：

```java
// ❌ Service 层分散校验
if (note.getStatus() != 0 && note.getStatus() != 2) throw...;

// ✅ 枚举层集中校验
NoteStatus current = NoteStatus.of(note.getStatus());
if (!current.canTransitTo(NoteStatus.PUBLISHED)) throw...;
```

---

## 3. 生命周期方法详解

### 3.1 saveDraft() — 创建草稿

```java
// NoteService.java:160-175
@Transactional
public Long saveDraft(Long userId, NotePublishRequest request) {
    Note note = buildNote(userId, request);
    note.setStatus(NoteStatus.DRAFT.getCode());        // 状态=0
    note.setAuditStatus(AuditStatus.PENDING.getCode()); // 审核=待审核
    noteMapper.insert(note);
    // ❌ 不触发 DFA 检测
    // ❌ 不触发 Feed 推送
    // ✅ 事务提交后延迟双删缓存
}
```

**为什么草稿不做 DFA 检测？**：草稿可能包含不完整句子，如"这个 #敏感词 应该..."。DFA 会误判——草稿应允许任何内容，只在发布时检测。

### 3.2 publishNote() — 直接发布

完整流程见文档 14（笔记发布全链路）。关键点：
- DFA 检测 ✅
- 本地消息表 ✅
- MQ 推送 ✅
- 状态直接设为 PUBLISHED(2) + APPROVED

### 3.3 publishDraft() — 草稿发布

```java
// NoteService.java:356-380
@Transactional
public void publishDraft(Long userId, Long noteId) {
    Note note = getAndCheckOwner(noteId, userId);

    // 1. 状态流转校验 — 只有草稿可以发布
    if (!NoteStatus.of(note.getStatus()).canTransitTo(NoteStatus.PUBLISHED))
        throw new BizException(ResultCode.NOTE_STATUS_ERROR);

    // 2. DFA 敏感词检测 — 草稿可能在保存后修改了内容
    checkSensitiveWords(note.getTitle(), note.getContent());

    // 3. 更新状态
    note.setStatus(NoteStatus.PUBLISHED.getCode());
    note.setAuditStatus(AuditStatus.APPROVED.getCode());

    // 4. 写本地消息表（与笔记更新同事务）
    LocalMessage localMsg = new LocalMessage();
    localMsg.setTopic("FEED_TOPIC");
    localMsg.setBody(toJson(event));
    localMsg.setStatus(0);
    localMessageMapper.insert(localMsg);

    // 5. afterCommit → asyncSend MQ
}
```

**与 publishNote() 的区别**：

| | publishNote() | publishDraft() |
|---|---|---|
| 入口 | POST /publish | POST /{id}/publish |
| 状态变更 | INSERT 新记录 | UPDATE 已有记录 |
| DFA 检测 | 新标题+新正文 | 已有标题+正文 |
| 本地消息表 | ✅ | ✅ (M2修复) |

### 3.4 updateNote() — 编辑笔记

```java
// NoteService.java:190-244
@Transactional
public void updateNote(Long userId, Long noteId, NoteUpdateRequest request) {
    Note note = getAndCheckOwner(noteId, userId);

    // 状态校验：仅草稿和已发布可编辑
    NoteStatus current = NoteStatus.of(note.getStatus());
    if (current != NoteStatus.DRAFT && current != NoteStatus.PUBLISHED)
        throw new BizException(ResultCode.NOTE_STATUS_ERROR);

    // ★ 已发布笔记编辑内容时，重做 DFA 检测
    if (current == NoteStatus.PUBLISHED)
        checkSensitiveWords(newTitle, newContent);

    // 部分更新（非空字段才更新）
    if (hasText(request.getTitle())) note.setTitle(request.getTitle());
    if (request.getContent() != null) note.setContent(request.getContent());
    // ... images, videoUrl, coverUrl, topicIds, tags

    noteMapper.updateById(note);
    // afterCommit → 延迟双删缓存
}
```

**关键设计：已发布笔记编辑后为什么重做 DFA？**

用户可以在自己的笔记编辑器中修改内容（新增敏感词），如果不重做 DFA，攻击者可以：
1. 发布一篇安全笔记
2. 编辑笔记，添加违规内容
3. 绕过 DFA（因为编辑时不做检测）

### 3.5 deleteNote() — 删除笔记

```java
// NoteService.java:252-270
@Transactional
public void deleteNote(Long userId, Long noteId) {
    Note note = getAndCheckOwner(noteId, userId);

    // 逻辑删除（status 变为 OFFLINE）
    noteMapper.deleteById(noteId);  // MyBatis-Plus 逻辑删除(@TableLogic)

    // afterCommit → 延迟双删缓存
}
```

**逻辑删除 vs 物理删除**：

| | 逻辑删除 (当前) | 物理删除 |
|---|---|---|
| 用户看到 | "笔记已删除" | 404 |
| 数据库 | status=OFFLINE, 数据保留 | 行被移除 |
| 恢复 | 可能需要 | 不可能 |
| 数据统计 | 不统计删除的 | N/A |

---

## 4. 状态流转表

| 当前状态 | 可流转到 | 触发方法 | DFA | MQ | 删除路径 |
|:--:|------|------|:--:|:--:|------|
| DRAFT(0) | AUDITING(1) | (future) | — | — | `deleted=1` |
| DRAFT(0) | PUBLISHED(2) | publishDraft() | ✅ | ✅ | `deleted=1` |
| AUDITING(1) | PUBLISHED(2) | (future) | — | — | `deleted=1` |
| AUDITING(1) | OFFLINE(3) | (future, rejected) | — | — | `deleted=1` |
| PUBLISHED(2) | — | deleteNote() | ❌ | ❌ | `deleted=1` |
| OFFLINE(3) | 终态（枚举定义，代码中未使用） | — | — | — | — |

### 删除路径：逻辑删除，绕过 FSM

`deleteNote()` 不走状态机——它用 MyBatis-Plus 的 `@TableLogic`（定义在 `BaseEntity` 中），将 `deleted` 列设为 1：

```java
// NoteService.java:252-258
public void deleteNote(Long userId, Long noteId) {
    Note note = getAndCheckOwner(noteId, userId);
    noteMapper.deleteById(noteId);  // → SET deleted=1 (逻辑删除), status 不变
}
```

`BaseEntity.java:35-36`:
```java
@TableLogic
private Integer deleted;
```

**这意味着**：删除后笔记的 `status` 值保留原值（DRAFT/PUBLISHED/...），只是 `deleted=1` 让 MyBatis-Plus 自动在 WHERE 子句中追加 `AND deleted=0`。查询时自动过滤掉已删除笔记。

**为什么用 `@TableLogic` 而不是 `status=OFFLINE`？**

- `@TableLogic`：MyBatis-Plus 自动处理，所有 SELECT 自动附加 `deleted=0`，无需修改业务代码
- `status=OFFLINE`：需要在每个查询中手动添加 `WHERE status != 3`，容易遗漏

**代价**：`OFFLINE` 枚举状态目前**未被代码使用**（只是 Enum 中定义了合法流转）。这是预留的——如果未来需要区分 "作者删除" vs "管理员下架"，可以在 status 中实现。

---

## 5. 缓存一致性

所有生命周期方法的缓存策略一致：

```java
TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
    @Override
    public void afterCommit() {
        // 1. 删除笔记详情缓存
        cacheHelper.delayDoubleDelete(RedisKeyConstants.NOTE_DETAIL + noteId);
        // 2. 删除用户笔记列表缓存
        cacheHelper.delayDoubleDelete(RedisKeyConstants.NOTE_LIST_USER + userId);
    }
});
```

**为什么要延迟双删？** 见文档 14 §5（缓存策略章节）。

---

## 6. 权限控制

所有写操作通过 `getAndCheckOwner()` 校验作者身份：

```java
private Note getAndCheckOwner(Long noteId, Long userId) {
    Note note = noteMapper.selectById(noteId);
    if (note == null) throw new BizException(ResultCode.NOTE_NOT_FOUND);
    if (!note.getUserId().equals(userId))
        throw new BizException(ResultCode.FORBIDDEN, "无权操作该笔记");
    return note;
}
```

**为什么不在 Controller 层校验？** Controller 只知道 userId（Header 注入），不知道笔记作者是谁。Service 层需要查 DB 才能判断。

---

## 7. 知识点索引

| 知识点 | 源码 | 行号 |
|--------|------|------|
| NoteStatus 枚举 + TRANSITIONS | `NoteStatus.java` | 16-47 |
| canTransitTo 守卫 | `NoteStatus.java` | 47-49 |
| saveDraft | `NoteService.java` | 160-175 |
| publishNote | `NoteService.java` | 81-150 |
| publishDraft + M2修复 | `NoteService.java` | 356-385 |
| updateNote + 已发布重做DFA | `NoteService.java` | 190-244 |
| deleteNote | `NoteService.java` | 252-270 |
| getAndCheckOwner 权限 | `NoteService.java` | (私有方法) |

---

*02-content 模块文档全部完成。下一篇：03-analytics 模块。*
