# 09 测试质量

> 复审维度 09 | 每个模块必查 | 9 透镜全覆盖，测试的真实有效性为本维度核心
>
> 你是谁：分布式系统审查员。不凭记忆，逐条执行以下指令。每个检查项标注了透镜类型，覆盖
> 业务逻辑/工程正确性/分布式正确性/生产级/可扩展性/并发/微服务/性能/盲区 全部 9 个维度。

---


**执行本维度后，必须在审查报告中输出 `[09] 09 测试质量：发现 N 项`。缺失此标题 = 本维度未被执行。执行每条检查项时，标注来源维度编号（如 [09]）。**
## 检查项

### 9.1 编译与运行双验证 | 透镜：工程/盲区

**必须检查**：`mvn test-compile` **和** `mvn test` 都必须通过——`mvn test-compile` 只检查编译，`mvn test` 才检查运行时 mock 与生产实现是否一致。

**怎么查**：
```bash
mvn test-compile -pl my-xhs-<module> -am
mvn test -pl my-xhs-<module>
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| `test-compile` 失败 | Mock 类/方法签名与当前代码不匹配→测试永远跑不了 |
| `test-compile` 通过但 `test` 失败 | Mock stub 了旧实现路径（如 hashOps.put）但生产已改为 Lua→测试运行时 mock 无匹配→Fail |
| `mvn compile` 通过不代表测试通过 | 只编译主代码不编译测试→测试失败不暴露 |

**案例**：06-cart `CartServiceTest` 编译通过但运行时失败——mock 了 `hashOps.put`，生产已改为 `execute(script)`，mock 永远匹配不到；`SpuServiceTest` 需要 `TableInfoHelper.initTableInfo` 初始化→编译通过但运行时 ClassNotLoaded。

---

### 9.2 假测试识别 | 透镜：盲区

**必须检查**：测试是否只验证 HTTP 200 而不验证业务数据正确性；Mock 是否只 stub 了 happy path，异常路径全部未覆盖。

**怎么查**：
```bash
grep -rn '@Test' my-xhs-<module>/src/test/ -A20 | grep -E 'isOk\(\)|status\(\)|code.*200|isSuccess'
grep -rn '@Test' my-xhs-<module>/src/test/ -A20 | grep -c 'verify\|assertThat\|assertEquals'
```
前一条多后一条少→仅验证 HTTP 200→假测试。

**判定**：

| 假测试模式 | 特征 |
|--------|------|
| 仅验 200 | `Result.isOk()`=true → 测试通过→但返回数据内容是错的 |
| Mock 全路径 | 所有外部依赖都 mock→测试只验证"测试写了什么" |
| 无异常路径 | 只有 `when(service.get()).thenReturn(data)`→没有 null/异常/边界案例 |
| Mock 返回 Mock | `when(a.getB()).thenReturn(mock(B))`→mock 链→没验证真实交互 |

**案例**：06-cart Test 用 hashOps mock 匹配旧实现路径→"测试通过"但是 mock 和生产行为完全不一致→假测试。

---

### 9.3 表/数据源实测验证 | 透镜：生产级/盲区

**必须检查**：测试是否验证了数据库表/Redis 数据结构的存在——不能只 mock DAO 层。对声称已修复的 DDL 变更（新增表/字段），必须在生产库实际 `SHOW TABLES` 验证。

**怎么查**：
```bash
# DDL 文件中声明的表
grep -rn 'CREATE TABLE' my-xhs-<module>/src/main/resources/ my-xhs/sql/ 2>/dev/null

# 生产库实测——仅适用于当前本地环境
mysql -h127.0.0.1 -uroot -e "SHOW TABLES LIKE 't_%'" my_xhs_<module> 2>/dev/null
```

**判定**：

| 检查点 | 缺陷 |
|--------|------|
| DDL 文件有但生产库没有 | AI 声称"实测全 PASS"但表不存在→修复是完全虚假的 |
| 索引不存在 | DDL 有 CREATE INDEX 但生产库 Missing Index→全表扫描 |
| 测试用 H2 代替 MySQL | H2 语法和 MySQL 不完全兼容→测试通过但生产失败 |

**案例**：07-inventory DDL 文件定义了 `t_inventory_outbox` 和 `t_inventory_compensation` 两张表，但生产库实测全不存在——上一个 AI 声称"修复完成"是假的（`SHOW TABLES` 直接验证）。

---

### 9.4 Mock 语义正确性 | 透镜：工程/盲区

**必须检查**：Mock 与生产实现的语义一致——Mock 返回的值**真实反映了生产行为**，而不是任意构造的假数据。

**怎么查**：
```bash
grep -rn '@MockBean\|@Mock\|mockStatic\|when(' my-xhs-<module>/src/test/ -A3
```
逐 mock 检查：mock 了哪个类？mock 的返回值是否合理？

**判定**：

| 模式 | 缺陷 |
|------|------|
| Mock 缺失参数 | Service 调用 `someMethod(a, b, c)` 但 mock 只 stub 了 `someMethod(a)`→mock 不匹配 |
| Mock 参数语义错误 | `when(redisTemplate.opsForSet().remove(anyString(), anyString()))` → 任意参数都匹配→无法验证调用是否正确 |
| mockStatic 未关闭 | `mockStatic(TransactionSynchronizationManager)` 影响全局→其他测试受影响 |
| Mock 对象不完整 | `when(service.findById(1L)).thenReturn(new Entity())`→Entity 字段全 null→测试 NullPointer |

**案例**：`FavoriteServiceTest` 缺 4 个 mock（`unfavoriteAtomicScript` + `mock` import + `anyLong` import）→编译失败；`CartServiceTest` 11 参构造只 mock 了 7 个 Lua→运行时缺 mock。

---

### 9.5 测试覆盖率与关键路径 | 透镜：业务/工程

**必须检查**：测试是否覆盖了核心业务路径和异常路径——每条 Consumer 的消息处理、每个 TCC 操作的正向和补偿、每条 Feign 的降级。

**怎么查**：
```bash
grep -rn '@Test' my-xhs-<module>/src/test/ | wc -l
grep -rn 'Test' my-xhs-<module>/src/test/ -l | head -20
```
逐测试文件评估：覆盖了类中的哪些方法？遗漏了哪些？

**判定**：
- Consumer 消息处理逻辑→无测试→MQ 消费逻辑完全依赖手工验证
- TCC Try/Confirm/Cancel→只有 Try 有测试→Confirm/Cancel 行为依赖集成环境
- Feign Fallback→无测试→降级路径依赖线上事故暴露
- `@Transactional` 回滚→无测试→事务回滚行为依赖隐式信任

**案例**：（全特性面预置检查项——my-xhs 02-07 模块测试编译均通过但覆盖率不均：inventory TCC Cancel/Confirm 无单元测试；cart reconcile Job 无测试；counter CounterBuffer 并发测试缺失。）

---

### 9.6 测试环境隔离 | 透镜：微服务/工程

**必须检查**：测试是否互相隔离——不会因为测试执行顺序影响结果；是否没有依赖外部服务（Redis/MQ/其他微服务）的单元测试。

**怎么查**：
```bash
mvn test -pl my-xhs-<module> 2>&1 | grep -E 'FAIL|ERROR|Tests run'
```
如果有外部依赖导致的 Fail→测试隔离不完善。

**判定**：
- 测试连真实 Redis→Redis 没启动→全部 Fail→不可靠
- 测试依赖其他服务→网络不可达→随机 Fail
- 测试顺序敏感→`testB` 依赖 `testA` 产生的数据→单独跑 `testB` 失败
- `mockStatic` 不 close→污染下一个测试→随机 Fail

**案例**：`SpuServiceTest` `mockStatic(TransactionSynchronizationManager)` 无 close→可能影响同模块其他测试。

---

## 跨维度引用

| 相关概念 | 主维度 | 说明 |
|---------|:--:|------|
| 表结构验证 | 09.3 | 生产库 DDL vs 实际 SHOW TABLES |
| 假修复识别 | 23 | 假修复/假测试/假 mock 模式库 |

---

## 验证命令汇总

```bash
# 编译+运行（两步独立）
mvn test-compile -pl my-xhs-<module> -am
mvn test -pl my-xhs-<module>

# 假测试检测——只验 200 不验数据
grep -rn '@Test' my-xhs-<module>/src/test/ -A20 | grep -c 'isOk\|status\|code.*200'
grep -rn '@Test' my-xhs-<module>/src/test/ -A20 | grep -c 'verify\|assertThat\|assertEquals'

# Mock 方法签名匹配
grep -rn 'when(' my-xhs-<module>/src/test/ -A2 | grep -E 'thenReturn\|thenThrow'

# mockStatic 使用——检查 close
grep -rn 'mockStatic' my-xhs-<module>/src/test/ -A10 | grep 'close\|verify'

# DDL 表声明
grep -rn 'CREATE TABLE' my-xhs-<module>/src/main/resources/ 2>/dev/null || grep -rn 'CREATE TABLE' sql/ 2>/dev/null

# 测试方法覆盖率
grep -rn '@Test' my-xhs-<module>/src/test/ | wc -l
grep -rn 'public\|private\|protected' my-xhs-<module>/src/main/java/com/myxhs/*/service/ | grep -v '//\|/\*' | wc -l
```
