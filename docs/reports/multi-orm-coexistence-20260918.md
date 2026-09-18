# 多 ORM 并存 Demo（MyBatis / MyBatis-Plus / Spring Data JPA）2026-09-18

> 目标：验证同一应用内多 ORM 框架隔离并存（对应"动态 Spring 上下文 + 多 JDBC 框架"能力）。

## 一、实现（content 服务，纯加性、默认关）

- 依赖：新增 `spring-boot-starter-data-jpa`
- 实体/仓库：`com.myxhs.content.jpa.NoteJpaEntity` / `NoteJpaRepository`（映射 t_note，只读）
- 配置 `ContentJpaConfig`（`content.jpa.enabled=true` 时启用）：
  - 独立 `EntityManagerFactory`（复用主数据源，`hbm2ddl=none`）+ 独立 `jpaTransactionManager`
  - 与 MyBatis 的事务/会话互不干扰
- 基线与自动装配隔离：yml 排除 `HibernateJpaAutoConfiguration`（防止开关关闭时自动装配），仓库扫描走显式 `@EnableJpaRepositories`
- 探针：`/api/content/internal/jpa-probe`（内部令牌），同表双 ORM 计数对比

## 二、验证结果（运行态）

```json
{"jpaCount":"36","mybatisCount":"33","jpaFirstTitle":"e2e链路验证笔记","orms":"MyBatis-Plus + Spring Data JPA"}
```

- MySQL 实查 `t_note` = **36** → **JPA 计数一致**
- MyBatis-Plus 计数 **33**：MP 逻辑删除自动追加 `deleted=0`（3 条已删记录被过滤）——**两套 ORM 各按其语义执行，证明真并存**（非同一套代持）
- content 健康 200，既有 MyBatis 接口未受影响

## 三、结论与边界

- **三框架并存达成**：MyBatis（XML Mapper）、MyBatis-Plus（BaseMapper/逻辑删除）、Spring Data JPA（Hibernate）在同一 Spring 上下文、同一数据源上共存；
- 隔离手段：独立 EMF/事务管理器 + 显式包扫描 + 自动装配排除（避免与基线冲突）；
- 边界：JPA 仅用于演示读路径；写路径未迁移（无必要）；开关默认关闭，生产启用需评估 Hibernate 内存/启动开销。
