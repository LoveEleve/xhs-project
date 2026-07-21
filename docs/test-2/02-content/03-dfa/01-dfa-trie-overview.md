# DFA Trie 树敏感词过滤

> `my-xhs-content/src/main/java/com/myxhs/content/filter/DFAFilter.java`（305 行）
> 一个完整的工程问题：数据结构选择 → 算法实现 → 性能分析 → 多实例一致 → 线程安全 → 生产缺项。

---

## 1. 问题

**给定一个词库（几千到几万个敏感词）和一段用户输入的文本（标题最长 128 字 + 正文最长 20000 字），判断文本是否包含任何敏感词，并找出具体是哪些词。**

实际场景：
- 用户输入 `"我这里有便宜博彩平台"` → `buildTrie` 的词库中有 `赌博` → 需要检测到"博"后面跟着"彩"不代表安全
- 用户输入 `"ｄｕｂｏ"`（全角） → 需要转半角后匹配 `"dubo"`
- 用户输入 `"赌*博"` → 需要过滤 `*` 后匹配 `"赌博"`
- 100 并发请求每秒，每个请求 DFA 检测不能成为瓶颈

---

## 2. 数据结构：为什么是 Trie 树（前缀树）？

### 2.1 直观理解

Trie 树把每个词拆成一个字符链，共同前缀共享节点。

```
词库: ["赌博", "赌场", "诈骗"]

root
 ├─ 赌 → node("赌")
 │     ├─ 博 → END  ← 命中"赌博"
 │     └─ 场 → END  ← 命中"赌场"
 └─ 诈 → node("诈")
       └─ 骗 → END  ← 命中"诈骗"
```

查找 `"赌场"` 的过程：
```
从 root 找 '赌' → 命中 node("赌")
从 node("赌") 找 '场' → 命中 → 检查 END_FLAG → 是 → 返回"赌场"
```

查找 `"赌馆"` 的过程：
```
从 root 找 '赌' → 命中 node("赌")
从 node("赌") 找 '馆' → 不存在 → 未命中
```

### 2.2 vs 其他方案的复杂度分析

| 方案 | 每次检测时间复杂度 | 10 万词库、1000 字文本 |
|------|:---:|------|
| **Trie 树** | **O(N)**，N=文本长度 | 遍历 1000 个字符，~0.1ms |
| SQL LIKE | O(N*M)，N=文本长度，M=词数 | 1000 × 100,000 = 1 亿次字符比较 |
| 正则 `\b(word1\|word2\|...)\b` | O(N*∑Wi)，取决于正则引擎对「或」的优化 | Java Pattern 会编译成 DFA，但 10 万个「或」条件编译极慢 |
| Brute Force `contains()` | O(N*M*L)，N=文本，M=词数，L=平均词长 | 1000 × 100,000 × 3 = 3 亿次 |

**结论**：Trie 树检测只需**遍历文本 1 遍**，性能与词库大小无关。词库从 100 个增加到 10 万个，检测性能不变（只影响内存）。

---

## 3. 代码实现

### 3.1 内存模型

这里实现的是**自制版本**，不是标准的 Trie 类，而是直接用 JDK 原生 `HashMap` 模拟：

```java
// DFAFilter.java:34
private volatile Map<Character, Object> trieRoot = new HashMap<>();
```

每个节点是 `HashMap<Character, Object>`，其中 `Object` 的含义分两种情况：

| 值类型 | 含义 |
|--------|------|
| `Map<Character, Object>` | 子节点，继续往下匹配 |
| `END_FLAG('\0')` | 词尾标记，当前前缀构成一个完整敏感词 |

节点对象中同时存在终止标记和子节点是可能的——词库中有 `"赌博"` 和 `"赌博平台"` 时，`"博"` 节点既包含 `END_FLAG` 又包含子节点 `"平"`。

### 3.2 构建算法 — `buildTrie()`

```java
// DFAFilter.java:192-207
public void buildTrie(List<String> words) {
    Map<Character, Object> root = new HashMap<>();
    for (String word : words) {
        if (word == null || word.isEmpty()) continue;
        Map<Character, Object> current = root;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            // computeIfAbsent: 当前字符对应的子节点不存在就创建
            current = (Map<Character, Object>) current.computeIfAbsent(c, k -> new HashMap<>());
        }
        // 词尾插入 END_FLAG
        current.put(END_FLAG, null);
    }
    this.trieRoot = root; // volatile 写 ← 线程安全保证
}
```

以 `["赌博", "赌场", "诈骗"]` 为例，逐步跟踪 `computeIfAbsent` 的状态：

```
处理 "赌博":
  i=0: c='赌' → root中没有'赌' → computeIfAbsent → 创建 HashMap node1
        current = node1
  i=1: c='博' → node1中没有'博' → computeIfAbsent → 创建 HashMap node2
        current = node2
  循环结束 → node2.put('\0', null)

当前 Trie:
  root: { '赌' → node1 { '博' → node2 { '\0' → null } } }

处理 "赌场":
  i=0: c='赌' → root中有'赌' → computeIfAbsent → 返回已有 node1（共享前缀！）
        current = node1
  i=1: c='场' → node1中没有'场' → computeIfAbsent → 创建 HashMap node3
        current = node3
  循环结束 → node3.put('\0', null)

当前 Trie:
  root: { '赌' → node1 { '博' → node2 { '\0' → null },
                         '场' → node3 { '\0' → null } } }

处理 "诈骗":
  i=0: c='诈' → root中没有'诈' → computeIfAbsent → 创建 HashMap node4
  i=1: c='骗' → node4中没有'骗' → computeIfAbsent → 创建 HashMap node5
  node5.put('\0', null)

最终 Trie:
  root: { '赌' → { '博' → { '\0' }, '场' → { '\0' } },
          '诈' → { '骗' → { '\0' } } }
```

### 3.3 检测算法 — `detect()`

```java
// DFAFilter.java:216-244
public Set<String> detect(String text) {
    Set<String> result = new LinkedHashSet<>();
    if (text == null || text.isEmpty()) return result;

    String processed = preprocess(text);
    Map<Character, Object> root = trieRoot; // volatile 读 ← 拿到最新版本

    for (int i = 0; i < processed.length(); i++) {
        Map<Character, Object> current = root;
        StringBuilder word = new StringBuilder();
        for (int j = i; j < processed.length(); j++) {
            char c = processed.charAt(j);
            Object next = current.get(c);
            if (next == null) break;          // 匹配失败，i 后移
            word.append(c);
            if (next instanceof Map) {
                current = (Map<Character, Object>) next;
                if (current.containsKey(END_FLAG)) {
                    result.add(word.toString()); // 命中一个词，但继续匹配更长子串
                }
            }
        }
    }
    return result;
}
```

**关键设计：命中后不停止，继续匹配更长子串**

因为词库中可能存在 `"赌博"` 和 `"赌博平台"` 两个词。当匹配到 `"赌博"` 的 `END_FLAG` 时，当前节点的 `Map` 可能还包含子节点（`"平"` 等），算法会继续扫描 `j++`：

```
文本: "这个赌博平台很火"
i=3: c='赌' → 命中 root['赌'] → word="赌"
     j=4: c='博' → 命中 node1['博'] → word="赌博" → END_FLAG → 加入结果
          j=5: c='平' → 命中 node2['平'] → word="赌博平"
               j=6: c='台' → 命中 node3['台'] → word="赌博平台" → END_FLAG → 加入结果
                    j=7: c='很' → node4['很']=null → break
```

最终结果 = `{"赌博", "赌博平台"}`。

**复杂度分析**：外层循环 `i` 走遍文本的每个位置，内层循环 `j` 从 `i` 开始沿 Trie 走，但遇到 `break`（`current` 中没有该字符）时就停止。平均每个位置只匹配几个字符就退出，实际执行时间远小于 O(N × maxWordLength)，近似 O(N)。

### 3.4 文本预处理 — `preprocess()`

```java
// DFAFilter.java:263-283
private String preprocess(String text) {
    StringBuilder sb = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
        char c = text.charAt(i);
        // 1. 全角转半角
        if (c >= 0xFF01 && c <= 0xFF5E) {
            c = (char) (c - 0xFEE0);
        } else if (c == 0x3000) {
            c = ' ';
        }
        // 2. 跳过干扰字符
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r'
                || c == '*' || c == '#' || c == '@' || c == '!'
                || c == '.' || c == ',' || c == '。' || c == '，') {
            continue;
        }
        // 3. 统一小写
        sb.append(Character.toLowerCase(c));
    }
    return sb.toString();
}
```

**为什么要这三步？**

| 处理 | 原因 | 示例 |
|------|------|------|
| 全角转半角 | 中文输入法可输入全角英文 `ｇａｍｂｌｉｎｇ` | 全角 `0xFF41`(ａ) - `0xFEE0` = 半角 `0x0061`(a) |
| 过滤干扰符 | 用户用 `赌 * 博` 绕过匹配 | `"赌*博"` → `"赌博"` |
| 统一小写 | `Gambling` vs `gambling` | `"GAMBLING"` → `"gambling"` |

全角范围 `0xFF01-0xFF5E` 对应 ASCII 的 `!~`（0x21-0x7E），减去 `0xFEE0` 就是对应的半角字符。全角空格 `0x3000` 单独处理。

---

## 4. 双词典架构

不是只有一个静态文件就结束了——生产环境中运营需要随时加词/删词，不能每次改词都重启服务。DFAFilter 用了三层加载机制：

```
┌─ 静态词库 (classpath sensitive-words.txt) ─ 兜底
│    → loadWordsFromFile() → BufferedReader 逐行读取
│
├─ 动态词库 (Redis Set myxhs:sensitive-word:list) ─ 运营后台实时操作
│    → loadWordsFromRedis() → SMEMBERS
│
└─ Redis Pub/Sub (Channel myxhs:sensitive-word:reload) ─ 多实例同步
     → registerRedisListener() → MessageListener.onMessage → reload()
```

**操作流程**：

```
运营后台 → addDynamicWords(["代孕"]) → Redis SADD + PUBLISH "reload"
  │
  ├─ content-实例1 收到 Pub/Sub 消息 → reload()
  ├─ content-实例2 收到 Pub/Sub 消息 → reload()
  └─ content-实例3 收到 Pub/Sub 消息 → reload()

reload() = loadWordsFromFile() + loadWordsFromRedis() + buildTrie(words)
```

`reload()` 是**全量重建**——代码注释清楚写了不追求增量更新，因为增量太复杂：
- 加词：可以增量挂到树末尾，但如果加的是已有前缀的新词，需要知道前缀对应的节点
- 删词：需要知道该节点是否有其他子词——如果有，不能直接删节点，只能移除 `END_FLAG`

考虑到敏感词更新不是高频操作（一天几次），全量重建开销可接受（几十毫秒），没必要用增量。

---

## 5. 线程安全：volatile + 无锁写

```java
private volatile Map<Character, Object> trieRoot = new HashMap<>();
```

**volatile 的作用**：
- `detect()` 读 `trieRoot` 时，保证看到的是 `buildTrie()` 最后一次赋值的结果
- `buildTrie()` 写 `this.trieRoot = root` 时，保证在此之前构造的整个 `Map<Character, Object>` 树对读线程可见

**无锁设计的代价**：`buildTrie()` 构造过程中 `trieRoot` 还指向旧树，读线程读旧树，不阻塞。当 `this.trieRoot = root` 赋值完成，后续读线程看到新树。

**TOCTOU 风险**：`buildTrie()` 方法本身没有同步保护。如果两个线程同时调用 `reload()`，两个线程各自构建一棵新树，最后 volatile 写只会保留其中一个——另一棵树被 GC 回收。但这个场景不会出正确性问题（两棵树内容相同），只是浪费了 CPU 构建一棵没有用的树。

**更严重的极端情况**：一个线程在 `buildTrie` 过程中（`for each word` 循环还没结束），另一个线程执行 `detect()`，读到的是 `this.trieRoot` 的旧值——所以 `detect` 仍然正常工作。当循环结束、`this.trieRoot = root` 赋值后，后续的 `detect` 才读新树。这是安全的。

---

## 6. 内存分析

**单个节点的内存开销**（HashMap 方案）：

每个 HashMap 的 overhead 约 48 字节（JVM 对象头 + threshold + loadFactor + modCount + size）+ 平均每个 entry 32 字节（Node 对象 + key + value 引用 + next 指针）。

5000 个词，假设平均词长 3 个字符：
- 节点数 ≈ 5000 × 3 + 5000（END_FLAG 占一个 entry）= 20000 个 HashMap 对象
- 每个 HashMap 只有 1-2 个 entry（中文词的前缀很少冲突）
- 总开销 ≈ 20000 × (48 + 32) ≈ 1.6MB

加上全局 `trieRoot`、字符串常量等，总内存约 2-3MB。对于 -Xmx512m 的 JVM 进程，几乎是零成本。

---

## 7. 生产缺项

| 缺项 | 严重度 | 说明 |
|------|:------:|------|
| 词库仅 5 个测试词 | 🔴 | classpath 文件只有赌博/诈骗/传销/洗钱/走私。代码已支持通过 Redis 动态加载，缺少的是一个全量词库 |
| 预处理只过滤标点符号 | 🟡 | 变体绕过：emoji、Unicode 零宽字符（`U+200B`）未过滤 |
| `buildTrie` 无并发锁 | 🟡 | 两个线程同时 `reload()` 会重复构建，最多浪费 CPU |
| 未处理特殊用户 | 🟡 | VIP 用户可能需要更宽松的策略？目前一刀切 |
| 无降级策略 | 🟢 | Redis 不可用时动态词库加载失败只打 warn，不影响静态词库 |

---

## 8. 总结

| 维度 | 选择 | 理由 |
|------|------|------|
| 数据结构 | Trie 树（HashMap 实现） | 检测 O(N)，与词库大小无关 |
| 词汇来源 | 静态文件 + Redis Set 双词典 | 基础兜底 + 实时运营 |
| 多实例同步 | Redis Pub/Sub 全量重建 | 操作简单，更新频率低 |
| 线程安全 | volatile + 写时构建 | 无锁，读不阻塞 |
| 文本清洗 | 全角→半角 + 过滤干扰符 + 小写 | 防简单变体绕过 |
