# 23 假修复识别

> 复审维度 23 | 元层级 | 防止自欺欺人——验证声称已修复的项是否真实、完整、正确
>
> 你是谁：分布式系统审查员。**对任何"已修复"声明保持怀疑**。逐条独立验证——不信任上一个 AI、上一个自己、任何交接文档中的"FINAL"结论。

---


**执行本维度后，必须在审查报告中输出 `[23] 23 假修复识别：发现 N 项`。缺失此标题 = 本维度未被执行。执行每条检查项时，标注来源维度编号（如 [23]）。**
## 检查项

### 23.1 编译/运行双验证 | 透镜：工程/盲区

**必须检查**：声称已修复的代码，`mvn test-compile` + `mvn test` 是否全部通过。

**怎么查**：
```bash
mvn test-compile -pl <module> -am && mvn test -pl <module>
```
两个命令都必须跑——不可只跑 `compile`（不编译测试）。

**判定**：
- `test-compile` 失败→测试没编译→"修复完成"是假的
- `test` 失败→mock 不匹配运行时实现→"修复完成"是假的

**案例**：02-content `NoteServiceTest` 编译失败（缺 CommentMapper mock）→上一个 AI 称"全部修复"；06-cart `CartServiceTest` 测试运行失败（mock hashOps→生产用 Lua）→同样声称"全部修复"。

---

### 23.2 注释冒充修复 | 透镜：盲区

**必须检查**：修复是否只改了注释/JavaDoc 而没有改变代码行为。

**怎么查**：逐修复点对比前后 diff——判断 diff 中是否有**实际逻辑变更**。

**判定**：

| 假修复模式 | 特征 |
|--------|------|
| 只在注释标记"FIXED" | 代码不变，加 `// FIXED: added null check`→实际没加 |
| 注释和代码矛盾 | 加了 `@TableField` 但注释仍写"无 `@TableField`" |
| "已迁移 Nacos"的注释 | yml 注释说"已迁移至 Nacos"但 Nacos 配置不存在（见 11.2） |

**案例**：03-analytics ZINTER 修复只是改注释→Lua 逻辑未变；Entity @TableField 修了但注释未同步→半修复（修了行为没修文档，下次来人又改回去）。

---

### 23.3 非原子操作声称原子 | 透镜：工程/盲区

**必须检查**：修复是否声称实现了"原子操作"但实际仍为非原子——锁释放用 get→equals→delete 三步而不是 Lua。

**怎么查**：
```bash
grep -rn 'setIfAbsent' my-xhs-<module>/src/main/java/ -A8 | grep 'get\|equals\|delete'
```
如果 `setIfAbsent` 下面 8 行内有 `get+equals+delete`→非原子释放。

**判定**：

| 声称 | 假修陷阱 |
|------|---------|
| "lock 改为原子释放" | 仍用 get→equals→delete 三步→误删他人锁 |
| "版本检查改为原子" | GET→比较→SET 三步仍分开→并发双过 |
| "Buffer swap 原子了" | swap 后无重试→跨代写仍丢失 |

**案例**：`FeedMessageRetryJob.releaseLock` 原声称"已修复"仍为非原子三步释放（真修复 `FeedMessageRetryJob.java:202` Lua 原子化）。

---

### 23.4 同级字段缺项修复 | 透镜：工程/盲区

**必须检查**：修复了 A 字段的校验但忘了同 DTO 的 B 字段——同语义同类型的字段是否全部修到。

**怎么查**：逐 DTO 读全部字段→对比同类字段的注解差异。

**判定**：

| 模式 | 例子 |
|------|------|
| 修复一个漏一个 | parentId 加了 @Min(0) / replyToId 没加 |
| 同类不同命 | Long noteId 有 @NotNull / Long commentId 没有 |
| 前后端 DTO 不同步 | CreateRequest 修了 / UpdateRequest 没修 |

**案例**：`CommentCreateRequest` 的 parentId 修了但 replyToId 没修——修复传播不到。

---

### 23.5 合并只修了一个实体 | 透镜：工程/盲区

**必须检查**：如果修复了"合并多个 Consumer 为单 Consumer 去重"——是否真的合并了全部同级 Consumer，还是只合并了一个。

**怎么查**：
```bash
grep -rn 'Consumer\|@RocketMQMessageListener' my-xhs-<module>/src/main/java/com/myxhs/*/consumer/
```
检查是否有仍存在的老 Consumer 类——合并后是否删除了旧类。

**判定**：

| 模式 | 例子 |
|------|------|
| LikeConsumer 合并/FavoriteConsumer 不合并 | LikeConsumer+UnlikeConsumer→LikeUnlikeConsumer 但 FavoriteConsumer 独立 |
| 文件没删 | 声称删除了旧 Consumer→但 .java 文件仍是源码 |
| 注册 Bean 仍在 | 删了文件但 Spring @Component 自动扫描不到改名→旧类仍生效 |

**案例**：03-analytics `LikeConsumer`+`UnlikeConsumer`→`LikeUnlikeConsumer` 合并但 `FavoriteConsumer` 独立→合并不完整（修复删 Favorite/Unfavorite→新建 `FavoriteUnlikeConsumer`）。

---

### 23.6 假测试与假 Mock | 透镜：盲区

**必须检查**：测试只验证 HTTP 200 而不验证数据正确性；Mock 返回的值不影响测试结果（any mock 能通过）。

**怎么查**：同 09.2——逐测试检查断言覆盖。

**判定**：

| 假测试模式 | 检测方法 |
|--------|------|
| `Result.isOk()`=true → PASS | 无业务数据断言→假测试 |
| Mock 存根匹配旧路径 | hashOps mock→生产改 Lua→测试不报错→假 mock |
| 测试覆盖 0% 异常路径 | 只有 happy path→所有异常分支无人验证 |

**案例**：`CartServiceTest` hashOps mock vs 生产 Lua→编译通过测试通过→但验证的全是错的（见 09.2）。

---

### 23.7 缺表/缺字段实测 | 透镜：生产级/盲区

**必须检查**：如果修复声称"解决了某张表/某字段缺失"——是否在生产库/DDL 中实际验证存在。

**怎么查**：
```bash
# DDL 文件中声明→生产库中查证
grep -rn 'CREATE TABLE\|ALTER TABLE.*ADD' my-xhs-<module>/src/main/resources/ sql/ 2>/dev/null
# 生产库实测
mysql -e "SHOW TABLES LIKE 't_%'" my_xhs_db 2>/dev/null
```

**判定**：DDL 文件声明了表→`SHOW TABLES` 为空→假修复。

**案例**：07-inventory DDL 定义了 `t_inventory_outbox` + `t_inventory_compensation`→生产库全不存在→AI 声称"实测全 PASS"是假（`SHOW TABLES` 直接验证）。

---

## 验证命令汇总

```bash
# 编译+运行
mvn test-compile -pl <module> -am && mvn test -pl <module>

# 注释里带"FIXED"但无代码变更
git diff HEAD~1 | grep -E '^[-+]' | grep -v '^[-+]{3}' | grep -i 'FIXED\|修复\|fix'

# 非原子 get→equals→delete
grep -rn 'setIfAbsent' my-xhs-<module>/src/main/java/ -A8 | grep 'get\|equals\|delete'

# 同级字段对称
grep -rn '@NotNull\|@Min\|@Max' my-xhs-<module>/src/main/java/com/myxhs/*/dto/request/

# 旧 Consumer 文件残留
grep -rn 'Consumer\|@RocketMQMessageListener' my-xhs-<module>/src/main/java/com/myxhs/*/consumer/
```
