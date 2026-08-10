# 24 验证协议

> 复审维度 24 | 元层级 | 逐项执行验证命令，不满足于"修复声明"或"编译通过"
>
> 你是谁：分布式系统审查员。每完成一轮审查/修复后，执行以下验证命令。**不凭空推断——每一句"通过"都必须有命令输出支撑。**

---

**执行本维度后，必须在审查报告中输出 `[24] 验证协议执行：共9项，通过/失败 N 项`。缺失此标题 = 验证未执行。**

## 验证清单

### 24.1 编译与测试

```bash
# 所有模块编译（包括测试源码）
mvn compile test-compile -pl <module> -am
# 运行测试
mvn test -pl <module>
```
通过标准：`BUILD SUCCESS` + `Tests run: N, Failures: 0, Errors: 0`

---

### 24.2 表结构验证

```bash
# 列出目标模块的所有 DDL 表声明
grep -rn 'CREATE TABLE' my-xhs-<module>/src/main/resources/ sql/ 2>/dev/null
# 生产库实测——每个声称存在的表都要 SHOW TABLES
mysql -h127.0.0.1 -uroot -e "SHOW TABLES" my_xhs_db 2>/dev/null
```
通过标准：DDL 声明的每张表都能在生产库 `SHOW TABLES` 中找到。分库分表模块需查全部分库。

---

### 24.3 Consumer 链路完整性

```bash
# 列出所有 Producer 发送的 topic/tag
grep -rn 'syncSend\|asyncSend' my-xhs-<module>/src/main/java/ -B2 | grep -E 'topic|tag'
# 所有 Consumer 监听的 topic/tag
grep -rn '@RocketMQMessageListener' my-xhs-<module>/src/main/java/ -A3
# 交叉比对——每个 Producer 对应的 Consumer 是否存在
```
通过标准：每个业务动作（confirm/release/cancel）的 Producer→Consumer 成对匹配。Consumer有但Producer无→记录；Producer有但Consumer无→🔴。

---

### 24.4 Feign 接口契约验证

```bash
# 列出所有 Feign 声明
grep -rn '@FeignClient' my-xhs-<module>/src/main/java/ -A5
# 检查是否有对应的 Fallback
grep -rn 'FallbackFactory\|Fallback' my-xhs-<module>/src/main/java/com/myxhs/*/feign/
```
通过标准：每个 Feign 接口方法都在 Fallback 中有实现。

---

### 24.5 鉴权端点覆盖

```bash
grep -rn '@GetMapping\|@PostMapping\|@PutMapping\|@DeleteMapping' my-xhs-<module>/src/main/java/com/myxhs/*/controller/ | while read -r line; do
  f=$(echo "$line" | cut -d: -f1); l=$(echo "$line" | cut -d: -f2)
  head -$((l+5)) "$f" | tail -5 | grep -qE 'ADMIN_TOKEN|INTERNAL_TOKEN|RateLimit|INTERNAL_CALL' || echo "NO AUTH: $f:$l"
done
```
通过标准：输出为空 = 所有端点有鉴权/限流注解。

---

### 24.6 Lua 脚本 Cluster 兼容

```bash
# 多 KEYS 脚本
grep -rn 'KEYS\[2\]\|KEYS\[3\]' my-xhs-<module>/src/main/resources/lua/
# 逐脚本检查 hash tag 一致性
```
通过标准：所有多 key Lua 脚本的 key 都有相同的 hash tag（`{userId}`），或在 Cluster 模式下使用单 key 脚本。

---

### 24.7 Token 空值绕过验证

```bash
grep -rn 'ADMIN_TOKEN\|INTERNAL_TOKEN\|INTERNAL_CALL_TOKEN' my-xhs-<module>/src/main/java/ | grep -v 'isEmpty\|isBlank'
```
通过标准：输出为空 = 所有 Token 比较前有 `!isEmpty()` 前置检查（fail-closed）。

---

### 24.8 优雅关闭验证

```bash
grep -rn '@Component.*GracefulShutdown\|GracefulShutdown.*@Component\|@PreDestroy' my-xhs-<module>/src/main/java/ | grep 'GracefulShutdown'
```
通过标准：所有 `GracefulShutdownListener` 类都有 `@Component` 注解。

---

### 24.9 全量 Redis Key 格式审计

```bash
grep -rn 'myxhs:' my-xhs-<module>/src/main/java/ | grep -oP 'myxhs:[^"'\''\s]+' | sort | uniq
```
通过标准：同一业务概念在不同模块的 key 格式一致（已在 08.1 审查）。

---

## 验证输出格式

审查完成后，输出如下格式的验证报告：

```
## 验证报告：<module>
- 编译：✅ BUILD SUCCESS
- 测试：✅ 10/10 passed
- 表结构：✅ 4/4 表存在
- Consumer 链路：✅ 5/5 Producer→Consumer 匹配
- Feign 契约：✅ 3/3 Fallback 方法完整
- 鉴权覆盖：✅ 0 个缺口
- Lua 兼容：✅ 0 个多 key Cross-slot
- Token 空值：✅ 0 个未 isEmpty
- 优雅关闭：✅ @Component 已注册
- Redis Key：✅ 无格式不一致
```
