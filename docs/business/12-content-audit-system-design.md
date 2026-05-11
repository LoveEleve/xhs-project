# my-xhs 内容审核系统设计

> 内容平台的生命线。小红书每天新增10万+笔记，纯人工审核不可能。
> 分层审核：机器审核（实时）+ 人工审核（复审）+ 用户举报（兜底）。

---

## 一、审核体系总览

```
┌───────────────────────────────────────────────────────┐
│                    内容发布入口                         │
│         笔记发布 / 评论发表 / 用户资料修改              │
└───────────────────────┬───────────────────────────────┘
                        │
                 ┌──────▼──────┐
                 │ 第一层       │  实时审核（<100ms）
                 │ 机器审核     │  敏感词 + 图片分类 + 风控
                 └──────┬──────┘
                        │
              ┌─────────┴─────────┐
              │                   │
        ┌─────▼─────┐      ┌─────▼─────┐
        │ 通过       │      │ 疑似/拒绝  │
        │ 直接发布   │      │ 进入审核队列│
        └───────────┘      └─────┬─────┘
                                 │
                          ┌──────▼──────┐
                          │ 第二层       │  延迟审核（<4小时）
                          │ 人工审核     │  审核员复审
                          └──────┬──────┘
                                 │
                          ┌──────┴──────┐
                          │             │
                    ┌─────▼────┐  ┌─────▼────┐
                    │ 通过发布  │  │ 拒绝+通知 │
                    └──────────┘  └──────────┘

        ┌───────────────────────────────────┐
        │ 第三层：用户举报（兜底）            │
        │ 用户举报 → 审核员处理 → 处罚       │
        └───────────────────────────────────┘
```

---

## 二、第一层：机器审核（实时）

### 2.1 文本审核 — DFA敏感词过滤

> 已有设计（02-模块详细设计/笔记服务），此处补充完整方案

```
DFA敏感词过滤（已有）
├── Trie前缀树构建
├── 10万词库毫秒级匹配
├── O(n)复杂度（n=文章长度）
└── 热更新机制（运营新增词实时生效）
```

**敏感词分类与处理策略**：

| 分类 | 示例 | 处理策略 | 是否需要人工复审 |
|------|------|---------|---------------|
| 🚫 违禁词 | 毒品、枪支、代孕 | **直接拒绝**，不允许发布 | 否 |
| ⚠️ 敏感词 | 政治人物、敏感事件 | **自动替换为***，允许发布 | 抽样复审 |
| 🔍 疑似词 | 双关语、隐晦表达 | **标记待审**，进入人工队列 | 是 |
| 💬 灰名单词 | 广告用语、引流话术 | **降权处理**，限流展示 | 抽样复审 |

**DFA增强 — 模糊匹配**：

```
基础DFA只能精确匹配，需要增强：
1. 繁简体转换：體→体、國→国
2. 拼音匹配：mg→蘑菇、dp→大炮
3. 拆字匹配：弓虽→强、木几→机
4. 特殊符号过滤：敏*感*词→敏感词
5. 同音字替换：敏干词→敏感词

实现：
- 文本预处理Pipeline：去特殊符号→繁简转换→拼音还原→拆字合并
- 预处理后再走DFA匹配
```

### 2.2 图片审核

```
图片审核是当前文档完全缺失的部分，这是内容平台的核心能力。

方案选型：
┌────────────────┬──────────┬──────────┬──────────┐
│ 方案           │ 准确率   │ 延迟     │ 成本     │
├────────────────┼──────────┼──────────┼──────────┤
│ 自建模型       │ 85-90%   │ 200-500ms│ GPU服务器 │
│ 云服务API      │ 95%+     │ 100-300ms│ 按量付费  │
│ 开源模型+微调  │ 90-95%   │ 300-800ms│ GPU+人力  │
└────────────────┴──────────┴──────────┴──────────┘

my-xhs选型：接口抽象 + 本地Mock实现
（与支付模块同理：接口抽象，Mock跑通链路，生产切云服务）
```

**图片审核接口设计**：

```java
/**
 * 图片审核服务接口
 * 生产环境切换为阿里云/腾讯云内容安全API
 */
public interface ImageAuditService {

    /**
     * 审核单张图片
     * @param imageUrl 图片URL
     * @return 审核结果
     */
    ImageAuditResult audit(String imageUrl);

    /**
     * 批量审核（笔记最多9张图）
     * @param imageUrls 图片URL列表
     * @return 审核结果列表
     */
    List<ImageAuditResult> batchAudit(List<String> imageUrls);
}

@Data
public class ImageAuditResult {
    private Boolean pass;           // 是否通过
    private String category;        // 风险类别：porn/terrorism/ad/politics/violence/normal
    private Double confidence;      // 置信度 0-1
    private String reason;          // 不通过原因
    private Boolean needManualReview; // 是否需要人工复审
}

/**
 * Mock实现（开发/测试环境）
 */
@Service
@Profile({"dev", "test"})
public class MockImageAuditService implements ImageAuditService {
    @Override
    public ImageAuditResult audit(String imageUrl) {
        // Mock逻辑：根据图片URL尾号模拟不同审核结果
        ImageAuditResult result = new ImageAuditResult();
        result.setPass(true);
        result.setCategory("normal");
        result.setConfidence(0.99);
        result.setNeedManualReview(false);
        return result;
    }
}
```

**图片审核分类标准**：

| 类别 | 说明 | 处理策略 | 置信度阈值 |
|------|------|---------|-----------|
| `porn` | 色情/低俗 | 直接拒绝 | >0.85 |
| `terrorism` | 暴恐 | 直接拒绝 | >0.80 |
| `politics` | 政治敏感 | 直接拒绝 | >0.80 |
| `ad` | 广告/引流 | 标记待审 | >0.70 |
| `violence` | 暴力/血腥 | 标记待审 | >0.75 |
| `normal` | 正常 | 通过 | — |

### 2.3 风控审核

```
基于用户行为的实时风控，在发布前拦截

风控规则：
┌────────────────┬────────────────────┬──────────┐
│ 规则           │ 触发条件            │ 处理动作  │
├────────────────┼────────────────────┼──────────┤
│ 注册时间风控    │ 注册<24小时发笔记   │ 进入人工  │
│ 频率风控       │ 1分钟内发3篇以上    │ 限流+标记 │
│ 内容重复风控    │ 与已发笔记相似度>80%│ 标记待审  │
│ IP风控        │ 同IP多账号发布      │ 标记待审  │
│ 历史违规风控    │ 近7天被拒≥2次      │ 全部人工  │
└────────────────┴────────────────────┴──────────┘
```

---

## 三、第二层：人工审核

### 3.1 审核队列设计

```
t_audit_task（审核任务表）
├── id                BIGINT       主键
├── biz_type          VARCHAR(20)  业务类型：NOTE/COMMENT/USER_PROFILE
├── biz_id            BIGINT       业务ID
├── content_snapshot  TEXT         内容快照（审核时内容可能已被修改）
├── audit_type        VARCHAR(20)  审核类型：AUTO_REVIEW/MANUAL/REPORT
├── risk_tags         VARCHAR(200) 风险标签：porn,ad,politics（逗号分隔）
├── risk_level        TINYINT      风险等级：1低/2中/3高
├── status            VARCHAR(20)  状态：PENDING/APPROVED/REJECTED/APPEALING
├── auditor_id        BIGINT       审核员ID
├── audit_result      VARCHAR(20)  审核结果
├── audit_remark      VARCHAR(500) 审核备注
├── audit_time        DATETIME     审核时间
├── create_time       DATETIME     创建时间
└── update_time       DATETIME     更新时间

索引：
- idx_biz (biz_type, biz_id)      -- 按业务查询
- idx_status_risk (status, risk_level) -- 审核员按优先级领取
- idx_auditor (auditor_id, status) -- 审核员工作量统计
```

### 3.2 审核优先级

```
优先级排序（高优先级先审核）：
1. 🔴 风险等级3（暴恐/色情/政治）→ 30分钟内审核
2. 🟠 用户举报内容                → 2小时内审核
3. 🟡 风险等级2（广告/低俗）      → 4小时内审核
4. 🟢 风险等级1（疑似/灰名单）    → 24小时内审核
```

### 3.3 审核员工作台

```
功能清单：
├── 待审核列表（按优先级排序）
├── 内容查看（笔记正文+图片+评论上下文）
├── 审核操作（通过/拒绝/删除/封号）
├── 审核理由（预置理由+自定义理由）
├── 批量操作（同类型内容批量通过/拒绝）
├── 历史记录（已审核内容回溯）
└── 统计看板（审核量/通过率/平均审核时长）
```

---

## 四、第三层：用户举报

### 4.1 举报流程

```
用户举报 → 创建举报记录 → 进入审核队列 → 审核员处理 → 处罚/驳回 → 通知举报人

举报类型（已有t_comment_report表，扩展为通用举报）：
├── 1-垃圾广告
├── 2-色情低俗
├── 3-政治敏感
├── 4-虚假信息
├── 5-侵权内容
├── 6-人身攻击
├── 7-不实谣言
├── 8-诱导消费
└── 9-其他
```

### 4.2 处罚体系

| 违规级别 | 首次 | 二次 | 三次 |
|----------|------|------|------|
| 轻微（广告/低俗） | 删除内容+警告 | 删除+禁言3天 | 禁言7天 |
| 中等（虚假/侵权） | 删除+禁言7天 | 禁言30天 | 永久封号 |
| 严重（暴恐/色情） | 删除+禁言30天 | 永久封号 | 永久封号+设备封禁 |

### 4.3 申诉机制

```
处罚通知 → 用户申诉 → 申诉审核（不同审核员） → 维持/撤销处罚

申诉规则：
- 7天内可申诉
- 每次处罚限1次申诉
- 申诉由资深审核员处理
- 撤销处罚后恢复内容/解封账号
```

---

## 五、审核与发布流程集成

### 5.1 笔记发布审核流程

```java
@Service
public class NotePublishService {

    @Autowired private TextAuditService textAuditService;   // DFA敏感词
    @Autowired private ImageAuditService imageAuditService; // 图片审核
    @Autowired private RiskControlService riskControlService;// 风控
    @Autowired private AuditTaskService auditTaskService;    // 审核任务

    /**
     * 笔记发布 — 三层审核
     */
    public NotePublishResult publish(NotePublishCmd cmd) {
        // 1. 风控检查
        RiskResult risk = riskControlService.check(cmd.getUserId());
        if (risk.isBlock()) {
            return NotePublishResult.blocked("账号异常，请联系客服");
        }

        // 2. 文本审核（DFA，<10ms）
        TextAuditResult textResult = textAuditService.audit(cmd.getTitle() + cmd.getContent());

        // 3. 图片审核（并行审核最多9张图）
        List<ImageAuditResult> imageResults = Collections.emptyList();
        if (!CollectionUtils.isEmpty(cmd.getImageUrls())) {
            imageResults = imageAuditService.batchAudit(cmd.getImageUrls());
        }

        // 4. 综合判定
        if (textResult.isDirectReject() || hasDirectRejectImage(imageResults)) {
            // 违禁内容：直接拒绝
            return NotePublishResult.rejected("内容违规，请修改后重新发布");
        }

        if (textResult.isAutoPass() && isAllImagePass(imageResults)) {
            if (risk.isTrustUser()) {
                // 可信用户+内容通过：直接发布
                Note note = saveAndPublish(cmd);
                return NotePublishResult.published(note.getId());
            }
        }

        // 疑似/需复审：保存为草稿状态，进入审核队列
        Note note = saveAsPending(cmd);
        auditTaskService.createTask(note, textResult, imageResults, risk);
        return NotePublishResult.pendingReview(note.getId());
    }
}
```

### 5.2 评论审核流程

```
评论审核相对简单：
├── DFA敏感词过滤（必做）
├── 图片审核（评论有图时）
├── 风控检查
└── 不做人工审核（量太大），但举报后进入审核队列

评论状态：
PENDING_REVIEW → APPROVED / REJECTED
            ↑ 举报
APPROVED → REPORTED → PENDING_REVIEW（复审）
```

---

## 六、审核数据统计与运营

### 6.1 核心指标

| 指标 | 计算方式 | 目标值 |
|------|---------|-------|
| 机器审核通过率 | 机器通过数/总提交数 | >85% |
| 人工审核准确率 | 人工正确数/人工审核总数 | >95% |
| 审核时效（P0） | 高风险内容审核时长 | <30分钟 |
| 审核时效（P1） | 普通内容审核时长 | <4小时 |
| 误杀率 | 人工撤销数/机器拒绝数 | <5% |
| 漏检率 | 举报确认违规数/总发布数 | <0.1% |

### 6.2 审核报表

```
日报：
├── 新增笔记数 / 审核通过数 / 拒绝数 / 待审数
├── 违规类型分布（色情/广告/政治/暴力）
├── 审核员工作量（审核数/通过率/平均时长）
└── 举报处理统计

周报：
├── 违规趋势（是否上升/下降）
├── 敏感词库更新建议
├── 审核模型准确率变化
└── 热点事件专项审核
```

---

## 七、数据库表设计补充

### 7.1 通用审核任务表

```sql
CREATE TABLE t_audit_task (
    id              BIGINT PRIMARY KEY COMMENT '主键',
    biz_type        VARCHAR(20) NOT NULL COMMENT '业务类型：NOTE/COMMENT/USER_PROFILE/REPORT',
    biz_id          BIGINT NOT NULL COMMENT '业务ID',
    content_snapshot TEXT COMMENT '内容快照',
    audit_source    VARCHAR(20) NOT NULL COMMENT '审核来源：AUTO/MANUAL/REPORT',
    risk_tags       VARCHAR(200) COMMENT '风险标签，逗号分隔',
    risk_level      TINYINT DEFAULT 1 COMMENT '风险等级：1低/2中/3高',
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态',
    auditor_id      BIGINT COMMENT '审核员ID',
    audit_result    VARCHAR(20) COMMENT '审核结果：APPROVED/REJECTED',
    audit_remark    VARCHAR(500) COMMENT '审核备注',
    audit_time      DATETIME COMMENT '审核时间',
    create_time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_biz (biz_type, biz_id),
    INDEX idx_status_risk (status, risk_level, create_time),
    INDEX idx_auditor (auditor_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审核任务表';
```

### 7.2 违规记录表

```sql
CREATE TABLE t_violation_record (
    id              BIGINT PRIMARY KEY COMMENT '主键',
    user_id         BIGINT NOT NULL COMMENT '违规用户ID',
    biz_type        VARCHAR(20) NOT NULL COMMENT '业务类型',
    biz_id          BIGINT NOT NULL COMMENT '业务ID',
    violation_type  VARCHAR(20) NOT NULL COMMENT '违规类型：PORN/AD/POLITICS/VIOLENCE/FAKE/OTHER',
    penalty         VARCHAR(20) NOT NULL COMMENT '处罚：DELETE_CONTENT/WARN/BAN_3D/BAN_7D/BAN_30D/PERMANENT_BAN',
    audit_task_id   BIGINT COMMENT '关联审核任务ID',
    is_appealed     TINYINT DEFAULT 0 COMMENT '是否已申诉',
    appeal_result   VARCHAR(20) COMMENT '申诉结果：UPHELD/OVERTURNED',
    create_time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user (user_id, create_time),
    INDEX idx_type (violation_type, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='违规记录表';
```

---

## 八、生产决策与表达

### Q: 内容审核怎么做的？

> "三层审核：第一层机器审核实时拦截——DFA敏感词过滤文本（10万词库O(n)匹配）+ 图片分类识别（接口抽象+Mock实现，生产切云服务API）+ 行为风控（注册时间/频率/IP/历史违规）。第二层人工审核——疑似内容进审核队列，按风险等级排序，高风险30分钟内审核。第三层用户举报兜底——举报进入审核队列，审核后处罚。处罚分3级9等，支持申诉。"

### Q: DFA敏感词有什么缺陷？怎么弥补？

> "DFA只能精确匹配文本，三个缺陷：1.图片/视频无法审核→接入图片审核API；2.变体词无法识别（繁体/拆字/拼音）→文本预处理Pipeline（繁简转换+拆字合并+拼音还原）再走DFA；3.语义理解不足→疑似内容进人工复审队列，用人工兜底机器的不足。"

### Q: 100万DAU每天10万篇笔记，审核人力够吗？

> "机器审核通过率>85%，即每天只有约1.5万篇需要人工审核。按每个审核员每天审500篇计算，需要30个审核员。高风险内容30分钟时效要求下，需要3班倒，约40-50人审核团队。这是内容平台的标准配置——小红书审核团队超千人。"
