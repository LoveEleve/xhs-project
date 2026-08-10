# DFA 敏感词过滤：Trie 树 + 滑动窗口 + 多实例热更新

> **源码**: DFAFilter.java (304 行)  
> **算法**: DFA (Deterministic Finite Automaton) / Trie 前缀树  
> **设计亮点**: volatile 无锁热更新 / Redis Pub/Sub 多实例同步 / 全角半角归一化

---

## 1. 算法原理：从 O(k×n) 到 O(n×m)

| 方案 | 时间复杂度 | 10 万词库 × 1 万字笔记 |
|------|-----------|----------------------|
| SQL `LIKE '%word%'` | O(k × n) | 10 万 × 1 万 = 10 亿次扫描 |
| `List.contains()` 遍历 | O(k × n) | 同上 |
| **DFA Trie 树** | **O(n × m)** | 1 万 × 最长词长 ≈ 10 万次比较 |

> **⚠️ 源码注释偏差**：DFAFilter.java:22 注释声称"匹配 O(n)"，但实际上 `detect()` 实现中内层循环 `for (int j = i; j < processed.length(); j++)` 虽然由 `next == null` 提前终止（`break`），但该终止条件受 Trie 树深度（最大词长 m）限制，并非无条件 O(n)。精确复杂度为 O(n × m)，其中 m ≤ maxWordLen。源码注释在理想情况下成立——当内层循环被始终恒定地限制在 m 步内时，可以近似为 O(n)——但未说明隐藏的 m 因子。**本文档使用 O(n×m) 作为更准确的上界表述。**

m = 最长敏感词长度（通常 < 10），n = 文本长度。DFA 的优势来自"前缀共享"：

```
词库: [ "枪支", "枪支弹药", "赌博" ]

Trie 树结构:
    root
    ├── 枪 → 支 → [结束]
    │          └── 弹 → 药 → [结束]
    └── 赌 → 博 → [结束]

文本 "枪支弹药" 扫描:
    i=0: 枪(√) → 支(√) → 弹(√) → 药(√) → [结束] 命中 "枪支弹药"
                                  → [结束] 命中 "枪支"
```

---

## 2. Trie 树构建

```java
// DFAFilter.java:192-207
@SuppressWarnings("unchecked")
public void buildTrie(List<String> words) {
    Map<Character, Object> root = new HashMap<>();
    for (String word : words) {
        if (word == null || word.isEmpty()) continue;
        Map<Character, Object> current = root;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            // computeIfAbsent: 存在则返回现有节点，不存在则创建新 HashMap
            current = (Map<Character, Object>) current.computeIfAbsent(c, k -> new HashMap<>());
        }
        current.put(END_FLAG, null); // '\0' 字符作为词尾标记
    }
    this.trieRoot = root; // volatile 写入，保证所有线程可见
}
```

### 为什么用 `Map<Character, Object>` 而不是 `Map<Character, Map>`？

因为 Java 泛型不支持 `Map<Character, Map<Character, Map<...>>>` 这种递归类型。用 `Object` 做值类型，运行时 cast 回 `Map`。这是 DFA 实现中常见的类型擦除技巧。

---

## 3. 检测算法：滑动窗口

```java
// DFAFilter.java:216-244
@SuppressWarnings("unchecked")
public Set<String> detect(String text) {
    Set<String> result = new LinkedHashSet<>();
    if (text == null || text.isEmpty()) return result;

    String processed = preprocess(text);  // 文本预处理（§4）

    for (int i = 0; i < processed.length(); i++) {
        Map<Character, Object> current = trieRoot;
        StringBuilder word = new StringBuilder();
        for (int j = i; j < processed.length(); j++) {
            char c = processed.charAt(j);
            Object next = current.get(c);
            if (next == null) break;  // 不匹配，滑动窗口前移
            word.append(c);
            if (next instanceof Map) {
                current = (Map<Character, Object>) next;
                if (current.containsKey(END_FLAG)) {
                    result.add(word.toString()); // 命中！
                    // 不 break，继续检查是否有更长的匹配（如 "枪支" → "枪支弹药"）
                }
            }
        }
    }
    return result;
}
```

### 滑动窗口 vs 全量扫描

```
文本 "枪支弹药是违禁品"

外层循环 i=0:
  内层 j=0→3: 枪(√)→支(√)→弹(√)→药(√)→[结束] 命中 "枪支弹药"
  同时: 枪→支→[结束] 命中 "枪支"
  内层 j=4: 是(✗) → break

外层循环 i=1:
  内层 j=1: 支(✗) → break  ← 快速跳过

外层循环 i=2:
  内层 j=2: 弹(✗) → break  ← 快速跳过

结果: {"枪支", "枪支弹药"}
```

### 为什么命中后不 `break`？

因为可能有前缀关系：检测到 "枪支" 后继续检查，可能还匹配更长的 "枪支弹药"。两个都要返回。

---

## 4. 文本预处理：防绕过攻击

```java
// DFAFilter.java:263-283
private String preprocess(String text) {
    StringBuilder sb = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
        char c = text.charAt(i);
        // 全角转半角 (FF01~FF5E → 21~7E)
        if (c >= 0xFF01 && c <= 0xFF5E) c = (char) (c - 0xFEE0);
        else if (c == 0x3000) c = ' ';  // 全角空格

        // 秒杀常见绕过字符
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r'
                || c == '*' || c == '#' || c == '@' || c == '!'
                || c == '.' || c == ',' || c == '。' || c == '，') {
            continue;  // 跳过，不加入处理后文本
        }
        sb.append(Character.toLowerCase(c));
    }
    return sb.toString();
}
```

### 每种预处理解决什么攻击？

| 预处理 | 攻击示例 | 处理后 |
|--------|---------|--------|
| 全角→半角 | `枪 Ｏ支` (全角 O) | `枪支` |
| 去除空格 | `枪  支` | `枪支` |
| 去除特殊字符 | `枪*支`, `枪#支`, `枪.支` | `枪支` |
| 统一小写 | `Gun` | `gun` |

### 绕过的边界

当前实现**不**处理以下绕过方式：

| 绕过方式 | 示例 | 能否检测？ |
|---------|------|:--:|
| 插入数字 | `枪1支` → `枪1支` | ✗ 数字不被过滤 |
| 零宽字符 | `枪​支` (U+200B) | ✗ 零宽字符不被过滤 |
| 拼音 | `qiang zhi` | ✗ DFA 只能匹配中文字符 |
| Unicode 同形字 | `ɡun` (U+0261) vs `gun` | ✗ 需要专门的混淆字符映射表 |
| 谐音 | `biu biu` | ✗ 需要语义理解/NLP |

这些是已知的设计限制——当前方案覆盖了 80% 常见的文本绕过，更深层次的变体识别需要对接第三方审核 API。

---

## 5. 多实例热更新：volatile + Redis Pub/Sub

```
实例 A (运营后台)          实例 B (content 服务)
     │                          │
     │ addDynamicWords([...])    │
     │  → Redis RPUSH            │
     │  → PUBLISH channel        │
     │                          │
     │  ┌───────────────────────┘
     │  │  Redis PUBLISH "reload"
     │  │
     ▼  ▼
  onMessage()              onMessage()
     │                          │
  reload()                  reload()
     │                          │
  loadWordsFromFile()      loadWordsFromFile()
  loadWordsFromRedis()     loadWordsFromRedis()
     │                          │
  buildTrie(words)         buildTrie(words)
     │                          │
  trieRoot = newRoot       trieRoot = newRoot
```

### volatile 的无锁并发保证

```java
private volatile Map<Character, Object> trieRoot = new HashMap<>();
```

`volatile` 确保：
1. **写入可见性**：`buildTrie()` 中 `this.trieRoot = root` 对所有线程立即可见
2. **读取一致性**：`detect()` 中 `Map<Character, Object> current = trieRoot` 读到的是完整的 trie（volatile 保证引用写入前的所有字段初始化都已完成）

### 为什么不需要锁？

```
线程 A (detect)          线程 B (reload)
     │                        │
current = trieRoot            构建 newRoot
     │                        trieRoot = newRoot  ← volatile write
int j = i 用 current 继续        │
     │                        ✓ 线程 A 继续用旧的 current 引用
命中词库                       ✓ 不影响正在进行的检测
     │
下次检测时 trieRoot 已经是 newRoot ← volatile read
```

---

## 6. 双词库加载

### 6.1 静态词库（classpath 文件）

```java
// DFAFilter.java:164-186
private List<String> loadWordsFromFile() {
    ClassPathResource resource = new ClassPathResource("sensitive-words.txt");
    if (!resource.exists()) {
        log.warn("[DFA] 敏感词文件不存在，使用空词库");
        return words;
    }
    // 逐行读取，跳过空行和 # 注释行
    try (BufferedReader reader = ...) {
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (!line.isEmpty() && !line.startsWith("#")) words.add(line);
        }
    }
}
```

`sensitive-words.txt` 格式：
```
# 政治敏感
枪支弹药
色情内容

# 广告
加微信
扫码领取
```

### 6.2 动态词库（Redis Set）

运营后台通过 `addDynamicWords()` 添加敏感词 → 存入 Redis Set + PUBLISH 通知：
```
myxhs:sensitive-word:list → SMEMBERS → ["新敏感词1", "新敏感词2"]
```

动态词库与静态词库合并后再构建 Trie：
```java
List<String> words = loadWordsFromFile();
words.addAll(loadWordsFromRedis());
buildTrie(words);
```

---

## 7. 完整调用链

```
Controller (CommentService.createComment)     ← line 82
    │
    ▼
dfaFilter.detect(content)
    │
    ├── text 为 null/empty → return 空集合
    │
    ├── preprocess(text)
    │   ├── 全角→半角
    │   ├── 过滤空格/特殊字符
    │   └── 统一小写
    │
    ├── 滑动窗口检测
    │   for i in 0..len:
    │     for j in i..len:
    │       Trie树逐字匹配
    │
    └── return Set<String> (命中的敏感词)
        │
        ▼
    非空 → BizException → 事务回滚
```

---

## 8. 故障场景

| 场景 | 处理 |
|------|------|
| `sensitive-words.txt` 不存在 | `loadWordsFromFile` 返回空列表 + WARN 日志。词库为空 = 不检测（安全放宽） |
| Redis 不可用 | `loadWordsFromRedis` 捕获异常，返回空列表。不影响静态词库 |
| `reload()` 并发执行 | volatile 保证每次 `detect()` 看到完整的 trieRoot 引用，无中间状态 |
| `reload()` 中文件读取失败 | 异常在 `loadWordsFromFile` 中捕获，返回空列表。trie 只用动态词库重建 |

---

## 9. 知识点索引

| 知识点 | 源码 | 行号 |
|--------|------|------|
| Trie 树构建 | `DFAFilter.java` | 192-207 |
| 滑动窗口检测 | `DFAFilter.java` | 216-244 |
| 全角→半角转换 | `DFAFilter.java` | 268-269 |
| 字符过滤（跳过特殊字符） | `DFAFilter.java` | 274-277 |
| volatile 无锁热更新 | `DFAFilter.java` | 34, 205 |
| Redis Pub/Sub 多实例通知 | `DFAFilter.java` | 77-81, 152-159 |
| 动态词库 Redis Set | `DFAFilter.java` | 102-125 |
| 静态词库 classpath 文件 | `DFAFilter.java` | 164-186 |

---

## 10. 面试要点

**Q1**: DFA vs AC 自动机有什么区别？

**A**: 
- **DFA**：每个节点只存储一个字符的转移。匹配时用滑动窗口 + 前缀树逐字符回溯，最坏 O(n×m)。
- **AC 自动机**（Aho-Corasick）：在 Trie 上增加 `fail` 指针（类似 KMP 的 next 数组），匹配失败时跳转到最长后缀节点。一次扫描，O(n)。
- DFA 适合**短词库 + 长文本**；AC 适合**大词库**。当前 my-xhs 用 DFA，因为词库规模小（< 1000），DFA 实现更简单。

**Q2**: 为什么 `reload()` 期间 `detect()` 不会读到不完整的 Trie？

**A**: volatile 保证 `trieRoot = root` 这个引用赋值的 happens-before 语义——赋值前 `root` 中的所有 put 操作对后续读线程可见。`buildTrie()` 先完整构建 newRoot，最后才赋值给 volatile 字段。所以 `detect()` 看到的 trieRoot 要么是旧版本（完整），要么是新版本（也完整），永远不会是半成品。

**Q3（陷阱）**: 预处理去掉了 `*`、`#` 等字符。如果用户正常写 "5*5=25"，预处理后变成 "55=25"，会不会被误判？

**A**: 会。但内容审核系统的设计原则是 **宁可误杀，不可放过**。如果一个合法评论被误判为敏感词，用户会收到错误提示并修改。如果一条真正的敏感内容通过审核，后果更严重。这是安全系统 vs 业务系统的典型权衡。

**Q4**: 如果敏感词库里同时有 "枪支" 和 "枪支弹药"，检测 "枪支弹药" 会返回几个结果？

**A**: 两个都返回。因为命中 "枪支" 后不 break，继续检查后续字符是否构成更长匹配。`detect()` 不 break 是故意的——需要把匹配到的所有敏感词（包括前缀）都返回。

---

*下一篇：本地消息表 + MQ 可靠性补偿*
