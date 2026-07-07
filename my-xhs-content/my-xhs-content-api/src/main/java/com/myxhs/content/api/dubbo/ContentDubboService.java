package com.myxhs.content.api.dubbo;

import java.util.List;
import java.util.Map;

/**
 * 内容 Dubbo 服务接口（Triple 协议，替代 Feign 调用）
 * <p>
 * 用于首页聚合服务高频调用内容服务：
 * - 笔记详情（Feed 流核心数据）
 * - 评论分页（笔记详情页）
 * - 用户笔记列表（用户主页）
 * </p>
 */
public interface ContentDubboService {

    /**
     * 获取笔记详情
     */
    Map<String, Object> getNoteDetail(Long noteId);

    /**
     * 评论分页查询
     */
    Map<String, Object> getCommentPage(Long noteId, int pageNum, int pageSize);

    /**
     * 获取指定用户的笔记列表（仅已发布）
     */
    Map<String, Object> getUserNotes(Long userId, int pageNum, int pageSize);
}
