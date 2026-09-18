package com.myxhs.common.zone.locator;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件 Zone 定位器：读取部署环境写入的 zone 文件（类云元数据文件模式，如 ECS metadata file）。
 * <p>默认路径 /etc/myxhs/zone；文件不存在或为空则返回 null。</p>
 *
 * @since 1.0.0
 */
@Slf4j
public class FileZoneLocator implements ZoneLocator {

    private final String filePath;

    public FileZoneLocator(String filePath) {
        this.filePath = filePath;
    }

    @Override
    public String locate() {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(filePath);
            if (!Files.isReadable(path)) {
                return null;
            }
            String content = Files.readString(path);
            String zone = content == null ? null : content.trim();
            if (zone == null || zone.isEmpty()) {
                return null;
            }
            log.info("[ZoneLocator] 从文件发现 Zone: file={}, zone={}", filePath, zone);
            return zone;
        } catch (Exception e) {
            log.debug("[ZoneLocator] 读取 zone 文件失败: file={}, err={}", filePath, e.getMessage());
            return null;
        }
    }

    @Override
    public int getOrder() {
        return 20;
    }
}
