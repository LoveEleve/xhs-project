---
name: mybatis-toolkit
stage: 组件封装
description: MyBatis 工具封装——通用分页（PageHelper 封装）、乐观锁插件、逻辑删除拦截器、自动填充、数据权限拦截器、批量操作优化
---

# MyBatis 工具封装（mybatis-toolkit）

> **Lang**: Java 特有（基于 MyBatis / MyBatis-Plus）
> **存放位置**: `harness-java/skills/`

## 1. 职责

为 Java 项目生成 MyBatis 基础设施代码，包括通用分页、乐观锁插件、逻辑删除拦截器、自动填充（创建时间/更新时间/操作人）、数据权限拦截器、批量操作优化。**禁止在业务代码中手写分页 SQL 和逻辑删除条件**。

## 2. 触发方式

| 方式 | 说明 |
|------|------|
| 独立命令 | `/mybatis-toolkit [page|optimistic|logic|fill|permission|batch|all]` |
| 自动加载 | coding-skill 阶段检测到 `MyBatis` / `MyBatis-Plus` / `SqlSessionFactory` / `Mapper.xml` 时 |

## 3. 前置条件

- 项目已识别为 Java + MyBatis/MyBatis-Plus 项目
- 已确定目标包路径（`{basePackage}.mybatis`）
- 已确定数据库表名+字段名（至少 1 张表）
- 用户未指定 → 生成全部组件（`all`）

## 4. 工作流程

### Step 1: 确定生成目标

| 子命令 | 组件 | 检测条件 |
|--------|------|---------|
| `page` | 通用分页 | 手写 `LIMIT OFFSET` 分页 |
| `optimistic` | 乐观锁 | 并发更新无锁保护 |
| `logic` | 逻辑删除 | 硬删除（DELETE 语句） |
| `fill` | 自动填充 | 实体无创建时间/更新时间自动填充 |
| `permission` | 数据权限 | 多租户/部门数据隔离 |
| `batch` | 批量操作 | 循环逐条执行 INSERT/UPDATE |
| `all`（默认） | 全部组件 | 首次引入 MyBatis 增强 |

### Step 2: 生成代码文件

```
{basePackage}/mybatis/
├── page/
│   ├── PageHelper.java                 — 分页助手（ThreadLocal 分页参数）
│   ├── PageResult.java                 — 分页结果（总条数/总页数/当前页/数据列表）
│   └── PageInterceptor.java            — 分页拦截器（自动改写 SQL）
├── plugin/
│   ├── OptimisticLockPlugin.java       — 乐观锁拦截器（version 字段自动+1）
│   ├── LogicDeletePlugin.java          — 逻辑删除拦截器（自动追加 WHERE deleted=0）
│   ├── AutoFillPlugin.java            — 自动填充拦截器（createTime/updateTime/operator）
│   └── DataPermissionPlugin.java       — 数据权限拦截器（多租户/部门过滤）
├── batch/
│   ├── BatchExecutor.java              — 批量执行器（批量 INSERT/UPDATE 合并提交）
│   └── BatchConfig.java                — 批量配置（每批大小 1000，BATCH 模式）
├── wrapper/
│   ├── QueryWrapperBuilder.java        — 查询条件构建器
│   └── UpdateWrapperBuilder.java       — 更新条件构建器
└── config/
    └── MyBatisAutoConfig.java          — 自动配置（插件注册/分页配置/批量配置）
```

**每个组件的关键实现约束**：

- `PageHelper`：ThreadLocal 存储分页参数（pageNum/pageSize），在 Interceptor 中自动改写 `SELECT COUNT(*)` 查询总数 + 追加 `LIMIT OFFSET`，请求结束后清除 ThreadLocal（防止内存泄漏/线程污染）
- 分页结果：`PageResult<T>` 包含 total / pages / pageNum / pageSize / list，支持 PageHelper.startPage() 和 MyBatis-Plus Page 两种模式
- `OptimisticLockPlugin`：拦截 UPDATE 语句，检查实体是否有 @Version 注解的字段，有则自动追加 `SET version = version + 1 WHERE version = #{oldVersion}`，影响行数为 0 则抛出乐观锁异常
- `LogicDeletePlugin`：拦截 DELETE 语句，改为 `UPDATE SET deleted = 1, deleted_time = NOW()`，拦截 SELECT 语句自动追加 `AND deleted = 0`，支持 `@TableLogic` 注解配置
- `AutoFillPlugin`：拦截 INSERT 自动填充 createTime/updateTime/operator，拦截 UPDATE 自动填充 updateTime/operator，支持 @TableField(fill=INSERT/UPDATE/INSERT_UPDATE) 注解
- `DataPermissionPlugin`：从安全上下文获取当前用户/租户/部门，拦截 SELECT 自动追加 `AND tenant_id = #{tenantId}` 或 `AND dept_id IN (${deptIds})`，支持 `@DataPermission` 注解配置字段名
- 批量操作：`BatchExecutor` 使用 MyBatis BATCH 执行器模式，每批 1000 条 flush 一次，减少数据库交互次数，支持批量 INSERT/UPDATE/DELETE

### Step 3: 生成测试

```
{basePackage}/mybatis/
├── page/PageHelperTest.java
├── plugin/OptimisticLockPluginTest.java
├── plugin/LogicDeletePluginTest.java
├── plugin/AutoFillPluginTest.java
├── plugin/DataPermissionPluginTest.java
└── batch/BatchExecutorTest.java
```

测试要求：
- 分页：验证 SQL 改写、COUNT 查询、ThreadLocal 清除
- 乐观锁：验证 version 自增+条件、版本冲突异常
- 逻辑删除：验证 DELETE 转 UPDATE、SELECT 追加条件
- 自动填充：验证 INSERT/UPDATE 自动填充字段
- 使用 H2 内存数据库测试

### Step 4: 验证

1. 编译通过 + 测试通过
2. 检查清单：
   - [ ] 分页使用拦截器自动改写 SQL（非手写 LIMIT）
   - [ ] 删除使用逻辑删除（非物理删除）
   - [ ] 更新时间字段自动填充（非业务代码手动 set）
   - [ ] 批量操作使用 BATCH 模式（非逐条执行）
   - [ ] 数据权限自动追加 SQL 条件

## 5. 输出文件清单

```
{basePackage}/mybatis/
├── page/PageHelper.java
├── page/PageResult.java
├── page/PageInterceptor.java
├── plugin/OptimisticLockPlugin.java
├── plugin/LogicDeletePlugin.java
├── plugin/AutoFillPlugin.java
├── plugin/DataPermissionPlugin.java
├── batch/BatchExecutor.java
├── batch/BatchConfig.java
├── wrapper/QueryWrapperBuilder.java
├── wrapper/UpdateWrapperBuilder.java
├── config/MyBatisAutoConfig.java
├── page/PageHelperTest.java
├── plugin/OptimisticLockPluginTest.java
├── plugin/LogicDeletePluginTest.java
├── plugin/AutoFillPluginTest.java
├── plugin/DataPermissionPluginTest.java
└── batch/BatchExecutorTest.java
```

## 6. 质量门禁

- ✅ 编译通过 + 测试通过
- ✅ 分页使用拦截器自动改写
- ✅ 删除使用逻辑删除
- ✅ 更新时间字段自动填充
- ✅ 批量操作使用 BATCH 模式
- ✅ 数据权限自动追加 SQL 条件
- ❌ 禁止手写 `LIMIT OFFSET` 分页
- ❌ 禁止使用 `DELETE` 物理删除（应使用逻辑删除）
- ❌ 禁止手动 set 创建时间/更新时间
- ❌ 禁止循环逐条执行 INSERT/UPDATE（应使用批量操作）

## 7. 约束

- ❌ 不生成实体类和 Mapper 接口（实体类由业务需求生成）
- ❌ 不修改已有的 Mapper XML 文件（只新增拦截器和配置）
- ❌ 不引入未确认的 MyBatis 版本（项目中已有的优先复用）
- ❌ 不生成数据库表结构（由 migration-toolkit 处理）
---

> **来源 & 作者**
> - 公众号：华仔聊技术
> - 知识星球：华仔·AI高并发全栈训练营
> - 作者：王江华@huazai
