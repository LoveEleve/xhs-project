package com.myxhs.notification.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.myxhs.common.annotation.RateLimit;
import com.myxhs.common.exception.BizException;
import com.myxhs.common.response.R;
import com.myxhs.common.response.ResultCode;
import com.myxhs.common.web.AccessTokenGuard;
import com.myxhs.notification.dto.NotificationVO;
import com.myxhs.notification.dto.UnreadCountVO;
import com.myxhs.notification.service.NotificationService;
import com.myxhs.notification.service.SseTicketService;
import com.myxhs.notification.sse.SseEmitterManager;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 通知接口
 */
@Slf4j
@RestController
@RequestMapping("/api/notification")
@RequiredArgsConstructor
@org.springframework.validation.annotation.Validated
public class NotificationController {

    private final NotificationService notificationService;
    private final SseTicketService sseTicketService;
    private final SseEmitterManager sseEmitterManager;
    private final AccessTokenGuard accessTokenGuard;

    // ==================== SSE 连接 ====================

    /**
     * 获取 SSE 连接 Ticket（30 秒有效，一次性）
     * <p>
     * 两步法第一步：用 HTTP POST（Header 携带 Token）获取 Ticket。
     * </p>
     */
    @PostMapping("/sse/ticket")
    public R<Map<String, Object>> getSseTicket(@RequestHeader("X-User-Id") Long userId) {
        String ticket = sseTicketService.generateTicket(userId);
        return R.ok(Map.of("ticket", ticket, "expiresIn", 30));
    }

    /**
     * SSE 长连接（两步法第二步：用 Ticket 建立连接）
     * <p>
     * 返回 text/event-stream，浏览器 EventSource 自动处理。
     * 超时设为 0（永不超时），由心跳保活。
     * </p>
     */
    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sseConnect(@RequestParam String ticket) {
        Long userId = sseTicketService.validateAndConsume(ticket);
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED, "Ticket无效或已过期");
        }
        return sseEmitterManager.createConnection(userId);
    }

    // ==================== 通知列表 ====================

    /**
     * 通知列表（分页 + 类型筛选）
     */
    @GetMapping("/list")
    public R<Page<NotificationVO>> getNotificationList(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(required = false) Integer type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        // page 下限 clamp（size 已 clamp；page<=0 时响应页码与实际不符）
        page = Math.max(1, page);
        size = Math.max(1, Math.min(size, 50));
        return R.ok(notificationService.getNotificationList(userId, type, page, size));
    }

    // ==================== 未读计数 ====================

    /**
     * 获取未读通知数（总 + 分类）
     */
    @GetMapping("/unread-count")
    public R<UnreadCountVO> getUnreadCount(@RequestHeader("X-User-Id") Long userId) {
        return R.ok(notificationService.getUnreadCount(userId));
    }

    // ==================== 标记已读 ====================

    /**
     * 标记单条已读
     */
    @PostMapping("/read/{id}")
    @RateLimit(prefix = "myxhs:notification:read", maxRequests = 30, windowSeconds = 60, perUser = true)
    public R<Void> markAsRead(@RequestHeader("X-User-Id") Long userId,
                              @PathVariable Long id) {
        notificationService.markAsRead(userId, id);
        return R.ok();
    }

    /**
     * 按类型全部标记已读
     */
    @PostMapping("/read-by-type/{type}")
    @RateLimit(prefix = "myxhs:notification:readByType", maxRequests = 10, windowSeconds = 60, perUser = true)
    public R<Void> markAllReadByType(@RequestHeader("X-User-Id") Long userId,
                                     @PathVariable Integer type) {
        notificationService.markAllReadByType(userId, type);
        return R.ok();
    }

    /**
     * 全部标记已读
     */
    @PostMapping("/read-all")
    @RateLimit(prefix = "myxhs:notification:readAll", maxRequests = 5, windowSeconds = 60, perUser = true)
    public R<Void> markAllAsRead(@RequestHeader("X-User-Id") Long userId) {
        notificationService.markAllAsRead(userId);
        return R.ok();
    }

    // ==================== 调试接口 ====================

    /**
     * 获取 SSE 在线连接数（运维/调试用）
     */
    @GetMapping("/sse/online-count")
    public R<Map<String, Object>> getOnlineCount(
            @RequestHeader(value = "X-Admin-Call", required = false) String adminCall) {
        if (!accessTokenGuard.isAdminCall(adminCall)) return R.fail(403, "无权访问管理接口");
        return R.ok(Map.of("onlineCount", sseEmitterManager.getOnlineCount()));
    }

    @PostConstruct
    public void validateAdminToken() {
        accessTokenGuard.requireAdminTokenConfigured("notification");
    }
}
