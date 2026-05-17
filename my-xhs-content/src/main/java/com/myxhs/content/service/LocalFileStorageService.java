package com.myxhs.content.service;

import com.myxhs.common.exception.BizException;
import com.myxhs.common.response.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 本地磁盘文件存储实现（开发环境）
 * <p>
 * 文件按日期目录分层存储，使用 UUID 生成唯一文件名防止覆盖。
 * 生产环境通过 storage.type=minio 切换到 MinIO 实现。
 * </p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "storage.type", havingValue = "local", matchIfMissing = true)
public class LocalFileStorageService implements FileStorageService {

    @Value("${storage.local.path:/data/uploads}")
    private String basePath;

    @Value("${storage.local.url-prefix:http://localhost:19002/uploads}")
    private String urlPrefix;

    /** 允许的图片类型白名单 */
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp"
    );

    /** 最大文件大小：5MB */
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;

    /** Content-Type → 文件扩展名映射（不信任客户端传来的文件名） */
    private static final Map<String, String> CONTENT_TYPE_EXT_MAP = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/gif", "gif",
            "image/webp", "webp"
    );

    @Override
    public String upload(MultipartFile file, String directory) {
        // 1. 校验文件类型（白名单）
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new BizException(ResultCode.PARAM_INVALID, "文件类型不允许，仅支持 JPEG/PNG/GIF/WebP");
        }

        // 2. 校验文件大小（最大 5MB）
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BizException(ResultCode.PARAM_INVALID, "文件大小不能超过5MB");
        }

        // 3. 生成唯一文件名（从 Content-Type 推导扩展名，不信任客户端文件名）
        String ext = CONTENT_TYPE_EXT_MAP.getOrDefault(contentType, "jpg");
        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String relativePath = directory + "/" + datePath + "/" + fileName;
        String fullPath = basePath + "/" + relativePath;

        // 4. 写入磁盘
        File dest = new File(fullPath);
        if (!dest.getParentFile().exists() && !dest.getParentFile().mkdirs()) {
            log.error("[文件上传] 创建目录失败: {}", dest.getParentFile().getAbsolutePath());
            throw new BizException(ResultCode.FILE_UPLOAD_FAIL, "文件上传失败");
        }

        try {
            file.transferTo(dest);
        } catch (IOException e) {
            log.error("[文件上传] 写入磁盘失败: {}", fullPath, e);
            throw new BizException(ResultCode.FILE_UPLOAD_FAIL, "文件上传失败");
        }

        String url = urlPrefix + "/" + relativePath;
        log.info("[文件上传] 上传成功: {}", url);
        return url;
    }
}
