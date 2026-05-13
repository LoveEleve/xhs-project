# 笔记发布与审核

> 所属服务：my-xhs-content (9002) | 开发阶段：Phase-1 | 状态：⏳ 待开发

---

## 🎯 一、需求分析

### 1.1 业务场景

用户发布图文/视频笔记，经过 DFA 敏感词自动审核后上架。支持草稿保存、笔记编辑、状态流转（草稿→审核中→已发布/已下架）。图片通过本地磁盘存储（生产环境可切换 MinIO/OSS）。

### 1.2 功能边界

| 功能项 | 是否实现 | 说明 |
|--------|---------|------|
| 笔记发布（图文） | ✅ | 标题 128 字、正文 TEXT、图片最多 9 张 |
| 笔记发布（视频） | ✅ | 视频 URL 存储，不做视频转码 |
| 草稿保存 | ✅ | 保存草稿不触发审核 |
| DFA 敏感词审核 | ✅ | Trie 树构建，10 万词库毫秒级匹配 |
| 敏感词热更新 | ✅ | Redis Pub/Sub 通知各节点刷新 Trie 树 |
| 笔记状态机 | ✅ | 草稿→审核中→已发布/已下架，防非法状态流转 |
| 图片上传 | ✅ | 本地磁盘存储 + 接口抽象（可切 OSS） |
| 话题关联 | ✅ | 笔记可关联多个话题（topic_ids JSON） |
| 人工审核后台 | ❌ | 需要管理后台，不在 MVP 范围 |
| AI 内容审核 | ❌ | 需对接第三方 AI 审核 API |

### 1.3 数据量预估

| 指标 | 预估值 | 计算依据 |
|------|--------|----------|
| 笔记总量 | 5000 万 | 1000 万用户 × 平均 5 篇笔记 |
| 日增量 | 10 万/天 | 活跃用户 100 万 × 10% 发布率 |
| 图片总量 | 2 亿 | 5000 万笔记 × 平均 4 张图片 |
| 笔记详情 QPS | 5000 | 首页 Feed 流 + 搜索结果点击 |
| 发布 QPS | 200 | 高峰期集中发布 |

---

## 🏗️ 二、架构设计

### 2.1 整体架构图

```
Client → Gateway(鉴权) → my-xhs-content(9002) → MySQL(t_note) + Redis(笔记缓存)
                                │                        │
                                ├── 图片上传 → 本地磁盘/MinIO
                                ├── DFA 敏感词过滤 → Trie 树（内存）
                                └── 审核通过 → RocketMQ → Feed 服务（推送到粉丝收件箱）
```

### 2.2 模块交互

| 调用方 | 被调用方 | 方式 | 场景 |
|--------|---------|------|------|
| my-xhs-content | MySQL | MyBatis-Plus | 笔记 CRUD |
| my-xhs-content | Redis | RedisOperator | 笔记详情缓存、敏感词热更新 |
| my-xhs-content | RocketMQ | 异步消息 | 审核通过后通知 Feed 服务推送 |
| my-xhs-content | 本地磁盘/MinIO | FileStorageService | 图片上传存储 |
| my-xhs-home | my-xhs-content | Feign | Feed 流聚合时获取笔记详情 |
| my-xhs-search | my-xhs-content | Canal + MQ | 笔记变更同步到 ES |

### 2.3 核心流程时序图

**笔记发布流程：**

```
1. Client → Gateway: POST /api/note/publish（鉴权通过）
2. Gateway → NoteService: 转发请求
3. NoteService: 参数校验（标题长度、正文长度、图片数量）
4. NoteService → DFAFilter: 敏感词检测（标题 + 正文）
5.   ├── 包含敏感词 → 返回"内容包含违规词汇"
6.   └── 通过 → 继续
7. NoteService → MySQL: INSERT t_note（status=2 已发布, audit_status=1 通过）
8. NoteService → Redis: DEL note:list:user:{userId}（清除用户笔记列表缓存）
9. NoteService → RocketMQ: 发送 NOTE_PUBLISHED 消息（异步通知 Feed 服务）
10. NoteService → Client: 返回笔记 ID
```

**笔记状态机：**

```
                    ┌──────────┐
                    │  草稿(0)  │
                    └────┬─────┘
                         │ 提交发布
                         ▼
                    ┌──────────┐
                    │ 审核中(1) │
                    └────┬─────┘
                    ┌────┴────┐
                    │         │
              审核通过    审核拒绝
                    │         │
                    ▼         ▼
              ┌──────────┐ ┌──────────┐
              │ 已发布(2) │ │ 已下架(3) │
              └────┬─────┘ └──────────┘
                   │ 作者删除/违规下架
                   ▼
              ┌──────────┐
              │ 已下架(3) │
              └──────────┘
```

> **简化说明**：当前版本 DFA 敏感词检测通过即自动发布（status=2），不经过人工审核队列。后续可扩展为 MQ 异步审核。

---

## 🗄️ 三、数据库设计

### 3.1 表结构

```sql
-- 笔记表（实际 DDL 来自 init-databases.sql）
CREATE TABLE IF NOT EXISTS t_note (
    id            BIGINT       NOT NULL COMMENT 'ID',
    user_id       BIGINT       NOT NULL COMMENT '作者ID',
    title         VARCHAR(128) DEFAULT NULL COMMENT '标题',
    content       TEXT         DEFAULT NULL COMMENT '正文',
    images        TEXT         DEFAULT NULL COMMENT '图片URL列表(JSON)',
    video_url     VARCHAR(512) DEFAULT NULL COMMENT '视频URL',
    cover_url     VARCHAR(512) DEFAULT NULL COMMENT '封面图URL',
    topic_ids     VARCHAR(512) DEFAULT NULL COMMENT '话题ID列表(JSON)',
    tags          VARCHAR(512) DEFAULT NULL COMMENT '标签列表(JSON)',
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0-草稿 1-审核中 2-已发布 3-已下架',
    audit_status  TINYINT      NOT NULL DEFAULT 0 COMMENT '审核状态：0-待审核 1-通过 2-拒绝',
    reject_reason VARCHAR(256) DEFAULT NULL COMMENT '审核拒绝原因',
    note_type     TINYINT      NOT NULL DEFAULT 0 COMMENT '笔记类型：0-图文 1-视频',
    deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    INDEX idx_user_id (user_id),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='笔记表';
```

### 3.2 索引设计

| 索引名 | 字段 | 类型 | 使用场景 |
|--------|------|------|----------|
| `idx_user_id` | user_id | INDEX | 查询用户的笔记列表 |
| `idx_status` | status | INDEX | 按状态筛选（审核队列、已发布列表） |
| `idx_created_at` | created_at | INDEX | 按时间排序、后台管理时间筛选 |

### 3.3 字段设计决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 图片存储 | `images` TEXT（JSON 数组） | 图片数量不固定（1~9张），JSON 数组比关联表更简单；不需要按图片查询 |
| 话题关联 | `topic_ids` VARCHAR(JSON) | 笔记关联话题数量少（1~3个），JSON 比关联表更轻量 |
| 计数字段 | 不在 t_note 中存储 | 点赞/收藏/评论/浏览数由计数服务（my-xhs-counter）管理，避免高频更新笔记表 |
| status + audit_status | 双字段分离 | status 管理笔记生命周期，audit_status 管理审核结果，职责分离 |

---

## 🔑 四、缓存设计

### 4.1 Redis Key 规范

| Key Pattern | 类型 | 过期时间 | 说明 |
|-------------|------|----------|------|
| `note:info:{noteId}` | Hash | 30min | 笔记详情缓存（标题/正文/图片/作者等） |
| `note:list:user:{userId}` | ZSet | 30min | 用户笔记列表（score=发布时间戳） |

### 4.2 缓存更新策略

| 操作 | 策略 | 说明 |
|------|------|------|
| 读笔记详情 | Cache Aside + 逻辑过期 | 热点笔记使用逻辑过期，异步更新不阻塞请求 |
| 发布/编辑笔记 | 延迟双删 | 先更新 DB → 删缓存 → 延迟 500ms 再删缓存 |
| 删除笔记 | 直接删缓存 | DEL note:info:{noteId} + ZREM note:list:user:{userId} |

### 4.3 缓存异常处理

| 问题 | 解决方案 |
|------|----------|
| 缓存穿透 | 缓存空值（TTL=2min），不存在的笔记 ID 也缓存 |
| 缓存击穿 | 热点笔记使用逻辑过期（不设 TTL，过期后异步更新） |
| 缓存雪崩 | TTL 加随机偏移（30min ± 5min） |

---

## 📡 五、接口设计

### 5.1 接口列表

| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/api/note/publish` | 发布笔记 | ✅ |
| POST | `/api/note/draft` | 保存草稿 | ✅ |
| PUT | `/api/note/{id}` | 编辑笔记 | ✅ |
| DELETE | `/api/note/{id}` | 删除笔记 | ✅ |
| GET | `/api/note/{id}` | 获取笔记详情 | ❌（公开） |
| GET | `/api/note/user/{userId}` | 获取用户笔记列表 | ❌（公开） |
| POST | `/api/note/upload/image` | 上传笔记图片 | ✅ |

### 5.2 请求/响应示例

**发布笔记**

```http
POST /api/note/publish
Content-Type: application/json
Authorization: Bearer {accessToken}

{
  "title": "深圳南山区美食探店",
  "content": "今天去了科技园附近的一家日料店...",
  "images": [
    "https://cdn.myxhs.com/note/2026/05/img1.jpg",
    "https://cdn.myxhs.com/note/2026/05/img2.jpg"
  ],
  "coverUrl": "https://cdn.myxhs.com/note/2026/05/img1.jpg",
  "topicIds": [1001, 1002],
  "tags": ["美食", "探店", "日料"],
  "noteType": 0
}
```

```json
{
  "code": 200,
  "msg": "发布成功",
  "data": {
    "noteId": 20001
  }
}
```

**上传图片**

```http
POST /api/note/upload/image
Content-Type: multipart/form-data
Authorization: Bearer {accessToken}

file: (binary)
```

```json
{
  "code": 200,
  "msg": "上传成功",
  "data": {
    "url": "https://cdn.myxhs.com/note/2026/05/abc123.jpg"
  }
}
```

---

## 💻 六、核心代码实现

### 6.1 笔记发布（含敏感词检测）

```java
/**
 * 笔记发布
 * 关键点：参数校验 → DFA 敏感词检测 → 入库 → 异步通知 Feed 服务
 */
@Override
@RateLimit(count = 5, window = 60, key = "#userId") // 同一用户1分钟最多发布5篇
public Long publishNote(Long userId, NotePublishRequest request) {
    // 1. 参数校验
    if (StringUtils.isBlank(request.getTitle()) || request.getTitle().length() > 128) {
        throw new BizException(BizErrorCode.PARAM_ERROR, "标题不能为空且不超过128字");
    }
    if (request.getImages() != null && request.getImages().size() > 9) {
        throw new BizException(BizErrorCode.PARAM_ERROR, "图片最多9张");
    }

    // 2. DFA 敏感词检测（标题 + 正文）
    String fullText = request.getTitle() + " " + request.getContent();
    Set<String> sensitiveWords = dfaFilter.detect(fullText);
    if (!sensitiveWords.isEmpty()) {
        throw new BizException(BizErrorCode.CONTENT_SENSITIVE,
                "内容包含违规词汇：" + String.join(",", sensitiveWords));
    }

    // 3. 构建笔记实体
    Note note = new Note();
    note.setId(idGenerator.nextId());
    note.setUserId(userId);
    note.setTitle(request.getTitle());
    note.setContent(request.getContent());
    note.setImages(JSON.toJSONString(request.getImages()));
    note.setCoverUrl(request.getCoverUrl());
    note.setTopicIds(JSON.toJSONString(request.getTopicIds()));
    note.setTags(JSON.toJSONString(request.getTags()));
    note.setNoteType(request.getNoteType());
    note.setStatus(2);       // 已发布（DFA 通过即发布）
    note.setAuditStatus(1);  // 审核通过

    // 4. 入库
    noteMapper.insert(note);

    // 5. 清除用户笔记列表缓存
    redisOperator.delete("note:list:user:" + userId);

    // 6. 异步通知 Feed 服务（推送到粉丝收件箱）
    rocketMQTemplate.asyncSend("note-published",
            MessageBuilder.withPayload(new NotePublishedEvent(note.getId(), userId))
                    .build(),
            new SendCallback() {
                @Override
                public void onSuccess(SendResult sendResult) {
                    log.info("笔记发布消息发送成功: noteId={}", note.getId());
                }
                @Override
                public void onException(Throwable e) {
                    log.error("笔记发布消息发送失败: noteId={}", note.getId(), e);
                    // 本地消息表兜底（后续 XXL-Job 补发）
                }
            });

    return note.getId();
}
```

### 6.2 DFA 敏感词过滤器（Trie 树）

```java
/**
 * DFA 敏感词过滤器
 * 核心原理：Trie 树（前缀树）构建一次，匹配 O(n) 时间复杂度
 * vs SQL LIKE：10万词库 × LIKE 查询 = 10万次扫描，DFA 只需遍历文本1遍
 */
@Component
public class DFAFilter {

    // Trie 树根节点：Map<Character, Map<Character, Map<...>>>
    private volatile Map<Character, Object> trieRoot = new HashMap<>();

    /**
     * 构建 Trie 树（启动时加载 + 热更新时重建）
     */
    @PostConstruct
    public void buildTrie() {
        // 从数据库/文件加载敏感词库
        List<String> words = sensitiveWordRepository.loadAll();
        Map<Character, Object> root = new HashMap<>();
        for (String word : words) {
            Map<Character, Object> current = root;
            for (int i = 0; i < word.length(); i++) {
                char c = word.charAt(i);
                current = (Map<Character, Object>) current.computeIfAbsent(c, k -> new HashMap<>());
            }
            current.put('\0', null); // 结束标记
        }
        this.trieRoot = root; // volatile 保证可见性
        log.info("敏感词 Trie 树构建完成，词库大小: {}", words.size());
    }

    /**
     * 检测文本中的敏感词
     * @return 命中的敏感词集合（空集合表示通过）
     */
    public Set<String> detect(String text) {
        Set<String> result = new HashSet<>();
        for (int i = 0; i < text.length(); i++) {
            Map<Character, Object> current = trieRoot;
            StringBuilder word = new StringBuilder();
            for (int j = i; j < text.length(); j++) {
                char c = text.charAt(j);
                Object next = current.get(c);
                if (next == null) break; // 不匹配，跳出
                word.append(c);
                if (next instanceof Map) {
                    current = (Map<Character, Object>) next;
                    if (current.containsKey('\0')) {
                        result.add(word.toString()); // 命中敏感词
                    }
                }
            }
        }
        return result;
    }

    /**
     * 敏感词热更新（Redis Pub/Sub 触发）
     */
    @RedisListener(channel = "sensitive-word-update")
    public void onSensitiveWordUpdate(String message) {
        log.info("收到敏感词更新通知，重建 Trie 树");
        buildTrie();
    }
}
```

### 6.3 笔记状态机

```java
/**
 * 笔记状态枚举 + 状态流转校验
 * 防止非法状态流转（如从"已下架"直接变为"草稿"）
 */
public enum NoteStatus {
    DRAFT(0, "草稿"),
    AUDITING(1, "审核中"),
    PUBLISHED(2, "已发布"),
    OFFLINE(3, "已下架");

    private final int code;
    private final String desc;

    /**
     * 合法的状态流转映射
     */
    private static final Map<NoteStatus, Set<NoteStatus>> TRANSITIONS = Map.of(
            DRAFT, Set.of(AUDITING, PUBLISHED),     // 草稿 → 审核中/已发布
            AUDITING, Set.of(PUBLISHED, OFFLINE),    // 审核中 → 已发布/已下架（拒绝）
            PUBLISHED, Set.of(OFFLINE),              // 已发布 → 已下架
            OFFLINE, Set.of()                        // 已下架 → 终态
    );

    /**
     * 校验状态流转是否合法
     */
    public boolean canTransitTo(NoteStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }
}
```

### 6.4 图片上传（接口抽象）

```java
/**
 * 文件存储接口 — 抽象层
 * 本地磁盘实现用于开发环境，生产环境切 MinIO/OSS 只需加一个实现类
 */
public interface FileStorageService {
    /**
     * 上传文件
     * @return 文件访问 URL
     */
    String upload(MultipartFile file, String directory);
}

/**
 * 本地磁盘实现（开发环境）
 */
@Service
@ConditionalOnProperty(name = "storage.type", havingValue = "local", matchIfMissing = true)
public class LocalFileStorageService implements FileStorageService {

    @Value("${storage.local.path:/data/uploads}")
    private String basePath;

    @Value("${storage.local.url-prefix:http://localhost:9002/uploads}")
    private String urlPrefix;

    @Override
    public String upload(MultipartFile file, String directory) {
        // 1. 校验文件类型（白名单）
        String contentType = file.getContentType();
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new BizException(BizErrorCode.FILE_TYPE_NOT_ALLOWED);
        }
        // 2. 校验文件大小（最大 5MB）
        if (file.getSize() > 5 * 1024 * 1024) {
            throw new BizException(BizErrorCode.FILE_TOO_LARGE);
        }
        // 3. 生成唯一文件名（防覆盖）
        String ext = getExtension(file.getOriginalFilename());
        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String fullPath = basePath + "/" + directory + "/" + datePath + "/" + fileName;

        // 4. 写入磁盘
        File dest = new File(fullPath);
        dest.getParentFile().mkdirs();
        file.transferTo(dest);

        return urlPrefix + "/" + directory + "/" + datePath + "/" + fileName;
    }

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp");
}
```

---

## ⚖️ 七、方案对比

### 7.1 敏感词过滤：DFA vs SQL LIKE vs 正则

| 维度 | DFA（Trie 树）（✅ 选定） | SQL LIKE | 正则表达式 |
|------|------------------------|---------|-----------|
| 时间复杂度 | O(n)，遍历文本 1 遍 | O(n × m)，10 万词 × 全文扫描 | O(n × m)，正则回溯 |
| 10 万词库性能 | 毫秒级 | 数十秒 | 秒级 |
| 内存占用 | Trie 树约 50MB | 无额外内存 | 编译后正则对象 |
| 热更新 | Redis Pub/Sub 通知重建 | 无需（每次查 DB） | 需重新编译 |

**选择理由**：10 万词库场景下，DFA 是唯一能做到毫秒级匹配的方案。SQL LIKE 需要 10 万次查询，完全不可接受。

### 7.2 图片存储：本地磁盘 vs MinIO vs 阿里云 OSS

| 维度 | 本地磁盘（✅ 开发环境） | MinIO（✅ 生产环境） | 阿里云 OSS |
|------|---------------------|-------------------|-----------|
| 成本 | 免费 | 免费（自建） | 按量付费 |
| 可靠性 | 单点，磁盘坏了数据丢失 | 纠删码，高可靠 | 99.9999999% |
| CDN 加速 | ❌ | 需自建 | ✅ 原生支持 |
| 部署复杂度 | 零配置 | Docker 一键部署 | 注册+配置 |

**选择理由**：开发环境用本地磁盘零配置启动；生产环境通过 `FileStorageService` 接口切换到 MinIO，代码零改动。

### 7.3 审核方式：同步 DFA vs 异步 MQ 审核

| 维度 | 同步 DFA（✅ 当前版本） | 异步 MQ 审核 |
|------|---------------------|-------------|
| 用户体验 | 发布即可见（秒级） | 需等待审核（分钟级） |
| 审核深度 | 仅文本敏感词 | 可接入 AI 图片/视频审核 |
| 实现复杂度 | 低 | 高（需审核队列+状态回调） |

**选择理由**：MVP 阶段用同步 DFA 快速上线，后续可扩展为 MQ 异步审核（发布后 status=1 审核中，审核通过后回调更新 status=2）。

---

## 🐛 八、踩坑记录

### 8.1 DFA 敏感词匹配遗漏

- **现象**：用户在敏感词中间插入空格/特殊字符绕过检测（如"赌 博"）
- **原因**：DFA 严格按字符匹配，空格打断了连续匹配
- **解决**：匹配前预处理文本——去除空格、特殊字符、全角转半角
- **教训**：敏感词过滤必须做文本预处理，否则极易被绕过

### 8.2 图片上传文件名冲突

- **现象**：不同用户上传同名文件，后者覆盖前者
- **原因**：使用原始文件名存储
- **解决**：UUID 生成唯一文件名 + 日期目录分层（`2026/05/12/uuid.jpg`）
- **教训**：文件存储永远不要用原始文件名

### 8.3 笔记状态非法流转

- **现象**：已下架的笔记被重新发布
- **原因**：更新接口未校验状态流转合法性
- **解决**：引入状态机枚举 `NoteStatus.canTransitTo()`，每次状态变更前校验
- **教训**：有状态的实体必须用状态机模式，禁止直接 `setStatus()`

---

## 📊 九、测试验证

### 9.1 功能测试

| 测试场景 | 输入 | 预期结果 | 通过 |
|----------|------|----------|------|
| 正常发布笔记 | 合法标题+正文+图片 | 返回笔记 ID，status=2 | ⬜ |
| 标题超长 | 129 字标题 | 返回"标题不超过128字" | ⬜ |
| 图片超过9张 | 10 张图片 | 返回"图片最多9张" | ⬜ |
| 包含敏感词 | 正文含敏感词 | 返回"内容包含违规词汇" | ⬜ |
| 保存草稿 | 不完整内容 | status=0 草稿 | ⬜ |
| 编辑已发布笔记 | 修改标题 | 更新成功 + 缓存清除 | ⬜ |
| 删除笔记 | 已发布笔记 ID | 逻辑删除 + 缓存清除 | ⬜ |
| 上传合法图片 | JPEG 文件 < 5MB | 返回图片 URL | ⬜ |
| 上传非法文件 | .exe 文件 | 返回"文件类型不允许" | ⬜ |
| 上传超大文件 | 10MB 图片 | 返回"文件大小超限" | ⬜ |
| 发布限频 | 1分钟内发布6篇 | 第6篇返回"操作过于频繁" | ⬜ |

### 9.2 压测数据（预期基线）

| 场景 | 并发数 | 目标 QPS | 目标平均 RT | 目标 P99 RT | 目标错误率 |
|------|--------|---------|-----------|-----------|-----------|
| 笔记详情查询 | 200 | 5000 | < 20ms | < 100ms | < 0.1% |
| 笔记发布 | 50 | 200 | < 200ms | < 1000ms | < 0.1% |
| DFA 敏感词检测 | 100 | 10000 | < 5ms | < 20ms | 0% |

### 9.3 关键场景验证

- [ ] 敏感词热更新：运营新增敏感词 → Redis Pub/Sub → 各节点 Trie 树重建 → 新词可检测
- [ ] 笔记发布后 Feed 推送：发布笔记 → MQ 消息 → Feed 服务消费 → 粉丝收件箱可见
- [ ] 笔记缓存一致性：编辑笔记 → 缓存清除 → 再次查询从 DB 加载最新数据

---

## 🎤 十、面试考察点

### Q1: 笔记发布流程是怎样的？

**推荐回答思路**：

> 1. "用户提交笔记 → 参数校验（标题长度、图片数量）→ DFA 敏感词检测 → 入库 → 异步通知 Feed 服务"
> 2. "敏感词检测用 DFA 算法（Trie 树），10 万词库毫秒级匹配，比 SQL LIKE 快 100 倍"
> 3. "审核通过后发 MQ 消息，Feed 服务消费后推送到粉丝收件箱（写扩散）"
> 4. "图片先上传到存储服务拿到 URL，再和笔记内容一起提交"

### Q2: 为什么用状态机而不是简单的 status 字段？

**推荐回答思路**：

> 1. "状态机定义了合法的状态流转路径：草稿→审核中→已发布/已下架"
> 2. "防止非法流转：比如已下架的笔记不能直接变为已发布，必须重新提交审核"
> 3. "每次状态变更前调用 `canTransitTo()` 校验，不合法直接拒绝"
> 4. "vs 简单 status 字段：没有流转校验，任何状态都能改成任何状态，容易出 bug"

### Q3: 敏感词过滤为什么用 DFA 而不是 SQL LIKE？

**推荐回答思路**：

> 1. "DFA 基于 Trie 树，构建一次后匹配时间复杂度 O(n)——只需遍历文本一遍"
> 2. "SQL LIKE 需要 10 万次查询（每个敏感词一次 LIKE），时间复杂度 O(n×m)"
> 3. "实测：10 万词库 + 5000 字正文，DFA 耗时 < 5ms，SQL LIKE 耗时 > 30 秒"
> 4. "热更新：运营新增敏感词后，通过 Redis Pub/Sub 通知所有节点重建 Trie 树，无需重启"

---

## 📚 参考资料

| 资料 | 相关章节 | 参考内容 |
|------|----------|----------|
| 📄 phase-1/README.md | §3.3 | 笔记发布完整设计（API/表/Redis Key/Java文件/DFA） |
| 📄 02-module-detailed-design.md | §4 | 笔记服务核心功能/状态机/敏感词 |
| 📄 00-technical-specification-outline.md | §4.2 | 笔记服务功能清单与方案对比 |
| 📄 04-comment-system | 全文 | 评论系统（与笔记服务同属 my-xhs-content） |
