# 笔记发布与审核 — Code Review 报告

> 审查时间：2026-05-13 | 审查范围：NoteService / NoteController / DFAFilter / LocalFileStorageService

---

## 一、Review 评分（对标 P8）

| 维度 | 权重 | 得分 | 加权分 |
|------|:----:|:----:|:------:|
| 架构设计 | 25% | 9.0 | 2.250 |
| 代码质量 | 25% | 8.5 | 2.125 |
| 技术深度 | 25% | 9.0 | 2.250 |
| 安全设计 | 15% | 9.0 | 1.350 |
| 工程实践 | 10% | 8.0 | 0.800 |
| **综合** | **100%** | | **8.8 / 10** |

**结论**：✅ 达到 P8 水平

---

## 二、发现的问题 & 修复记录

### 🔴 P0 问题（已修复）

| # | 问题 | 修复方案 | 状态 |
|:-:|------|---------|:----:|
| 1 | `deleteNote` 缓存只删一次（应延迟双删） | 统一使用 `cacheHelper.delayDoubleDelete()` | ✅ |
| 2 | 缓存操作在事务内执行（事务回滚后缓存已删） | 使用 `TransactionSynchronization.afterCommit()` 移到事务提交后 | ✅ |

### 🟡 P2 问题（已修复）

| # | 问题 | 修复方案 | 状态 |
|:-:|------|---------|:----:|
| 3 | 未使用的 `Collections` 导入 | 删除 | ✅ |
| 4 | 敏感词复用评论错误码 | 新增 `NOTE_CONTENT_ILLEGAL(20006)` | ✅ |
| 5 | `toJson` 失败静默返回 null | 改为抛出 `BizException` 触发事务回滚 | ✅ |
| 6 | 文件扩展名信任客户端 | 从 Content-Type 推导扩展名，删除 `getExtension` 方法 | ✅ |
| 7 | 移除未使用的 `RedisOperator` | 删除导入和字段声明 | ✅ |
| 8 | `pageSize` 无上限 | 加 `Math.min(pageSize, 50)` | ✅ |
| 9 | Key 常量重复（`NOTE_LIST_USER_PREFIX`） | 改用 `RedisKeyConstants.NOTE_LIST_USER` | ✅ |
| 10 | `multipart.max-file-size` 与代码限制不一致 | `max-file-size` 改为 5MB | ✅ |
| 11 | 草稿保存共用 `NotePublishRequest` 导致标题必填 | 草稿接口去掉 `@Valid` | ✅ |

### 🟢 后续优化（非阻塞）

| # | 问题 | 建议 | 状态 |
|:-:|------|------|:----:|
| 12 | `updateNote` 存在 ABA 问题 | 后续加乐观锁 `@Version` | ⬜ |
| 13 | 缓存了整个 `Note` 实体 | 后续优化为缓存 VO | ⬜ |
| 14 | 上传接口未记录 userId | 日志中补充 | ⬜ |
| 15 | `toItemVO` 每次反序列化整个 images JSON | 可用正则提取第一个 URL | ⬜ |

---

## 三、技术亮点

| 技术点 | 评分 | 面试价值 | 说明 |
|--------|:----:|:--------:|------|
| **DFA 敏感词过滤** | ⭐⭐⭐⭐⭐ | 高频 | Trie 树 O(n) 时间复杂度，支持热更新 |
| **状态机设计** | ⭐⭐⭐⭐⭐ | 高频 | 草稿→待审核→已发布/已拒绝，状态流转清晰 |
| **Cache Aside 模式** | ⭐⭐⭐⭐⭐ | 高频 | 查缓存→未命中查DB→回填缓存，防穿透+防雪崩 |
| **延迟双删** | ⭐⭐⭐⭐⭐ | 高频 | 删缓存→等待→再删缓存，保证最终一致性 |
| **事务后清缓存** | ⭐⭐⭐⭐⭐ | 高频 | `afterCommit()` 确保事务提交后才操作缓存 |
| **Content-Type 白名单** | ⭐⭐⭐⭐ | 中频 | 文件上传安全，不信任客户端扩展名 |

---

## 四、面试话术

### Q1: 缓存一致性怎么保证的？

> 1. **写操作**：使用"延迟双删"策略
>    - 第一次删除：事务提交后立即删除缓存
>    - 第二次删除：延迟 500ms 后再删一次（防止并发读请求回填了旧数据）
>
> 2. **为什么不用"先更新DB再更新缓存"？**
>    - 并发场景下可能出现：线程A更新DB→线程B更新DB→线程B更新缓存→线程A更新缓存
>    - 结果：缓存中是线程A的旧数据，DB中是线程B的新数据 → 不一致
>
> 3. **为什么缓存操作要在事务提交后？**
>    - 如果在事务内删缓存，事务回滚后缓存已经被删了
>    - 其他请求查缓存未命中，查DB得到旧数据回填缓存 → 不一致
>    - 使用 `TransactionSynchronization.afterCommit()` 确保事务成功后才删缓存

### Q2: DFA 敏感词过滤怎么实现的？

> 1. **数据结构**：Trie 树（前缀树），每个节点是一个字符
> 2. **构建**：将敏感词逐字符插入 Trie 树，末尾标记 `END_FLAG`
> 3. **检测**：遍历输入文本，每个字符尝试匹配 Trie 树路径
> 4. **预处理**：输入文本先转小写、去除特殊字符（防止"赌 博"绕过）
> 5. **时间复杂度**：O(n)，n 为输入文本长度
> 6. **热更新**：使用 `volatile` 修饰 Trie 树根节点，重建后原子替换

### Q3: 文件上传安全怎么做的？

> 1. **Content-Type 白名单**：只允许 `image/jpeg`、`image/png`、`image/gif`、`image/webp`
> 2. **扩展名从 Content-Type 推导**：不信任客户端传的文件名（可能伪造 `.jsp`）
> 3. **文件大小限制**：代码层 5MB + Spring 配置层 5MB 双重限制
> 4. **文件名随机化**：UUID 重命名，防止路径遍历攻击

---

## 五、修复前后对比

### 修复 1：缓存操作移到事务提交后

```java
// ❌ 修复前：缓存操作在事务内
@Transactional
public void publishNote(...) {
    noteMapper.updateById(note);
    cacheHelper.delayDoubleDelete(key);  // 如果后续代码抛异常，事务回滚但缓存已删
}

// ✅ 修复后：事务提交后才操作缓存
@Transactional
public void publishNote(...) {
    noteMapper.updateById(note);
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            cacheHelper.delayDoubleDelete(key);  // 事务成功后才删缓存
        }
    });
}
```

### 修复 2：文件扩展名安全

```java
// ❌ 修复前：信任客户端文件名
String ext = getExtension(file.getOriginalFilename());  // 客户端可传 "hack.jsp"

// ✅ 修复后：从 Content-Type 推导
private static final Map<String, String> CONTENT_TYPE_EXT_MAP = Map.of(
    "image/jpeg", ".jpg",
    "image/png", ".png",
    "image/gif", ".gif",
    "image/webp", ".webp"
);
String ext = CONTENT_TYPE_EXT_MAP.get(file.getContentType());
```
