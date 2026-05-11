# my-xhs 数据库变更管理（DDL Migration）方案

> 生产环境DDL变更是高风险操作——ALTER TABLE可能锁表、数据丢失、主从延迟。
> 必须有版本化管理 + 回滚方案。

---

## 一、为什么需要 DDL Migration 管理

```
血泪教训：
1. ALTER TABLE添加字段 → 锁表2小时 → 线上故障
2. 删错列 → 数据不可恢复 → 级联故障
3. 多人并行修改 → Schema冲突 → 部署失败
4. 忘记同步测试环境 → 线上和本地Schema不一致 → Bug
5. 回滚时不知道回滚到哪个版本 → 手忙脚乱
```

---

## 二、方案选型

| 工具 | 优点 | 缺点 | 选择 |
|------|------|------|------|
| Flyway | 简单、SQL原生、社区活跃 | 不支持回滚（需付费版） | ✅ 推荐 |
| Liquibase | 支持回滚、XML/YAML/JSON | 配置复杂、学习成本高 | 备选 |
| 手动SQL脚本 | 无依赖 | 不可追踪、不可回滚 | ❌ 禁止 |

**选型**：Flyway（简单可靠，符合项目"问题驱动"理念）

---

## 三、Flyway 集成方案

### 3.1 依赖引入

```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
    <!-- 与Spring Boot版本对齐 -->
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
</dependency>
```

### 3.2 配置

```yaml
spring:
  flyway:
    enabled: true
    # 迁移脚本位置
    locations: classpath:db/migration
    # 命名规范：V{版本号}__{描述}.sql
    # 例如：V1_0_0__init_schema.sql
    #      V1_0_1__add_note_tags_column.sql
    # 版本号规则：主版本.次版本.补丁版本
    baseline-on-migrate: true    # 已有数据库首次执行时创建基线
    baseline-version: 0          # 基线版本
    # 清理（❌ 生产环境必须false）
    clean-disabled: true
    # 校验
    validate-on-migrate: true    # 启动时校验已执行的脚本是否被篡改
```

### 3.3 脚本命名规范

```
db/migration/
├── V1_0_0__init_schema.sql                    # 初始化所有表
├── V1_0_1__add_note_tags_column.sql           # 笔记表增加标签列
├── V1_0_2__add_audit_task_table.sql           # 新增审核任务表
├── V1_1_0__add_violation_record_table.sql     # 新增违规记录表
├── V1_1_1__add_seller_order_index.sql         # 订单表增加卖家维度索引
├── U1_0_1__add_note_tags_column.sql           # 回滚脚本（Undo）
└── R__refresh_hot_notes_view.sql              # 可重复执行的脚本（视图/存储过程）

命名规则：
  V  = Versioned（只执行一次）
  U  = Undo（回滚脚本）
  R  = Repeatable（每次校验变化后重新执行）
  版本号之间用双下划线__分隔
  文件名不能修改（已执行的脚本修改会导致校验失败）
```

---

## 四、DDL变更规范

### 4.1 安全变更 vs 危险变更

| 类型 | 安全变更（可在线执行） | 危险变更（需停机/灰度） |
|------|---------------------|----------------------|
| 新增 | ADD COLUMN（MySQL 8.0 Instant ADD） | ADD INDEX（大表可能耗时） |
| 新增 | CREATE TABLE | ADD COLUMN（大表+无Instant） |
| 修改 | — | MODIFY COLUMN（锁表） |
| 修改 | — | CHANGE COLUMN（锁表） |
| 删除 | — | DROP COLUMN（数据不可恢复） |
| 删除 | — | DROP TABLE（数据不可恢复） |
| 重命名 | — | RENAME COLUMN（代码兼容） |

### 4.2 变更流程

```
DDL变更标准流程（6步法）：

Step 1: 编写迁移脚本
  └── V{version}__{description}.sql + U{version}__{description}.sql

Step 2: Code Review
  └── 架构师审核SQL（是否有锁表风险、是否兼容旧代码）

Step 3: 测试环境验证
  └── flywayMigrate → 验证功能 → flywayUndo → 验证回滚

Step 4: 预发布环境验证
  └── 使用生产数据副本验证

Step 5: 生产环境执行
  └── 低峰期执行（凌晨2-5点）
  └── 先从库验证→再主库执行
  └── 执行前备份

Step 6: 监控验证
  └── 主从延迟、慢查询、错误率
```

### 4.3 大表DDL变更策略

```
问题：ALTER TABLE 在大表（>1000万行）上可能锁表数小时

方案1：pt-online-schema-change（Percona工具）
  - 创建影子表 → 增量同步 → 交换表名
  - 不锁表，但有延迟
  - 适用于：ADD INDEX、ADD COLUMN、MODIFY COLUMN

方案2：gh-ost（GitHub工具）
  - 基于Binlog的在线DDL
  - 更安全、可暂停
  - 适用于：大表变更

方案3：MySQL 8.0 Instant DDL
  - ALTER TABLE ... ALGORITHM=INSTANT
  - 只修改元数据，不修改数据
  - 适用于：ADD COLUMN（在表末尾）、DROP INDEX

my-xhs选型：
  - 小表（<100万行）：直接ALTER TABLE
  - 大表（>100万行）：pt-online-schema-change
  - MySQL 8.0 Instant可用的：优先Instant
```

---

## 五、回滚方案

### 5.1 回滚策略矩阵

| 变更类型 | 回滚方式 | 回滚时间 | 数据风险 |
|----------|---------|---------|---------|
| 新增列 | DROP COLUMN | <1秒 | 无风险（新列数据丢失，可接受） |
| 新增索引 | DROP INDEX | <1秒 | 无风险 |
| 新增表 | DROP TABLE | <1秒 | 有风险（表内数据丢失） |
| 修改列类型 | 修改回原类型 | 可能锁表 | 有风险（数据截断） |
| 删除列 | 从备份恢复 | 小时级 | 高风险 |
| 重命名列 | RENAME回原名称 | <1秒 | 低风险 |

### 5.2 回滚脚本编写规范

```sql
-- 回滚脚本：U1_0_1__add_note_tags_column.sql
-- 对应迁移脚本：V1_0_1__add_note_tags_column.sql

-- 回滚：删除新增的列
ALTER TABLE t_note DROP COLUMN tags;

-- 回滚：删除新增的索引
-- DROP INDEX idx_note_tags ON t_note;

-- 注意：
-- 1. 回滚脚本必须能独立执行（不依赖其他脚本）
-- 2. 回滚脚本必须幂等（重复执行不报错）
-- 3. 回滚脚本必须先在测试环境验证
```

### 5.3 数据备份策略

```
DDL变更前必须备份：
  - 小表：mysqldump --single-transaction（不锁表）
  - 大表：xtrabackup（物理备份，速度快）
  - 特定表：CREATE TABLE t_xxx_backup_20260510 AS SELECT * FROM t_xxx

备份保留：
  - 变更前备份保留7天
  - 重要变更（删列/改类型）备份保留30天
```

---

## 六、多服务Schema管理

### 6.1 每个服务独立Schema

```
my-xhs-user     → db_user     → classpath:db/migration/user/
my-xhs-note     → db_note     → classpath:db/migration/note/
my-xhs-social   → db_social   → classpath:db/migration/social/
my-xhs-product  → db_product  → classpath:db/migration/product/
my-xhs-order    → db_order    → classpath:db/migration/order/
my-xhs-counter  → db_counter  → classpath:db/migration/counter/
```

### 6.2 Flyway多数据源配置

```java
@Configuration
public class FlywayConfig {

    // 主数据源自动迁移
    @Bean
    public Flyway flyway(DataSource dataSource) {
        Flyway flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load();
        flyway.migrate();
        return flyway;
    }
}
```

---

## 七、生产决策与表达

### Q: 数据库Schema变更是怎么管理的？

> "我们用Flyway做DDL版本化管理。每个变更是一个SQL文件，命名规范V{版本号}__{描述}.sql，同时编写对应的U回滚脚本。变更走6步流程：编写→Code Review→测试环境验证→预发布验证→生产低峰期执行→监控验证。大表变更用pt-online-schema-change避免锁表。变更前必做备份。"

### Q: 有没有遇到ALTER TABLE锁表的问题？

> "遇到过。一张1000万行的订单表加索引，直接ALTER TABLE锁了30分钟。之后改用pt-online-schema-change，创建影子表→增量同步→交换表名，全程不锁表。MySQL 8.0的Instant DDL也能解决部分场景——表末尾ADD COLUMN只需修改元数据，毫秒级完成。"
