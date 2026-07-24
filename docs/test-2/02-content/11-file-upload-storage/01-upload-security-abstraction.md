# 文件上传：类型白名单、UUID 命名、存储抽象层

> `LocalFileStorageService.java`（92 行）+ `FileStorageService.java`（接口）+ `FileUploadConfig.java`（静态资源映射）
> 前置阅读：`10-note-edit-delete/` — 笔记编辑更新 images 字段时依赖上传返回的 URL
> 看似简单的 CRUD 接口，背后涉及文件安全、命名策略、存储抽象三个工程主题。

---

## 1. 文件上传的四个安全问题

如果直接接收用户上传的文件并存到磁盘，会有这些风险：

| 风险 | 攻击方式 | 后果 |
|------|---------|------|
| **恶意文件** | 上传 `shell.jsp`，直接 HTTP 访问 → 服务器执行 | 远程代码执行 |
| **路径穿越** | 文件名 `../../etc/passwd` → 覆盖系统文件 | 系统崩溃 |
| **文件覆盖** | 多次上传同名文件 → 覆盖已有文件 | 数据丢失 |
| **磁盘填满** | 上传超大文件 → 磁盘满 → 所有服务不可用 | DOS |

这个项目用四道防线应对：

---

## 2. 防线 1：类型白名单 — 只看 Content-Type，不看文件名

```java
private static final Set<String> ALLOWED_TYPES = Set.of(
    "image/jpeg", "image/png", "image/gif", "image/webp"
);

String contentType = file.getContentType();
if (!ALLOWED_TYPES.contains(contentType)) {
    throw new BizException("文件类型不允许");
}
```

**为什么检查 Content-Type 而不是文件扩展名？**

客户端可以传任何文件名——`shell.php.png` 的扩展名是 `.png`，但实际内容是 PHP 代码。Content-Type 由客户端根据文件的实际二进制内容设置（虽然客户端也能伪造 Content-Type，但这需要客户端配合，门槛更高）。

**HTTP Content-Type 的来源**：浏览器/HTTP 客户端在发送 `multipart/form-data` 时，对每个文件 part 设置 Content-Type（基于文件扩展名的 MIME 映射）。攻击者可以在代码中手动设置 `Content-Type: image/png` 上传 PHP 文件，绕过这个检查。所以类型白名单是**第一道防线**，不是最终防线。

**更安全的做法**（生产环境）：
- 用 Tika/Apache POI 检测文件的实际二进制签名（magic bytes）
- 用 ClamAV 扫描病毒
- 文件不存储在应用服务器上，存在独立的 OSS/MinIO，通过 CDN 分发

---

## 3. 防线 2：扩展名由 Content-Type 推导，不信任文件名

```java
private static final Map<String, String> CONTENT_TYPE_EXT_MAP = Map.of(
    "image/jpeg", "jpg",
    "image/png",  "png",
    "image/gif",  "gif",
    "image/webp", "webp"
);

String ext = CONTENT_TYPE_EXT_MAP.getOrDefault(contentType, "jpg");
String fileName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
```

**完全忽略客户端传来的文件名**。即使客户端传了 `malicious.jsp.png`，实际存储的文件名是 `{uuid}.png`——因为 Content-Type 是 `image/png`。

---

## 4. 防线 3：UUID 文件名 — 防覆盖 + 防路径穿越

```
UUID.randomUUID().toString().replace("-", "")
→ "5347f1a92cf94583902e46d228e20075"

最终文件名: 5347f1a92cf94583902e46d228e20075.png
```

**防覆盖**：两个不同用户上传"同名的"图片，存储成不同的 UUID 文件名。不会互相覆盖。

**防路径穿越**：UUID 只包含十六进制字符 `[0-9a-f]`，不包含 `/`、`..`、`\` 等路径分隔符，无法进行目录遍历攻击。

---

## 5. 防线 4：大小限制 + 日期分层

```java
private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;  // 5MB

String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
String fullPath = basePath + "/" + directory + "/" + datePath + "/" + fileName;
// → /data/uploads/note/2026/07/23/5347f1a...png
```

**大小限制**：`file.getSize() > 5MB` → 直接拒绝。Tomcat 层面还有 `spring.servlet.multipart.max-file-size=5MB` 作为第二层保护。

**日期分层**：`yyyy/MM/dd` 的目录结构天然支持按天归档和清理——凌晨任务可以 `rm -rf /data/uploads/note/2026/07/01/`。

---

## 6. 存储抽象层 — 将来切 MinIO 只需换一个实现

```java
// FileStorageService.java — 接口
public interface FileStorageService {
    String upload(MultipartFile file, String directory);
}

// LocalFileStorageService.java — 当前实现
@Service
@ConditionalOnProperty(name = "storage.type", havingValue = "local", matchIfMissing = true)
public class LocalFileStorageService implements FileStorageService { ... }
```

切换到 MinIO 的步骤：

1. 添加 MinIO 依赖
2. 写 `MinioFileStorageService implements FileStorageService`
3. 加 `@ConditionalOnProperty(name = "storage.type", havingValue = "minio")`
4. 改配置 `storage.type=minio`

**所有调用方（NoteController）不需要改一行代码**——它们依赖接口 `FileStorageService`，不是实现类。

---

## 7. 静态文件访问 — WebMvcConfigurer

```java
// FileUploadConfig.java
@Configuration
public class FileUploadConfig implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + basePath + "/");
    }
}
```

上传的文件存在磁盘上，通过 Tomcat 直接提供 HTTP 访问——不需要经过 Controller。请求 `/uploads/note/2026/07/23/xxx.png` → Tomcat 直接从磁盘读取 → 返回文件内容。

**生产环境的正确做法**：文件存在 OSS/MinIO + CDN，不在应用服务器上提供静态文件访问。Tomcat 的工作是处理业务逻辑，不是提供静态文件。

---

## 8. 总结

| 防线 | 机制 | 防范的攻击 |
|:--:|------|---------|
| 1 | Content-Type 白名单 | 非图片文件上传 |
| 2 | 扩展名由 Content-Type 推导 | 文件名伪装 |
| 3 | UUID 文件名 | 路径穿越、文件覆盖 |
| 4 | 大小限制 + 日期分层 | 磁盘 DOS、便于归档 |

> 当前方案是"开发环境安全"，生产环境需要增加二进制签名检测 + OSS/CDN 分发 + 病毒扫描。

## 已知局限

| 局限 | 说明 |
|------|------|
| Content-Type 可伪造 | 客户端可以手动设置 Content-Type，绕过类型白名单——需增加二进制 magic bytes 签名检测 |
| Tomcat 直接提供静态文件 | 生产环境应使用 OSS/CDN，不在应用服务器上提供文件访问 |
| 无病毒扫描 | 攻击者可以构造一个包含恶意代码的 JPEG 图片（利用解析器漏洞） |
| 无文件去重 | 同一张图片多次上传 → 存储多份副本，可用 MD5/SHA256 去重 |
