# 笔记编辑与删除：部分更新、逻辑删除、归属校验

> `NoteService.updateNote()`（`NoteService.java:190-244`）+ `NoteService.deleteNote()`（`NoteService.java:251-270`）
> 前置阅读：`04-transaction-aftercommit/`（事务生命周期）、`06-cache-strategy/`（延迟双删）、`03-dfa/`（敏感词检测）

---

## 1. 编辑笔记 — `updateNote()`

### 1.1 完整流程

```java
@Transactional(rollbackFor = Exception.class)
public void updateNote(Long userId, Long noteId, NoteUpdateRequest request) {
    // ❶ 归属校验
    Note note = getAndCheckOwner(noteId, userId);

    // ❷ 状态校验 — 仅 DRAFT 和 PUBLISHED 可编辑
    NoteStatus currentStatus = NoteStatus.of(note.getStatus());
    if (currentStatus != DRAFT && currentStatus != PUBLISHED) {
        throw new BizException(NOTE_STATUS_ERROR, "当前状态不允许编辑");
    }

    // ❸ 已发布笔记编辑 → 重新 DFA
    if (currentStatus == PUBLISHED) {
        // newTitle/newContent: 如果 request 没传则用 DB 中的旧值
        String newTitle = hasText(request.getTitle()) ? request.getTitle() : note.getTitle();
        String newContent = request.getContent() != null ? request.getContent() : note.getContent();
        checkSensitiveWords(newTitle, newContent);
    }

    // ❹ 只更新非 null 字段
    if (hasText(request.getTitle()))     note.setTitle(request.getTitle());
    if (request.getContent() != null)    note.setContent(request.getContent());
    if (request.getImages() != null)     note.setImages(toJson(request.getImages()));
    // ... 7 个字段逐一判断

    noteMapper.updateById(note);  // 全量 UPDATE

    // ❺ afterCommit: 清除详情 + 列表缓存
    afterCommit() {
        delayDoubleDelete(NOTE_DETAIL + noteId);
        delayDoubleDelete(NOTE_LIST_USER + userId);
    }
}
```

### 1.2 部分更新模式 — 为什么用"全量 UPDATE + 只更新非 null 字段"？

MySQL 的 `UPDATE` 本质上是全量操作——不能只更新某几个列而其他列保持不变（除非用 `SET col = IFNULL(?, col)` 的 trick）。

这个项目用了最直接的方式：

```java
// 步骤 1: 从 DB 查询完整的 Note 对象（包含所有旧值）
Note note = noteMapper.selectById(noteId);

// 步骤 2: 只覆盖 request 中非 null 的字段
if (request.getTitle() != null) note.setTitle(newValue);   // 覆盖
// 没传的字段 → note 对象中保持 selectById 查出来的旧值

// 步骤 3: 全量 UPDATE（MyBatis-Plus 生成所有列的 SET 语句）
noteMapper.updateById(note);
```

**优缺点**：

| 优点 | 缺点 |
|------|------|
| 代码简单，不需要拼动态 SQL | 每个 UPDATE 都要先 SELECT，多一次 DB 查询 |
| `selectById` 拿到的是最新版本，天然乐观 | 不支持 `SET count = count + 1` 这种增量更新 |
| 不需要处理"传了空字符串 vs 没传"的边界 | `request.getContent()` 为 `null` 时不更新——意味着前端**不能**通过传 `null` 来清空正文 |

**边界陷阱**：如果前端想清空 `content`（比如把正文删掉），传 `"content": null` 不会生效。需要传 `"content": ""`（空字符串）。但 `NoteUpdateRequest` 上没有 `@NotBlank` 约束空字符串，所以 `""` 会被写入 DB。

### 1.3 为什么已发布笔记需要重新 DFA？

草稿发布时做过 DFA 了，但用户可能在**编辑已发布笔记**时加入敏感词：

```
之前: "今天天气真好"         → 发布时 DFA 通过
编辑: "今天天气真好+赌博平台"  → 必须重新检测
```

如果编辑时不做 DFA，用户可以绕过敏感词过滤——先发一篇正常笔记，通过了再编辑成违规内容。

### 1.4 草稿编辑不需要 DFA 的原因

草稿本身不对公众可见，且发布草稿时 `publishDraft()` 会补做 DFA。所以编辑草稿时不做 DFA 是安全的——最终防线在发布环节。

---

## 2. 删除笔记 — `deleteNote()`

### 2.1 完整流程

```java
@Transactional(rollbackFor = Exception.class)
public void deleteNote(Long userId, Long noteId) {
    Note note = getAndCheckOwner(noteId, userId);
    noteMapper.deleteById(noteId);  // ← 不是物理 DELETE，是逻辑删除

    afterCommit() {
        delayDoubleDelete(NOTE_DETAIL + noteId);
        delayDoubleDelete(NOTE_LIST_USER + userId);
    }
}
```

### 2.2 @TableLogic — MyBatis-Plus 的逻辑删除

```java
// BaseEntity.java:35
@TableLogic
private Integer deleted;  // 0=未删除, 1=已删除
```

`@TableLogic` 拦截了所有 CRUD 操作：

| 操作 | 原始 SQL | @TableLogic 改写后 |
|------|---------|-------------------|
| `deleteById(id)` | `DELETE FROM t_note WHERE id=?` | `UPDATE t_note SET deleted=1 WHERE id=? AND deleted=0` |
| `selectById(id)` | `SELECT * FROM t_note WHERE id=?` | `SELECT * FROM t_note WHERE id=? AND deleted=0` |
| `selectList(wrapper)` | `SELECT * FROM t_note WHERE ...` | `SELECT * FROM t_note WHERE ... AND deleted=0` |

**效果**：对上层代码透明——`noteMapper.selectById(deletedId)` 返回 `null`，就像记录真的不存在一样。

### 2.3 什么时候需要真正物理删除？

逻辑删除的缺点：表不断增长，`deleted=1` 的记录永久占用空间。

**这个项目没做物理删除**——`deleted=1` 的记录需要运维定期清理。生产环境的常见方案：

```
凌晨 3 点定时任务：
  DELETE FROM t_note WHERE deleted=1 AND updated_at < now() - 30 DAY
```

或者归档到历史表后再物理删除。

### 2.4 删除后缓存行为 — 正确的 NULL_PLACEHOLDER

```
deleteNote(id)
  → afterCommit: 清除 NOTE_DETAIL 缓存
  → 后续 GET /api/note/detail/id
    → Cache Aside: miss → 查 DB → @TableLogic 过滤 → null
    → 缓存 NULL_PLACEHOLDER (TTL=2min)
    → 后续查询命中空值 → 不查 DB
```

这不是 bug——删除后返回"不存在"是正确的。缓存空值防止了穿透攻击（反复查同一个已删除 ID 不会反复打 DB）。

---

## 3. `getAndCheckOwner()` — 归属校验

```java
// NoteService.java:483
private Note getAndCheckOwner(Long noteId, Long userId) {
    Note note = noteMapper.selectById(noteId);
    if (note == null) {
        throw new BizException(NOTE_NOT_FOUND);
    }
    if (!note.getUserId().equals(userId)) {
        throw new BizException(FORBIDDEN, "无权操作他人笔记");
    }
    return note;
}
```

**设计要点**：

1. **笔记不存在 → `NOTE_NOT_FOUND`**：不暴露"笔记存在但你不拥有它"的信息。如果返回 `FORBIDDEN`，攻击者可以通过返回码推测"某个 noteId 是存在的，只是不是我的"。
2. **userId 比较用 `.equals()`**：`note.getUserId()` 返回 `Long` 对象，不能用 `==`。
3. **返回 Note 对象**：不需要调用方再次查询——同一个 SELECT 既做了归属校验，又返回了数据。

---

## 4. 总结

| 操作 | 状态限制 | DFA | 缓存清除 | 关键设计 |
|------|:------:|:---:|:------:|------|
| 编辑 | DRAFT / PUBLISHED | 已发布时重新检测 | DETAIL + LIST | 部分更新（只覆盖非 null） |
| 删除 | 无限制 | ❌ | DETAIL + LIST | @TableLogic 逻辑删除 |

> 编辑已发布笔记的 DFA 重检机制详见 `03-dfa/01-dfa-trie-overview.md`
> 缓存延迟双删机制详见 `06-cache-strategy/01-cache-aside-delete.md`

## 已知局限

| 局限 | 说明 |
|------|------|
| 部分更新不支持增量 | `content = content + "追加文本"` 这种操作无法实现——必须传完整新值 |
| 并发编辑无乐观锁 | 两个用户同时编辑同一笔记 → 后提交者覆盖先提交者，无 `@Version` 保护 |
| `content` 不能通过 `null` 清空 | `request.getContent() == null` 被跳过，需传 `""` 来清空正文 |
| 删除后物理清理缺失 | `deleted=1` 的记录永久保留，无定时归档/清理任务 |
