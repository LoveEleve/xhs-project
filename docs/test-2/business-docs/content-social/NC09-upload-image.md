# NC09: 上传图片 — POST /api/note/upload/image

## § 源码分析

- **Controller**: `NoteController.java:137` → `@PostMapping("/upload/image")`, 参数 `X-User-Id` + `@RequestParam("file") MultipartFile`
- **Service**: `FileStorageService.upload(file, "note")` → 返回URL
  - 格式校验: JPEG/PNG/GIF/WebP (`MultipartFile.getContentType()`)
  - 大小限制: 5MB (application.yml `max-file-size: 5MB`)
  - 本地存储: `/data/uploads/{type}/{uuid}.{ext}`
  - 返回URL: `http://21.214.97.212:19002/uploads/{type}/{uuid}.{ext}`
- **RateLimit**: 20次/60秒
- **下游**: 本地文件系统

## § 业务逻辑

接收图片文件→格式/大小校验→存储到本地→返回可访问URL→前端拿到URL后填写images字段

## § 前置检查

| 条件 | 验证 | 不满足后果 |
|------|------|------|
| 文件格式 | JPEG/PNG/GIF/WebP | 415 |
| 文件大小 | ≤5MB | 413 |
| RateLimit | 20次/60秒 | 429 |

## § 数据验证 L2

| 层 | 验证命令 | 预期 |
|------|------|------|
| 文件系统 | `ls /data/uploads/note/` | 有新文件 |

## § 生产级检查 L3

| 透镜 | 检查项 | 预期 |
|------|------|:--:|
| 安全 | 格式白名单+5MB限制 | ✅ |

## § curl

```bash
echo "test image" > /tmp/test.jpg
curl -s -X POST http://localhost:19000/api/note/upload/image \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/tmp/test.jpg" | python3 -m json.tool
```

## § ASCII流转图

```
POST /api/note/upload/image + MultipartFile
  → NoteController.uploadImage(X-User-Id, file)
  → FileStorageService.upload(file, "note")
  → 格式/大小校验 → 存储 /data/uploads/note/{uuid}.{ext}
  → 返回 {url: "http://host:19002/uploads/note/xxx.jpg"}
```
