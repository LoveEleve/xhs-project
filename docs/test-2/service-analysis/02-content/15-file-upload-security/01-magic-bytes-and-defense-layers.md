# 文件上传安全体系：从 Content-Type 到魔数校验

> **源码**: `LocalFileStorageService.java:53-117`  
> **安全等级**: OWASP ASVS V12 (File & Resource) L1  
> **关键修复**: 魔数校验防 Content-Type 伪造（坑20）

---

## 1. 攻击面分析

文件上传是 Web 应用最常见的高危攻击入口之一。典型攻击路径：

| 攻击类型 | 手段 | 危害 |
|---------|------|------|
| 文件类型伪造 | 上传 `shell.php`，声明 Content-Type 为 `image/jpeg` | 远程代码执行 |
| 文件大小攻击 | 上传 500MB 大文件 | 磁盘耗尽 / OOM |
| 路径遍历 | 文件名 `../../etc/crontab` | 覆盖系统文件 |
| ZIP 炸弹 | 4KB 压缩文件解压 4GB | 磁盘 + CPU 耗尽 |
| SSRF via XXE | 图片内嵌 XML 实体 | 内网探测 / 文件读取 |

my-xhs 的防御分为 **4 层**：

```
 ┌──────────────────────────────────────────┐
 │  L1: Content-Type 白名单                   │
 │      只允许 image/jpeg,png,gif,webp       │
 ├──────────────────────────────────────────┤
 │  L2: 魔数校验（文件头字节比对）      ← 本文化身 │
 │      JPEG: FF D8 / PNG: 89 50 4E 47       │
 ├──────────────────────────────────────────┤
 │  L3: 文件大小限制 (5MB)                    │
 │      在 transferTo 之前检查，避免磁盘IO     │
 ├──────────────────────────────────────────┤
 │  L4: 文件名安全                            │
 │      UUID 重命名 + Content-Type 推导扩展名   │
 └──────────────────────────────────────────┘
```

---

## 2. L1: Content-Type 白名单

```java
// LocalFileStorageService.java:38-40
private static final Set<String> ALLOWED_TYPES = Set.of(
        "image/jpeg", "image/png", "image/gif", "image/webp"
);

// Line 57-58
String contentType = file.getContentType();
if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
    throw new BizException(ResultCode.PARAM_INVALID, "仅支持 JPEG/PNG/GIF/WebP");
}
```

### 为什么 Content-Type 不可信？

`Content-Type` 由客户端在 HTTP Header 中声明，攻击者可以随意修改：

```bash
# 攻击：上传 PHP 文件，伪装成图片
curl -X POST /api/note/upload/image \
  -H "Content-Type: multipart/form-data" \
  -F "file=@shell.php;type=image/jpeg"  # ← 伪造 type=image/jpeg
```

服务器端 `file.getContentType()` 读到的就是 `"image/jpeg"`，因此仅靠 Content-Type 白名单不够。

---

## 3. L2: 魔数校验（核心防御）

```java
// LocalFileStorageService.java:61-65
if (!verifyMagicBytes(file, contentType)) {
    log.warn("[文件上传] 文件头魔数不匹配，拒绝上传: claimed={}", contentType);
    throw new BizException(ResultCode.PARAM_INVALID, "文件内容与声明类型不匹配");
}
```

### 魔数表

| 文件类型 | 魔数（HEX） | 魔数（DEC） |
|---------|------------|-----------|
| JPEG | `FF D8` | 255, 216 |
| PNG | `89 50 4E 47` | 137, 80, 78, 71 |
| GIF | `47 49 46 38` | 71, 73, 70, 56 (GIF8) |
| WebP | `52 49 46 46 xx xx xx xx 57 45 42 50` | RIFF....WEBP |

### 源码实现

```java
// Line 102-117
private boolean verifyMagicBytes(MultipartFile file, String contentType) {
    try {
        byte[] h = file.getBytes();
        if (h.length < 4) return false;
        return switch (contentType) {
            case "image/jpeg" -> (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8;
            case "image/png"  -> h[0] == (byte)0x89 && h[1]==0x50 && h[2]==0x4E && h[3]==0x47;
            case "image/gif"  -> h[0]==0x47 && h[1]==0x49 && h[2]==0x46 && h[3]==0x38;
            case "image/webp" -> h.length >= 12 && h[0]==0x52 && h[1]==0x49
                    && h[2]==0x46 && h[3]==0x46      // "RIFF"
                    && h[8]==0x57 && h[9]==0x45
                    && h[10]==0x42 && h[11]==0x50;   // "WEBP"
            default -> true;
        };
    } catch (IOException e) {
        log.error("[文件上传] 读取文件头失败", e);
        return false;
    }
}
```

### JPEG 的 `& 0xFF`：无符号转换的防御性编码

Java 的 `byte` 是有符号的（-128 ~ 127），魔数 `0xFF`（无符号 255）在 Java 中表示为 `-1`。虽然 `h[0] == (byte)0xFF` 也能工作（`(byte)0xFF` 就是 `-1`，和 JPEG 文件头的第一个字节相同），但 `(h[0] & 0xFF)` 模式更显式地表达了"我在比较无符号字节值"的意图。

```java
byte b = (byte) 0xFF;   // = -1 (有符号)
int  u = b & 0xFF;       // = 255 (无符号)，可用于数值比较而非字节比较
```

**关键区别**：如果后续代码需要对字节值进行**算术运算**或**数值比较**（如 `> 0x80`），必须用 `& 0xFF` 转换，否则带符号的负值会破坏逻辑。

---

## 4. L3: 文件大小限制

```java
// Line 42-43
private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;  // 5MB

// Line 68-70
if (file.getSize() > MAX_FILE_SIZE) {
    throw new BizException(ResultCode.PARAM_INVALID, "文件大小不能超过5MB");
}
```

### 为什么在 transferTo 之前检查？

`MultipartFile.getSize()` 只是读取 Header `Content-Length`，不消耗文件流。如果先 `transferTo` 写磁盘再检查大小，磁盘 I/O 已经发生：

```
错误顺序:  transferTo (写 500MB 到磁盘) → 检查 size → 发现过大 → 删除文件
正确顺序:  检查 size → 通过 → transferTo
```

> **⚠️ 已修复**（2026-08-04）：源码中已将 size 检查移到魔数校验之前，并附注释说明原因。

---

## 5. L4: 文件名安全

```java
// Line 72-74
String ext = CONTENT_TYPE_EXT_MAP.getOrDefault(contentType, "jpg");
String fileName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
```

### 为什么不用客户端传来的文件名？

| 客户端文件名 | 风险 |
|------------|------|
| `shell.php` | PHP 解释器执行 |
| `../../../etc/crontab` | 路径遍历 |
| `a.jsp` | JSP 执行（如果部署在 Tomcat 的 webapps） |
| `a` + `NULL` + `.jpg` | NULL 字节截断 |
| `very_long_filename......jpg` | 文件名过长导致文件系统错误 |

UUID 重命名彻底消除了文件名风险。

---

## 6. 攻击绕过测试

### 绕过 L1（Content-Type）

```
攻击请求: Content-Type: image/jpeg, 实际文件: shell.php
结果: L2 魔数校验拒绝 → 40002 "文件内容与声明的类型不匹配"  ✓
```

### 绕过 L2（魔数拼接）

```
攻击: 将 JPEG 头 4 字节 + PHP 代码拼接成一个文件
文件: FF D8 FF E0 <?php system($_GET['cmd']); ?>
结果: 文件通过 L2（前4字节合法），被存储为 .jpg
危害评估:
  - Nginx 按扩展名返回 image/jpeg → 不会执行 PHP ✓
  - 如果 Nginx 配置错误（proxy_pass 到 PHP-FPM）→ 代码执行 ✗
```

**这个攻击能否完全防御？**

如果是 **独立图片服务器**（Nginx 只 serve 静态文件，不经过 PHP-FPM），拼接文件的 PHP 代码不会被解释执行。但如果图片和 Web 应用部署在同一台机器且配置了 `location ~ \.php$`，攻击者可能通过 `/uploads/evil.jpg` → Nginx 的 rewrite 规则触发 PHP 执行。

**生产环境加固建议**：
1. 图片存储到独立的对象存储（MinIO/OSS）
2. Nginx 图片路径禁止脚本执行
3. 对上传文件进行图片重新编码（ImageMagick/GD），这会把任意文件转换为真正的图片

---

## 7. Java ImageIO 层面的额外防御

```java
// NoteController.java:137-150
@PostMapping("/upload/image")
public R<Map<String, String>> uploadImage(
        @RequestHeader("X-User-Id") Long userId,
        @RequestParam("file") MultipartFile file) {
    String url = fileStorageService.upload(file, "note");
    return R.ok(Map.of("url", url));
}
```

**当前设计**: 只有 L1-L4 四层防御，没有调用 ImageIO 重新编码。

**生产建议**: 上传后增加 ImageIO 重编码步骤：
```java
BufferedImage img = ImageIO.read(file.getInputStream());
if (img == null) throw new BizException("不是有效的图片文件");
// 重新编码为 JPEG
ImageIO.write(img, "jpg", outputStream);
```
这会把攻击者拼接的文件（JPEG头 + PHP代码）重新编码为纯 JPEG 图片，PHP 代码在编码过程中被丢弃。

---

## 8. 知识点索引

| 知识点 | 源码 | 行号 |
|--------|------|------|
| Content-Type 白名单 | `LocalFileStorageService.java` | 38-40 |
| 魔数校验 | `LocalFileStorageService.java` | 102-117 |
| JPEG 的无符号转换陷阱 | `LocalFileStorageService.java` | 107 |
| UUID 重命名 | `LocalFileStorageService.java` | 73-74 |
| 扩展名 Content-Type 推导 | `LocalFileStorageService.java` | 46-51 |
| transferTo 前 size 检查 | `LocalFileStorageService.java` | 68-70 |
| 策略模式存储抽象 | `FileStorageService.java` | 11-21 |

---

## 9. 面试要点

**Q1**: 为什么 Content-Type 白名单不能单独作为文件校验依据？

**A**: Content-Type 由客户端在 HTTP Header 中声明，攻击者可以伪造。浏览器或 curl 可以设置任意 Content-Type 值。真正安全的校验必须读取文件内容的二进制字节（魔数）。

**Q2（陷阱）**: JPEG 魔数是 `FF D8`，为什么代码中写的是 `(h[0] & 0xFF) == 0xFF` 而不是 `h[0] == (byte)0xFF`？

**A**: Java 的 `byte` 是有符号类型（-128~127）。`0xFF` = 255 > 127，在 Java 中表示为 `-1`。`h[0] == (byte)0xFF` 相当于 `h[0] == -1`，而第二字节 `0xD8` 在有符号下也等于负数。虽然理论上 `==` 比较也能工作，但 `& 0xFF` 做无符号转换是更安全的实践——避免阅读代码的人困惑。

**Q3**: 如果攻击者上传了一个真实的 PNG 图片，但图片内容包含攻击 payload（如 XSS），如何防御？

**A**: 
- L1-L4 只验证文件类型，不验证内容安全
- XSS via SVG 是真实攻击（SVG 内嵌 `<script>`）
- 防御手段：禁止 SVG 上传、ImageIO 重编码去掉元数据、CSP Header `img-src 'self'`

---

*下一篇：评论系统（楼中楼 + 分页去重 + 计数一致性）*
