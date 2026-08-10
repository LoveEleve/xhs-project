# 01 输入校验与边界

> 复审维度 01 | 每个模块必查 | 9 透镜全覆盖
>
> 你是谁：分布式系统审查员。不凭记忆，逐条执行以下指令。每个检查项标注了透镜类型，覆盖
> 业务逻辑/工程正确性/分布式正确性/生产级/可扩展性/并发/微服务/性能/盲区 全部 9 个维度。

---


**执行本维度后，必须在审查报告中输出 `[01] 01 输入校验与边界：发现 N 项`。缺失此标题 = 本维度未被执行。执行每条检查项时，标注来源维度编号（如 [01]）。**
## 检查项

### 1.1 DTO 字段校验完整性 | 透镜：业务/工程/生产级

**必须检查**：`@RequestBody` DTO 的 **每个字段** 是否有校验注解。

**怎么查**：
```bash
# 列出 request DTO 的所有 private 字段，不含 @ 开头的行 = 缺注解字段
for f in $(find my-xhs-<module>/src/main/java -path '*/dto/request/*.java'); do
  echo "=== $f ==="
  grep -n -A0 'private ' "$f" | grep -v '@'
done
```
初筛后必须逐文件肉眼确认——多行注解声明 grep 会漏。

**判定**：

| 字段类型 | 缺什么就是问题 |
|---------|----------------|
| Long ID | `@NotNull` 或 `@Min(1)`/`@Positive` |
| Integer 枚举值 | `@Min` + `@Max` |
| String 文本 | `@Size(max=N)` 或 `@NotBlank` |
| List | 外层 `@Size(max=N)` + 元素级 `@NotNull` |
| 分页 `pageSize` | 只有 `@Max` 没有 `@Min(1)` |
| 分页 `pageNum` | Controller 层没有 `Math.max(1, pageNum)` 下限 |

**案例**：`CommentCreateRequest.replyToId` 缺 `@Min(0)`，负数回复 ID 入库存为孤儿数据（`CommentCreateRequest.java:26`）。

---

### 1.2 @Validated 类级陷阱 | 透镜：工程/盲区

**必须检查**：Controller 类是否有类级 `@Validated`。没有它，`@PathVariable`/`@RequestParam` 上的 `@Min`/`@Max` 全部**静默不生效**。

**怎么查**：
```bash
# 找参数上有 @Min/@Max 但类上没有 @Validated 的 Controller
grep -rn '@Min\|@Max' my-xhs-<module>/src/main/java/com/myxhs/*/controller/ | cut -d: -f1 | sort -u | while read f; do
  head -15 "$f" | grep -q '@Validated' || echo "MISSING @Validated on class: $f"
done
```

**判定**：类级无 `@Validated` 且其方法参数上有 `@Min`/`@Max` → 注解是装饰品，零校验效果。

**案例**：`LikeController.batchCheckLikeStatus` 的 `bizType` 加了 `@Min(1)@Max(2)` 但类级没有 `@Validated`，注解静默不生效。

---

### 1.3 嵌套对象 @Valid 级联 | 透镜：工程/盲区

**必须检查**：DTO 中嵌套对象的每个字段是否加 `@Valid`。不加，嵌套对象内部注解**全部静默不生效**。

**怎么查**：
```bash
grep -rn 'private.*Request \|private.*Item \|private.*DTO ' my-xhs-<module>/src/main/java/com/myxhs/*/dto/request/ | grep -v '@Valid'
```

**判定**：出现 `private SomeRequest xxx;` 行但不包含 `@Valid` → 所有嵌套注解形同虚设。

**案例**：`CartMergeRequest.items`（`CartMergeRequest.java:21`）字段缺 `@Valid`，嵌套 `MergeItem` 的 `skuId`/`quantity` 校验形同虚设。

---

### 1.4 同级字段对称性 | 透镜：业务/工程

**必须检查**：同一 DTO 中语义同级字段的校验注解是否**完全一致**。

**怎么查**：逐 DTO 读所有字段，对比同类型字段的注解差异。重点：一个有 `@Min(0)` 另一个没有；一个有 `@Positive` 另一个没有。

**判定**：存在同类型字段注解不对称 → 上一次修复只改了一处。

**案例**：02-content 复审，`CommentCreateRequest` 的 `parentId` 加了 `@Min(0)` 但同 DTO 的 `replyToId` 没加——修复传播只做了表不一。

---

### 1.5 集合与分页大小上限 | 透镜：性能/生产级

**必须检查**：所有接受集合或批量 ID 的端点是否限制大小。

**怎么查**：
```bash
# List/数组参数是否有限制
grep -rn '@RequestBody.*List\|@RequestParam.*List\|@PathVariable.*List' my-xhs-<module>/src/main/java/com/myxhs/*/controller/

# 逐端点检查——无 @Size 上限 + Controller 无手动上限检查 = 问题
```

**判定**：
- 批量查询 List 无上限 → `WHERE id IN (100000 个)` 打挂 DB
- 分页 pageSize 无上限 → 传 99999 一次查全量
- 分页 pageNum 无 `max(1, ...)` 下限 → 负数穿透到 `LIMIT -100, 20` = 全表扫描

**案例**：`ProductController.batchGetSkuDetails`（`ProductController.java:175`）无 size 上限，可传入数万 skuId 打挂 DB（修复加 `size <= 100`）。

---

### 1.6 IDOR 归属校验 | 透镜：业务/工程/分布式/盲区

**必须检查**：带资源 ID 的端点，Service 层**所有分支**是否校验当前用户对该资源的归属。

**怎么查**：
```bash
# 找出所有带资源 ID 参数的端点
grep -rn '@PathVariable\|@RequestParam.*Id\|@RequestParam.*id' my-xhs-<module>/src/main/java/com/myxhs/*/controller/
```
逐端点追踪：Controller 拿 `X-User-Id` → Service 方法是否用 userId 做归属比对 → **所有分支**（正常路径/Feign 调用/mock/if-else 每侧）都必须用。

**判定**：
- Controller 声明了 userId 参数但 Service 层一条路径都没用 → **假 IDOR 修复**
- `findById(id)` 后直接返回/操作，不与 userId 比对 → **真 IDOR 漏洞**
- 两条分支一条比一条没比 → **不完全修复**

**案例**：`GET /order/pay/status/{orderId}` Controller 声明了 userId 参数，但 Service 层 `getPaymentStatus` 两条路径全没比对 order.getUserId()——编译通过零保护。

---

### 1.7 状态机完整性 | 透镜：业务/工程

**必须检查**：所有修改状态的端点是否经过状态机校验；用户可触达的端点能否跳过中间状态。

**怎么查**：
```bash
# 找状态枚举
grep -rn 'enum.*Status' my-xhs-<module>/src/main/java/
```
1. 列出枚举的全部合法值
2. 找所有 setStatus/updateStatus 调用点
3. 逐点验证：是否经 `canTransitTo()` 或等效检查
4. 区分：用户端点（必须限制）vs 内部回调/补偿（可能有额外许可）

**判定**：
- 用户端点允许跳过中间状态（如 `AUDITING → PUBLISHED`，审核中笔记直接发布）
- `Status.of()` 抛 `IllegalArgumentException` 未 catch → 返回 500 而非 400

**案例**：`publishDraft` 曾允许 `AUDITING → PUBLISHED` 跳过中间状态（`NoteService.java:392`）；`SpuService.updateSpuStatus`（`SpuService.java:355`）`ProductStatus.of()` 非法值抛 500（修复 try-catch 转 `BizException(400)`）。

---

### 1.8 X-User-Id 空值防御 | 透镜：微服务/盲区

**必须检查**：依赖 `X-User-Id` Header 的端点，Header 缺失时的行为。

**怎么查**：
```bash
grep -rn 'X-User-Id' my-xhs-<module>/src/main/java/com/myxhs/*/controller/
# 检查每个声明：是否 required=false（null 风险）；Controller 传 null 到 Service 后 Service 是否判空
```

**判定**：
- `required = false` + Service 未判 null → 拼出 `myxhs:xxx:null` 的 Redis key
- Service 直接 `userId.equals(x)` → NPE

**案例**：`FollowController.getCommonFollowing`（`FollowController.java:103`）`X-User-Id` 为 `required=false` 且 Service 未判 null——Gateway 不传时拼出 `myxhs:follow:list:null` Redis key。

---

### 1.9 鉴权 Token 空值绕过 | 透镜：业务/工程/盲区

**必须检查**：所有内部鉴权 Token（ADMIN_TOKEN/INTERNAL_TOKEN/INTERNAL_CALL_TOKEN）的空值校验是否 fail-closed。

**怎么查**：
```bash
grep -rn 'ADMIN_TOKEN\|INTERNAL_TOKEN\|INTERNAL_CALL_TOKEN' my-xhs-<module>/src/main/java/
# 逐点检查：比对逻辑必须是 !isEmpty() 之后才比较，不是直接 equals
```

**判定**：
- 没有 `!isEmpty()` 前置检查 + 环境变量未配 → `"".equals("")` 为 true → **全线绕过**
- `.contains(token)` 而非 `.equals(token)` → 子串碰撞绕过

**案例**：空 token `"".equals("")` → 全线鉴权绕过；修复见 14 个模块的 12 处校验点追加 `!token.isEmpty()` 前置检查。

---

### 1.10 文件上传安全 | 透镜：生产级/性能

**必须检查**：文件上传的大小/类型校验在 **读取内容之前**，失败后是否清理残留文件。

**怎么查**：
```bash
grep -rn 'MultipartFile\|transferTo\|getBytes' my-xhs-<module>/src/main/java/
```
读上传 Service 代码，确认：
1. 文件大小/类型校验调用顺序在 `getBytes()` / `transferTo()` **之前**
2. `transferTo` 失败后是否有 `dest.delete()` 清理半截文件

**判定**：
- 先 `getBytes()` 后校验大小 → 大文件直接 OOM
- `transferTo` 失败无清理 → 磁盘泄漏

**案例**：`LocalFileStorageService.transferTo` 失败不清理 dest 残留文件（`LocalFileStorageService.java:91` 修复加 delete）。

---

### 1.11 RateLimit 全覆盖 | 透镜：生产级/微服务

**必须检查**：所有写端点（POST/PUT/DELETE）是否有 `@RateLimit`；prefix 是否使用统一命名空间。

**怎么查**：
```bash
# 找出所有写端点
grep -rn '@PostMapping\|@PutMapping\|@DeleteMapping' my-xhs-<module>/src/main/java/com/myxhs/*/controller/ | while read -r line; do
  file=$(echo "$line" | cut -d: -f1)
  lineno=$(echo "$line" | cut -d: -f2)
  # 检查下 3 行是否有 @RateLimit
  head -$((lineno+3)) "$file" | tail -3 | grep -q 'RateLimit' || echo "MISSING: $file:$lineno"
done

# 检查 prefix 是否含 myxhs: 前缀
grep -rn 'prefix = "' my-xhs-<module>/src/main/java/com/myxhs/*/controller/ | grep -v 'myxhs:'
```

**判定**：
- 写端点缺 `@RateLimit` → 无流量保护
- prefix 无 `myxhs:` 前缀 → 跨模块键冲突

**案例**：`CommentController.createComment` prefix 用 `comment:create` 缺 `myxhs:` 前缀；`ProductController.updateSpuStatus` 缺 `@RateLimit` 注解。

---

### 1.12 错误响应规范性 | 透镜：生产级/工程

**必须检查**：非法参数是否返回 400（而非 500）；自定义异常/校验失败是否抛 `BizException(400)`。

**怎么查**：
```bash
grep -rn 'IllegalArgumentException\|IllegalStateException' my-xhs-<module>/src/main/java/
# 检查这些异常是否被 try-catch 包装为 BizException(400) 或全局异常处理器捕获
grep -rn '@ExceptionHandler\|@RestControllerAdvice' my-xhs-<module>/src/main/java/
```

**判定**：
- `throw new IllegalArgumentException("xxx")` 无全局处理器捕获 → 500
- `Status.of(illegalValue)` 抛 IAE → 用户收到 500 → 误告警

**案例**：`SpuService.updateSpuStatus`（`SpuService.java:355`）`ProductStatus.of(code)` 非法值时抛 `IllegalArgumentException`，用户收到 500 而非 400（已修复 catch 转 `BizException(400)`）。

---

### 1.13 请求体大小限制 | 透镜：性能/生产级

**必须检查**：模块是否配置了 `spring.servlet.multipart.max-file-size` 和 `server.max-http-request-header-size`。

**怎么查**：
```bash
grep -rn 'max-file-size\|max-request-size\|tomcat.max-http-form-post-size\|max-http-header-size' my-xhs-<module>/src/main/resources/
```
无配置 → Spring Boot 默认值 1MB（form-data），但某些类型无默认限制。

**判定**：无配置或配置过大 → 10MB JSON body 打挂服务。

**案例**：无文件上传的模块常漏配此限制。（声明性检查，在模块中 grep 确认）

---

### 1.14 校验框架耦合度 | 透镜：可扩展性

**必须检查**：模块是否重度依赖自定义校验注解，还是使用标准 `javax.validation.constraints`。

**怎么查**：
```bash
grep -rn 'import javax.validation\|import jakarta.validation' my-xhs-<module>/src/main/java/
grep -rn 'import com.myxhs.*validation\|@[A-Z].*Constraint' my-xhs-<module>/src/main/java/
```

**判定**：自定义注解 > 5 个且无适配层 → 切换到 Jakarta EE 10 或不同校验框架时改动面大。**记录即可，不强制立即修改**。

**案例**：（全特性面预置检查项——my-xhs 当前无违规案例，但 08-15 未审模块可能有自定义校验注解。）

---

### 1.15 SQL 注入 / XSS / 文本注入防护 | 透镜：业务/生产级/盲区

**必须检查**：所有拼接 SQL（`${}`占位符 / native SQL / 动态排序字段）是否存在注入风险；所有用户文本输入（评论/昵称/笔记）是否有 HTML/脚本过滤。

**怎么查**：
```bash
# MyBatis XML 中的 ${} 占位符（非 #{}——参数化不防注入）
grep -rn '\${' my-xhs-<module>/src/main/resources/mapper/

# Controller/Service 中的字符串拼接 SQL
grep -rn 'concat.*sql\|"SELECT\|"select.*\+ ' my-xhs-<module>/src/main/java/

# 文本过滤——是否有 XSS/敏感词/HTML 转义
grep -rn -e 'HtmlUtils\|Jsoup\|StringEscapeUtils\|clean\|sanitize\|DFA\|sensitive' my-xhs-<module>/src/main/java/
```

**判定**：
- MyBatis 用 `${orderBy}`/`${sortField}` 动态排序→SQL注入
- 用户文本输入直接存储无转义→存储型 XSS（前端展示时执行脚本）
- DFA 过滤器无异常监控→过滤失败静默放行恶意内容

**案例**：02-content 的 DFA 过滤器异常时静默失效——敏感词绕过（`DFAFilter.java` 修复加 metrics 告警注释）。

---

### 1.16 请求签名校验 | 透镜：业务/微服务/盲区

**必须检查**：非公开接口（内部调用/Webhook 回调）是否有请求签名校验（X-Timestamp / X-Nonce / X-Signature 或 HMAC）。

**怎么查**：
```bash
# 找出内部调用路径——未经过 Gateway JWT 鉴权的接口
grep -rn '@PostMapping\|@GetMapping' my-xhs-<module>/src/main/java/com/myxhs/*/controller/ | grep -v 'X-User-Id\|X-Timestamp\|X-Signature\|X-Nonce'
# 支付回调/第三方 Webhook 接口
grep -rn 'callback\|notify\|webhook' my-xhs-<module>/src/main/java/com/myxhs/*/controller/
```

**判定**：
- 支付回调无 HMAC/RSA 验签→可伪造回调篡改支付状态
- 内部 Feign 调用接口不经过 Gateway 但有 JWT 过期/缺失→需 X-Internal-Call 或专用 Token
- Nonce/Timestamp 重复放攻击防护缺失→重放攻击

**案例**：09-payment 的支付回调未验签——可伪造支付成功回调（此属 P0 安全缺陷，08-15 未审模块优先排查）。

---

## 验证命令汇总

```bash
# 编译+测试
mvn compile test-compile -pl <module> -am
mvn test -pl <module>

# DTO 字段缺注解（初筛——多行注解声明 grep 会大量误报，零结果≠零问题，必须逐文件阅读确认）
for f in my-xhs-<module>/src/main/java/com/myxhs/*/dto/request/*.java; do
  echo "=== $f ===" && grep -n 'private ' "$f" | grep -v '@'
done

# @Validated 类级缺失
grep -rn '@Min\|@Max' my-xhs-<module>/src/main/java/com/myxhs/*/controller/ | cut -d: -f1 | sort -u | while read f; do head -15 "$f" | grep -q '@Validated' || echo "MISSING: $f"; done

# 嵌套对象检查——找 DTO 中嵌套对象字段，确认含 @Valid
grep -rn -e 'private List<.*Item\|private List<.*Request\|private List<.*DTO\|private .*Item \|private .*Request \|private .*DTO ' my-xhs-<module>/src/main/java/com/myxhs/*/dto/request/ | grep -v '@Valid'

# 写端点缺 @RateLimit（匹配短名和全限定名两种形式）
grep -rn '@PostMapping\|@PutMapping\|@DeleteMapping' my-xhs-<module>/src/main/java/com/myxhs/*/controller/ | while read -r line; do f=$(echo "$line" | cut -d: -f1); l=$(echo "$line" | cut -d: -f2); head -$((l+3)) "$f" | tail -3 | grep -q 'RateLimit' || echo "MISSING: $f:$l"; done

# RateLimit prefix 缺命名空间
grep -rn 'prefix = "' my-xhs-<module>/src/main/java/com/myxhs/*/controller/ | grep -v 'myxhs:'

# Token 空值绕过
grep -rn 'equals(.*[Tt]oken\|contains(.*[Tt]oken' my-xhs-<module>/src/main/java/ | grep -v 'isEmpty\|isBlank'

# 状态机校验缺失
grep -rn 'setStatus\|\.set.*Status' my-xhs-<module>/src/main/java/com/myxhs/*/ --include='*.java' | grep -v 'canTransit\|BusinessException'
```
