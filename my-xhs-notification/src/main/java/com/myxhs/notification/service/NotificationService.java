package com.myxhs.notification.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.response.ResultCode;
import com.myxhs.notification.dto.NotificationEventDTO;
import com.myxhs.notification.dto.NotificationType;
import com.myxhs.notification.dto.NotificationVO;
import com.myxhs.notification.dto.UnreadCountVO;
import com.myxhs.notification.entity.Notification;
import com.myxhs.notification.entity.PushTemplate;
import com.myxhs.notification.mapper.NotificationMapper;
import com.myxhs.notification.mapper.PushTemplateMapper;
import com.myxhs.notification.sse.SseEmitterManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 通知服务
 * <p>
 * 核心职责：
 * 1. 处理通知事件（MQ 消费者调用）
 * 2. 通知列表查询（分页 + 类型筛选）
 * 3. 标记已读（单条/按类型/全部）
 * 4. 获取未读计数
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationMapper notificationMapper;
    private final PushTemplateMapper pushTemplateMapper;
    private final NotificationAggregator aggregator;
    private final UnreadCountService unreadCountService;
    private final SseEmitterManager sseEmitterManager;

    // ==================== 处理通知事件 ====================

    /**
     * 处理通知事件（由 MQ 消费者调用）
     * <p>
     * 流程：
     * 1. 构建通知实体（模板渲染标题/内容）
     * 2. 聚合处理（5 分钟窗口内合并同类通知）
     * 3. 更新未读计数（Redis INCR）
     * 4. SSE 实时推送（如果用户在线）
     * </p>
     */
    public void processEvent(NotificationEventDTO event) {
        // 1. 构建通知实体
        Notification notification = buildNotification(event);

        // 2. 聚合处理
        Notification result = aggregator.processWithAggregate(notification);

        // 3. 更新未读计数（只有新建通知才增加计数，聚合更新不增加）
        if (Objects.equals(result.getId(), notification.getId())) {
            // 新建的通知 → 未读 +1
            unreadCountService.incrementUnread(event.getTargetUserId(), event.getType());
        }
        // 聚合更新的通知 → 不增加未读计数（用户已经看到了红点）

        // 4. SSE 实时推送
        if (sseEmitterManager.isOnline(event.getTargetUserId())) {
            NotificationVO vo = toVO(result);
            sseEmitterManager.pushNotification(event.getTargetUserId(), vo);

            // 推送未读计数变更
            UnreadCountVO countVO = unreadCountService.getUnreadCount(event.getTargetUserId());
            sseEmitterManager.pushUnreadCount(event.getTargetUserId(), countVO);
        }

        log.info("[通知] 处理完成: targetUserId={}, type={}, senderId={}",
                event.getTargetUserId(), event.getType(), event.getSenderId());
    }

    // ==================== 查询 ====================

    /**
     * 查询通知列表（分页 + 类型筛选）
     */
    public Page<NotificationVO> getNotificationList(Long userId, Integer type, int page, int size) {
        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .orderByDesc(Notification::getCreatedAt);

        if (type != null) {
            wrapper.eq(Notification::getType, type);
        }

        Page<Notification> pageResult = notificationMapper.selectPage(
                new Page<>(page, size), wrapper);

        Page<NotificationVO> voPage = new Page<>(page, size, pageResult.getTotal());
        voPage.setRecords(pageResult.getRecords().stream()
                .map(this::toVO)
                .collect(Collectors.toList()));
        return voPage;
    }

    /**
     * 获取未读计数
     */
    public UnreadCountVO getUnreadCount(Long userId) {
        return unreadCountService.getUnreadCount(userId);
    }

    // ==================== 标记已读 ====================

    /**
     * 标记单条已读
     */
    public void markAsRead(Long userId, Long notificationId) {
        Notification notification = notificationMapper.selectById(notificationId);
        if (notification == null || !notification.getUserId().equals(userId)) {
            throw new BizException(ResultCode.NOT_FOUND, "通知不存在");
        }
        if (notification.getIsRead() == 1) {
            return; // 幂等：已读不重复处理
        }

        int affected = notificationMapper.markAsRead(userId, notificationId);
        if (affected > 0) {
            unreadCountService.decrementUnread(userId, notification.getType());
        }
    }

    /**
     * 按类型全部标记已读
     */
    public void markAllReadByType(Long userId, Integer type) {
        int affected = notificationMapper.markAllReadByType(userId, type);
        if (affected > 0) {
            unreadCountService.resetUnreadByType(userId, type);
        }
        log.info("[通知] 按类型标记已读: userId={}, type={}, affected={}", userId, type, affected);
    }

    /**
     * 全部标记已读
     */
    public void markAllAsRead(Long userId) {
        int affected = notificationMapper.markAllAsRead(userId);
        if (affected > 0) {
            unreadCountService.resetUnread(userId);
        }
        log.info("[通知] 全部标记已读: userId={}, affected={}", userId, affected);
    }

    // ==================== 私有方法 ====================

    /**
     * 构建通知实体（模板渲染）
     */
    private Notification buildNotification(NotificationEventDTO event) {
        // 查询模板
        NotificationType nt = NotificationType.fromCode(event.getType());
        PushTemplate template = pushTemplateMapper.selectByType(nt.getName());

        String title;
        String content = event.getContent();

        if (template != null) {
            title = template.getTitleTemplate()
                    .replace("{sender}", event.getSenderName() != null ? event.getSenderName() : "某用户")
                    .replace("{target}", event.getTargetName() != null ? event.getTargetName() : "")
                    .replace("{title}", event.getTargetName() != null ? event.getTargetName() : "")
                    .replace("{content}", event.getContent() != null ? event.getContent() : "");
            if (template.getContentTemplate() != null && content == null) {
                content = template.getContentTemplate()
                        .replace("{sender}", event.getSenderName() != null ? event.getSenderName() : "")
                        .replace("{target}", event.getTargetName() != null ? event.getTargetName() : "")
                        .replace("{content}", event.getContent() != null ? event.getContent() : "");
            }
        } else {
            title = (event.getSenderName() != null ? event.getSenderName() : "某用户") + "与你互动";
        }

        return new Notification()
                .setUserId(event.getTargetUserId())
                .setType(event.getType())
                .setTitle(title)
                .setContent(content)
                .setSenderId(event.getSenderId())
                .setSenderName(event.getSenderName())
                .setSenderAvatar(event.getSenderAvatar())
                .setTargetId(event.getTargetId())
                .setTargetType(event.getTargetType())
                .setExtraData(event.getExtraData());
    }

    private NotificationVO toVO(Notification n) {
        return NotificationVO.builder()
                .id(n.getId())
                .type(n.getType())
                .title(n.getTitle())
                .content(n.getContent())
                .senderId(n.getSenderId())
                .senderName(n.getSenderName())
                .senderAvatar(n.getSenderAvatar())
                .targetId(n.getTargetId())
                .targetType(n.getTargetType())
                .isRead(n.getIsRead())
                .aggregateCount(n.getAggregateCount())
                .createdAt(n.getCreatedAt())
                .build();
    }
}
