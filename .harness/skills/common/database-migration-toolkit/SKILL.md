---
name: database-migration-toolkit
stage: 组件封装
description: 数据库迁移工具封装——迁移模板、回滚脚本、数据回填辅助、兼容性检查模板
---

# 数据库迁移工具封装（database-migration-toolkit）

## 1. 职责

为项目生成数据库迁移全套脚本，包括正向迁移 SQL、回滚 SQL、数据回填脚本、兼容性检查清单。**禁止在无回滚脚本的情况下执行迁移**。

## 2. 触发方式

| 方式 | 说明 |
|------|------|
| 独立命令 | `/database-migration-toolkit <migration-description>` |
| 自动加载 | coding-skill 阶段检测到 `V*__*.sql` / `migrations/` / `ALTER TABLE` 时 |

## 3. 前置条件

- 用户已描述迁移内容（如"订单表新增 payment_method 字段"）
- 已确定迁移工具（Flyway / Liquibase / 原生 SQL）
- 已确定目标数据库类型（MySQL / PostgreSQL 等）
- 已确认涉及的表名、列名、索引名

## 4. 工作流程

### Step 1: 解析迁移需求

从用户输入或 change.md 中提取：
- 变更类型：DDL（新增表/列/索引/修改列/删除列）or DML（数据回填/数据修正）
- 涉及表名、列名、数据类型、约束
- 是否影响已有数据（新增 NOT NULL 列需 DEFAULT 值）
- 大表操作（>1000 万行需 Online DDL 工具）

### Step 2: 生成迁移脚本

```
# 假设迁移描述: "order 表新增 payment_method 字段"
# 生成版本号: 从当前最大版本号递增

migrations/
├── V{version}__{description}.sql      — 正向迁移
├── R{version}__{description}.sql      — 回滚迁移
└── backfill/
    └── V{version}__backfill_{description}.sql  — 数据回填（如需）
```

**正向迁移模板约束**：
- 新增列：`ALTER TABLE ADD COLUMN ... AFTER ...`，允许 NULL（向前兼容）
- 新增索引：`ALTER TABLE ... ADD INDEX ... ALGORITHM=INPLACE, LOCK=NONE`
- 重命名列：先 ADD 新列，再 UPDATE 数据，再 DROP 旧列（兼容期）
- 删除列：先标记废弃，观察一周期再删除（或者先 DROP 索引，列保留）
- 大表操作：注释中注明使用 gh-ost / pt-online-schema-change

**回滚脚本模板约束**：
- 与正向迁移一一对应，同一个版本号
- 回滚后数据影响：在文件头注释中明确说明（如"回滚会丢失 payment_method 数据"）
- 大表回滚同样需要 Online 方式

**数据回填脚本约束**：
- 分批执行（每批 1000 条，间隔 100ms）
- 游标分页（非 OFFSET 分页，防深度分页性能问题）
- 幂等：WHERE 条件确保可重入
- 有进度日志（每批打印当前进度）

### Step 3: 生成兼容性检查清单

根据迁移内容自动生成检查项：

```
## 兼容性检查清单

### DDL 检查
- [ ] 新增 NOT NULL 列 → 是否有 DEFAULT 值？
- [ ] 列类型变更 → 是否兼容（varchar 扩长安全，缩长会截断）？
- [ ] 重命名列/表 → 是否保留了旧名（兼容期）？
- [ ] 删除列/表 → 是否确认无引用（先废弃再删除）？
- [ ] 大表 ALTER → 是否使用 ONLINE DDL（gh-ost / pt-online）？

### 索引检查
- [ ] 新增索引 → 是否评估了写入性能影响？
- [ ] 新增索引 → 是否与已有索引冗余？
- [ ] 索引字段顺序 → 是否匹配查询（最左前缀）？

### 回滚检查
- [ ] 回滚脚本 → 是否已准备？
- [ ] 回滚后数据 → 是否可恢复？
- [ ] 应用兼容 → 新旧代码是否同时兼容新旧 schema？
```

### Step 4: 验证

1. 正向迁移 SQL 语法正确：`mysql -e "source V{version}__xxx.sql"`
2. 回滚脚本与正向迁移对应：版本号一致，操作为逆向
3. 数据回填 SQL 可重入：同一脚本执行两次结果一致
4. 检查清单逐项确认

## 5. 输出文件清单

```
migrations/
├── V{version}__{description}.sql
├── R{version}__{description}.sql
└── backfill/
    └── V{version}__backfill_{description}.sql  (按需)

migration-checklist.md  (兼容性检查清单)
```

## 6. 质量门禁

- ✅ 正向迁移 + 回滚脚本成对存在
- ✅ 正向迁移向前兼容（新增列允许 NULL，不删除已有字段）
- ✅ 数据回填脚本幂等可重入
- ✅ 大表操作使用 Online DDL 工具
- ❌ 禁止无回滚脚本的迁移
- ❌ 禁止 NOT NULL 列无 DEFAULT 值
- ❌ 禁止大表直接 ALTER（无 Online DDL 注释）

## 7. 约束

- ❌ 不生成数据库无关的 SQL（只为目标数据库类型生成）
- ❌ 不修改已有迁移脚本（只新增版本号递增的迁移）
- ❌ 不擅自执行迁移（脚本只生成，不自动执行）
- ❌ 不生成回填数据的具体业务逻辑（只生成回填框架，业务逻辑由用户填写）
---

> **来源 & 作者**
> - 公众号：华仔聊技术
> - 知识星球：华仔·AI高并发全栈训练营
> - 作者：王江华@huazai
