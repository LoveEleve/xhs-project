package com.myxhs.content.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 文件存储接口 — 抽象层
 * <p>
 * 本地磁盘实现用于开发环境，生产环境切 MinIO/OSS 只需加一个实现类。
 * </p>
 */
public interface FileStorageService {

    /**
     * 上传文件
     *
     * @param file      上传的文件
     * @param directory 存储子目录（如 "note"）
     * @return 文件访问 URL
     */
    String upload(MultipartFile file, String directory);
}
