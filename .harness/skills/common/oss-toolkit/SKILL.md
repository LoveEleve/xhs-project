---
name: oss-toolkit
stage: 组件封装
description: 对象存储工具封装——统一接口（上传/下载/删除/预签名URL）、分片上传/断点续传、图片处理、CDN 刷新
---

# 对象存储工具封装（oss-toolkit）

## 1. 职责

为项目生成对象存储基础设施代码，包括统一存储接口（上传/下载/删除/列表/预签名URL）、分片上传/断点续传、图片处理（缩略图/水印/裁剪）、CDN 刷新。**禁止在业务代码中直接操作 OSS 客户端而不经过封装层**。

## 2. 触发方式

| 方式 | 说明 |
|------|------|
| 独立命令 | `/oss-toolkit [upload|presign|multipart|image|cdn|all]` |
| 自动加载 | coding-skill 阶段检测到 OSS/S3/MinIO/COS/OBS 客户端代码时 |

## 3. 前置条件

- 项目已识别技术栈+存储服务（阿里云OSS / AWS S3 / MinIO / 腾讯COS / 华为OBS）
- 已确定目标包路径（按语言规范）
- 已确定存储桶名称和区域
- 用户未指定 → 生成全部组件（`all`）

## 4. 工作流程

### Step 1: 确定生成目标

| 子命令 | 组件 | 检测条件 |
|--------|------|---------|
| `upload` | 上传/下载/删除 | 直接操作 OSS 客户端上传文件 |
| `presign` | 预签名 URL | 需要客户端直传或临时下载 |
| `multipart` | 分片上传/断点续传 | 大文件上传（>100MB） |
| `image` | 图片处理 | 上传图片需要缩略图/水印 |
| `cdn` | CDN 刷新 | 文件更新后需要刷新 CDN |
| `all`（默认） | 全部组件 | 首次引入对象存储 |

### Step 2: 生成代码文件

```
# Java 示例
{basePackage}/oss/
├── core/
│   ├── StorageService.java             — 统一存储接口
│   ├── StorageProperties.java          — 配置（endpoint/bucket/region）
│   └── StorageFile.java                — 文件元数据（文件名/大小/MD5/URL）
├── upload/
│   ├── FileUploader.java               — 文件上传器（普通/分片自动选择）
│   ├── MultipartUploader.java          — 分片上传（初始化/上传分片/完成/取消）
│   └── UploadTokenGenerator.java       — 上传凭证（STS 临时凭证）
├── download/
│   ├── FileDownloader.java             — 文件下载器（普通/断点续传）
│   └── PresignedUrlGenerator.java      — 预签名 URL 生成
├── image/
│   ├── ImageProcessor.java             — 图片处理（缩略图/水印/裁剪/格式转换）
│   └── ImageProcessTemplate.java       — 图片处理模板（头像/商品图/封面图）
├── cdn/
│   ├── CdnRefreshService.java          — CDN 刷新（文件/目录/全站）
│   └── CdnPrefetchService.java         — CDN 预热
└── config/
    └── OssAutoConfig.java              — 自动配置

# 其他语言按对应命名规范生成
```

**每个组件的关键实现约束**：

- `StorageService`：统一接口封装所有存储操作，上传自动判断文件大小选择普通上传（<100MB）或分片上传（>=100MB），上传返回完整 URL（含 CDN 域名）
- 分片上传：分片大小 5MB（除最后一片），最大 10000 片，支持断点续传（记录 uploadId 到本地/数据库），上传完成自动校验 MD5
- 预签名 URL：支持 GET（下载，有效期 1h~7d）和 PUT（上传，有效期 30min），支持自定义文件名和 Content-Type
- 图片处理：对上传的图片自动生成缩略图（大/中/小三种尺寸），水印（文字/图片），裁剪（按比例/按尺寸），格式转换（webp 自动判断）
- CDN 刷新：单个文件刷新（URL）、目录刷新（批量）、全站刷新（紧急），支持刷新记录查询，CDN 预热（预先把内容推送到 CDN 节点）

### Step 3: 生成测试

```
{basePackage}/oss/
├── core/StorageServiceTest.java
├── upload/MultipartUploaderTest.java
├── download/PresignedUrlGeneratorTest.java
├── image/ImageProcessorTest.java
└── cdn/CdnRefreshServiceTest.java
```

测试要求：
- 存储服务：验证上传/下载/删除/列表/预签名 URL 五种操作
- 分片上传：验证初始化/上传分片/完成/取消，验证断点续传恢复
- 预签名 URL：验证 URL 生成、有效期、访问控制
- 使用 Mock 存储服务测试

### Step 4: 验证

1. 编译通过 + 测试通过
2. 检查清单：
   - [ ] 所有文件操作经过 `StorageService` 封装
   - [ ] 上传自动判断普通/分片（非手动选择）
   - [ ] 分片上传支持断点续传
   - [ ] 预签名 URL 有有效期限制
   - [ ] 图片处理自动生成缩略图
   - [ ] CDN 刷新有刷新记录查询

## 5. 输出文件清单

```
{targetPackage}/oss/
├── core/StorageService.{java|py|go}
├── core/StorageProperties.{java|py|go}
├── core/StorageFile.{java|py|go}
├── upload/FileUploader.{java|py|go}
├── upload/MultipartUploader.{java|py|go}
├── upload/UploadTokenGenerator.{java|py|go}
├── download/FileDownloader.{java|py|go}
├── download/PresignedUrlGenerator.{java|py|go}
├── image/ImageProcessor.{java|py|go}
├── image/ImageProcessTemplate.{java|py|go}
├── cdn/CdnRefreshService.{java|py|go}
├── cdn/CdnPrefetchService.{java|py|go}
├── config/OssAutoConfig.{java|py|go}
├── core/StorageServiceTest.{java|py|go}
├── upload/MultipartUploaderTest.{java|py|go}
├── download/PresignedUrlGeneratorTest.{java|py|go}
├── image/ImageProcessorTest.{java|py|go}
└── cdn/CdnRefreshServiceTest.{java|py|go}
```

## 6. 质量门禁

- ✅ 编译通过 + 测试通过
- ✅ 所有文件操作经过封装层
- ✅ 上传自动选择普通/分片
- ✅ 分片上传支持断点续传
- ✅ 预签名 URL 有有效期
- ✅ 图片处理自动生成缩略图
- ❌ 禁止直接操作 OSS 客户端
- ❌ 禁止预签名 URL 无有效期（永久有效）
- ❌ 禁止大文件普通上传（应使用分片上传）
- ❌ 禁止图片处理硬编码尺寸（应使用配置模板）

## 7. 约束

- ❌ 不生成存储桶创建/删除操作（由运维管理）
- ❌ 不修改已有的 OSS 代码（只新增封装层）
- ❌ 不引入未确认的 OSS SDK 版本
- ❌ 不生成 STS 服务端代码（由云平台提供）
- ❌ 不生成 CDN 域名配置（由运维团队管理）
---

> **来源 & 作者**
> - 公众号：华仔聊技术
> - 知识星球：华仔·AI高并发全栈训练营
> - 作者：王江华@huazai
