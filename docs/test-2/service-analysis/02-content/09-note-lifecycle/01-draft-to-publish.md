# 笔记生命周期：草稿 → 发布

> 三条创建路径 + 状态机流转 + 缓存陷阱修复
> 前置阅读：`03-dfa/`（敏感词检测）、`04-transaction-aftercommit/`（事务生命周期）、`05-local-message-table/`（消息表）、`06-cache-strategy/`（延迟双删）

---

## 1. 笔记的三种创建方式

content 模块不是只有一个 `publishNote`——实际上有三条路径：

| 路径 | Controller | 状态 | DFA | 消息表 | MQ |
|------|-----------|:--:|:--:|:--:|:--:|
| 直接发布 | `POST /api/note/publish` → `publishNote()` | status=2 | ✅ | ✅ | ✅ |
| 保存草稿 | `POST /api/note/draft` → `saveDraft()` | status=0 | ❌ | ❌ | ❌ |
| 草稿发布 | `POST /api/note/{id}/publish` → `publishDraft()` | 0→2 | ✅ | ✅ | ✅ |

### 1.1 `saveDraft()` — 最简路径

```java
// NoteService.java:160-179
@Transactional(rollbackFor = Exception.class)
public Long saveDraft(Long userId, NotePublishRequest request) {
    Note note = buildNote(userId, request);
    note.setStatus(NoteStatus.DRAFT.getCode());         // 0
    note.setAuditStatus(AuditStatus.PENDING.getCode()); // 0
    noteMapper.insert(note);

    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        afterCommit() {
            cacheHelper.delayDoubleDelete(RedisKeyConstants.NOTE_LIST_USER + userId);
        }
    });
    return note.getId();
}
```

**与 `publishNote` 的差异**：

- ❌ 不调用 `checkSensitiveWords()` — 草稿允许写任何内容，发布时才检测
- ❌ 不写 `t_local_message` — 草稿不发 Feed
- ❌ 不发 MQ
- ✅ 清除用户笔记列表缓存（`NOTE_LIST_USER`）

### 1.2 `publishDraft()` — 最复杂的转换路径

```java
// NoteService.java:356-421
@Transactional(rollbackFor = Exception.class)
public void publishDraft(Long userId, Long noteId) {
    // 1. 归属校验
    Note note = getAndCheckOwner(noteId, userId);

    // 2. 状态流转校验
    NoteStatus currentStatus = NoteStatus.of(note.getStatus());
    if (!currentStatus.canTransitTo(NoteStatus.PUBLISHED)) {
        throw new BizException(NOTE_STATUS_ERROR);
    }

    // 3. 补做 DFA 检测（草稿保存时没做）
    checkSensitiveWords(note.getTitle(), note.getContent());

    // 4. 更新状态
    note.setStatus(PUBLISHED); note.setAuditStatus(APPROVED);
    noteMapper.updateById(note);

    // 5. 写本地消息表 + afterCommit 发 MQ + 清缓存
    LocalMessage localMsg = ...;
    localMessageMapper.insert(localMsg);

    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        afterCommit() {
            cacheHelper.delayDoubleDelete(NOTE_LIST_USER + userId);
            cacheHelper.delayDoubleDelete(NOTE_DETAIL + noteId);  // ← 刚修复的 Bug
            asyncSend(FEED_TOPIC, event, callback);
        }
    });
}
```

**与直接发布的差异**：

- 状态是 UPDATE 而非 INSERT — 笔记已经存在
- 必须先校验 `canTransitTo()` — 已发布/已下架不能再次发布
- 补做 DFA — 草稿内容在发布前重新检查
- 需要清除详情缓存 — 之前草稿的查询可能缓存了 NULL_PLACEHOLDER

---

## 2. NoteStatus 状态机

```java
// NoteStatus.java
public enum NoteStatus {
    DRAFT(0, "草稿"),
    AUDITING(1, "审核中"),
    PUBLISHED(2, "已发布"),
    OFFLINE(3, "已下架");

    private static final Map<NoteStatus, Set<NoteStatus>> TRANSITIONS = Map.of(
        DRAFT,     Set.of(AUDITING, PUBLISHED),
        AUDITING,  Set.of(PUBLISHED, OFFLINE),
        PUBLISHED, Set.of(OFFLINE)
        // OFFLINE → 无出口，终态
    );
}
```

```
┌────────┐   审核   ┌──────────┐
│ DRAFT  │ ──────→ │ AUDITING │
│   0    │         │    1     │
└───┬────┘         └────┬─────┘
    │  直接发布          │  通过
    │                   ▼
    │              ┌──────────┐   下架   ┌──────────┐
    └─────────────→│PUBLISHED │ ──────→ │ OFFLINE  │
                   │    2     │         │    3     │
                   └──────────┘         └──────────┘
```

**当前实现的简化**：项目跳过了审核流程——不论是 `publishNote` 还是 `publishDraft`，都直接设 `status=PUBLISHED(2)` 和 `auditStatus=APPROVED(1)`。`AUDITING(1)` 状态在代码中定义了但从未使用，路径 `DRAFT → AUDITING → PUBLISHED` 不存在。

---

## 3. `canTransitTo()` — 防非法状态变更

```java
public boolean canTransitTo(NoteStatus target) {
    Set<NoteStatus> allowed = TRANSITIONS.get(this);
    return allowed != null && allowed.contains(target);
}
```

**使用场景**：

```java
// publishDraft()
if (!currentStatus.canTransitTo(NoteStatus.PUBLISHED)) {
    throw new BizException(NOTE_STATUS_ERROR, "当前状态[" + currentStatus.getDesc() + "]不允许发布");
}

// updateNote()
if (currentStatus != DRAFT && currentStatus != PUBLISHED) {
    throw new BizException(NOTE_STATUS_ERROR, "当前状态不允许编辑");
}
```

**防护范围**：

| 操作 | 允许的状态 | 被拒绝的状态 |
|------|-----------|-------------|
| 发布草稿 | DRAFT | PUBLISHED、OFFLINE（已发布/下架的不能再"发布"） |
| 编辑笔记 | DRAFT、PUBLISHED | AUDITING、OFFLINE |
| 删除笔记 | 所有 | 无限制（逻辑删除，不是状态流转） |

---

## 4. 缓存陷阱：为什么草稿发布后会出现 404

当前修复之前，`publishDraft()` 的 `afterCommit` 只清除 `NOTE_LIST_USER`，不清除 `NOTE_DETAIL`。

**触发路径**：

```
T0: DRAFT 保存成功（status=0）
T1: 用户或测试脚本 调用 GET /api/note/detail/{id}
     → getNoteDetail(id) → 查到 status=0 ≠ PUBLISHED → return null
     → cacheHelper.getWithCacheAside() 缓存 NULL_PLACEHOLDER, TTL=2min
T2: 发布草稿 publishDraft(id) → status=0→2 → 事务提交
     → afterCommit: delayDoubleDelete(NOTE_LIST_USER) ✅
     → afterCommit: delayDoubleDelete(NOTE_DETAIL)    ❌ 缺失（修复前）
T3: 用户访问 GET /api/note/detail/{id}
     → Cache Aside: 命中 NULL_PLACEHOLDER → 返回 null
     → 抛出 NOTE_NOT_FOUND
     → 笔记明明已发布，接口却返回 404
```

**修复**（`NoteService.java:400`）：

```java
afterCommit() {
    cacheHelper.delayDoubleDelete(RedisKeyConstants.NOTE_LIST_USER + userId);
    cacheHelper.delayDoubleDelete(RedisKeyConstants.NOTE_DETAIL + noteId);  // ← 修复
    asyncSend(FEED_TOPIC, event, callback);
}
```

修复后，`T2` 的 `afterCommit` 会立即删除详情缓存——`T3` 时 Cache Miss → 查 DB → status=2 → 正常返回。

**通用教训**：任何将数据从"不可见状态"转化为"可见状态"的操作，必须清除所有可能缓存了空值/旧值的 Key。不只是列表缓存，详情缓存也要清除。

---

## 5. 为何草稿不检测敏感词？

这看起来像安全漏洞——用户可以在草稿里写任何内容——但实现上没问题：

1. **草稿只有作者自己能看到** — `GET /api/note/my` 返回草稿内容，但这是自己的草稿
2. **发布时补做 DFA** — `publishDraft()` 第一件事就是 `checkSensitiveWords()`，不通过直接抛异常
3. **不浪费 DB 连接** — 草稿保存时不持有连接做 DFA（虽然 DFA 是纯内存），关键是设计意图明确：草稿 = 临时数据，发布 = 正式数据，正式数据才需要校验

如果需要在草稿保存时也做检测，加一行 `checkSensitiveWords()` 即可，不会引入任何架构变更。

---

## 6. 总结

| 路径 | 状态转换 | DFA | 消息表 | 缓存清除 | MQ |
|------|:------:|:---:|:-----:|:------:|:--:|
| `publishNote` | — → PUBLISHED | ✅ | ✅ | LIST | ✅ |
| `saveDraft` | — → DRAFT | ❌ | ❌ | LIST | ❌ |
| `publishDraft` | DRAFT → PUBLISHED | ✅ 补做 | ✅ | LIST + **DETAIL** | ✅ |

核心设计原则：草稿是临时态、发布是终态——终态需要完整性保障（DFA + 消息表 + MQ + 双缓存清除）。

> **直接发布**的完整链路分析见 `03-dfa/` 至 `08-id-generation/` 共 6 份深度文档。
