# Common 数据层 — 读写分离 + TCC 防悬挂

> 关联源码：`ReadWriteRoutingDataSource.java` / `DataSourceContextHolder.java` / `TccFenceService.java`

---

## 读写分离

### 3 层路由

```
① DataSourceContextHolder 手动指定（优先级最高）✅ 生效
  → 强制走主库或从库
  → 场景：写入后立即读（需保证主从一致性）

② @Transactional(readOnly=true) ✅ 生效
  → Spring 自动设置事务只读标记
  → 自动路由到从库

③ SQL 分析（兜底）✅ 已修复（2026-07-30）
  → 新增 ReadWriteRoutingInterceptor（MyBatis Interceptor）
  → 非事务/只读事务中 SELECT 自动路由到从库
  → 活跃写事务中不路由（保持事务单一连接）
```

**修复记录（2026-07-30）**：`ReadWriteRoutingDataSource.isReadOperation()` 原为孤儿方法（无调用方），SQL 分析路由未生效。新增 `ReadWriteRoutingInterceptor` 补上调用链。事务安全：写事务内不做 SQL 路由，防止 SELECT 走从库破坏事务隔离。验证：从库（13310）出现 IM 服务连接。

### 从库降级

从库不可用时，30 秒内所有读请求降级到主库：

```java
try {
    return slaveDataSource.getConnection();
} catch (SQLException e) {
    slaveUnavailable = true;        // 标记不可用
    lastSlaveCheckTime = now;       // 记录降级时间
}
// 降级到主库
return masterDataSource.getConnection();
```

每隔 30 秒尝试恢复从库连接：

```java
private void tryRecoverSlave() {
    if (now - lastSlaveCheckTime < 30000) return;
    try (Connection conn = slaveDataSource.getConnection()) {
        if (conn.isValid(3)) slaveUnavailable = false;
    } catch (SQLException ignored) {}
}
```

---

## TCC Fence

### 为什么需要 Fence

TCC 模式三个问题：

```
空回滚：Cancel 先于 Try 到达 → Try 还没执行，Cancel 白做了
悬挂：Try 在 Cancel 之后到达 → Try 本不该执行
幂等：所有阶段都可能重复执行
```

### Fence 表

```sql
CREATE TABLE t_tcc_fence (
    xid         VARCHAR(128) NOT NULL,  -- 全局事务 ID
    branch_id   BIGINT NOT NULL,         -- 分支事务 ID
    action_name VARCHAR(64),             -- 操作名
    status      TINYINT NOT NULL,        -- 1=Try 2=Confirm 3=Cancel
    PRIMARY KEY (xid, branch_id)
);
```

### 3 个阶段

**Try**：
```java
try {
    INSERT INTO t_tcc_fence (xid, branch_id, status=1)
    return true;     // 首次 Try → 执行业务
} catch (DuplicateKeyException) {
    status = SELECT status FROM t_tcc_fence
    if (status == 3) return false;  // 悬挂 → 拒绝
    return true;                     // 幂等 → 放行
}
```

**Confirm**：
```java
// status 必须是 1（Try），2（Confirm）走幂等，3（Cancel）拒绝
UPDATE t_tcc_fence SET status=2 WHERE xid=? AND branch_id=? AND status=1
```

**Cancel**：
```java
try {
    INSERT INTO t_tcc_fence (xid, branch_id, status=3)
    // 如果 INSERT 成功 → Try 还没执行，空回滚
} catch (DuplicateKeyException) {
    status = SELECT status FROM t_tcc_fence
    if (status == 2) return;   // 已 Confirm → 拒绝
    UPDATE t_tcc_fence SET status=3 WHERE status=1
}
```

### 状态机

```
          try             confirm
  null ──────→ 1 (Try) ────────→ 2 (Confirm)
                │
                └── cancel ───→ 3 (Cancel)
                   (空回滚)
```

关键规则：只有 status=1（Try）可以流转到 2（Confirm）或 3（Cancel）。Cancel 直接从 null 插入 status=3（空回滚）。

---

## 面试 Q&A

**Q: 读写分离的主从延迟怎么处理？**
A: 默认走从库，但通过 `DataSourceContextHolder` 手动指定主库可以绕开。写后立即读的业务手动切主库。

**Q: 从库降级到主库时，主库压力增大怎么处理？**
A: 单从库降级确实会增加主库压力。生产环境建议多个从库，单从库挂时其他从库继续分担。

**Q: TCC 的悬挂和空回滚有什么区别？**
A: 空回滚是 Cancel 先到但 Try 没执行（Cancel 插入 status=3）。悬挂是 Try 在 Cancel 之后到达（Try 发现 status=3 拒绝执行）。两种都通过 Fence 表的状态检查解决。
