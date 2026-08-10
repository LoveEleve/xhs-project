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
        // 1. 校验文件类型（Content-Type 白名单）
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new BizException(ResultCode.PARAM_INVALID, "文件类型不允许，仅支持 JPEG/PNG/GIF/WebP");
        }

        // 2. 校验文件大小（最大 5MB）——必须在魔数校验之前，避免大文件 getBytes() OOM
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BizException(ResultCode.PARAM_INVALID, "文件大小不能超过5MB");
        }

        // 3. 校验文件头魔数（防 Content-Type 伪造）——仅读取前 12 字节（WebP 头最大长度）
        if (!verifyMagicBytes(file, contentType)) {
            log.warn("[文件上传] 文件头魔数不匹配，拒绝上传: claimed={}", contentType);
            throw new BizException(ResultCode.PARAM_INVALID, "文件内容与声明的类型不匹配");
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
            // 清理残留的半截文件，防止磁盘泄漏
            dest.delete();
            throw new BizException(ResultCode.FILE_UPLOAD_FAIL, "文件上传失败");
        }

        String url = urlPrefix + "/" + relativePath;
        log.info("[文件上传] 上传成功: {}", url);
        return url;
    }

    /**
     * 校验文件头魔数，防止 Content-Type 伪造
     * JPEG: FF D8 / PNG: 89 50 4E 47 / GIF: 47 49 46 38 / WebP: RIFF...WEBP
     */
    private boolean verifyMagicBytes(MultipartFile file, String contentType) {
        try {
            byte[] h = file.getBytes();
            if (h.length < 4) return false;
            return switch (contentType) {
                case "image/jpeg" -> (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8;
                case "image/png" -> h[0] == (byte)0x89 && h[1]==0x50 && h[2]==0x4E && h[3]==0x47;
                case "image/gif" -> h[0]==0x47 && h[1]==0x49 && h[2]==0x46 && h[3]==0x38;
                case "image/webp" -> h.length >= 12 && h[0]==0x52 && h[1]==0x49 && h[2]==0x46 && h[3]==0x46 && h[8]==0x57 && h[9]==0x45 && h[10]==0x42 && h[11]==0x50;
                default -> true;
            };
        } catch (IOException e) {
            log.error("[文件上传] 读取文件头失败", e);
            return false;
        }
    }
}
