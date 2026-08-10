package com.myxhs.im.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.myxhs.common.annotation.RateLimit;
import com.myxhs.common.response.R;
import com.myxhs.common.util.JwtUtil;
import com.myxhs.im.dto.ConversationVO;
import com.myxhs.im.dto.ImMessageVO;
import com.myxhs.im.entity.ChatMessage;
import com.myxhs.im.entity.ChatUserRelation;
import com.myxhs.im.handler.ImWebSocketHandler;
import com.myxhs.im.service.ChatService;
import com.myxhs.im.service.OnlineRouteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * IM REST 接口
 * <p>
 * WebSocket 负责实时消息收发，REST 负责：
 * 1. WebSocket ticket 签发（两步法鉴权）
 * 2. 会话列表查询
 * 3. 历史消息查询
 * 4. 标记已读
 * 5. 未读消息数
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/im")
@RequiredArgsConstructor
@org.springframework.validation.annotation.Validated
public class ImController {

    private final ChatService chatService;
    private final ImWebSocketHandler webSocketHandler;
    private final OnlineRouteService onlineRouteService;

    @org.springframework.beans.factory.annotation.Value("${jwt.secret:${IM_JWT_SECRET:}}")
    private String jwtSecret;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 签发 WebSocket ticket（两步法鉴权）
     * <p>
     * 客户端先调用此接口获取短期 ticket（5 分钟有效），
     * 再用 ticket 建立 WebSocket 连接：ws://host/api/im/ws?ticket=xxx
     * 避免在 WebSocket URL 中暴露长期 Token。
     * </p>
     */
    @PostMapping("/ws/ticket")
    public R<Map<String, String>> createTicket(@RequestHeader("X-User-Id") Long userId) {
        // 生成短期 JWT ticket（5 分钟有效期）
        String ticket = JwtUtil.generateToken(String.valueOf(userId), "ws_ticket", 5 * 60 * 1000L, jwtSecret);
        return R.ok(Map.of("ticket", ticket));
    }

    /**
     * 获取会话列表
     */
    @GetMapping("/conversations")
    public R<Map<String, Object>> getConversations(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        size = Math.min(size, 50);

        Page<ChatUserRelation> pageResult = chatService.getConversationList(userId, page, size);

        List<ConversationVO> voList = pageResult.getRecords().stream()
                .map(r -> ConversationVO.builder()
                        .peerId(r.getPeerId())
                        .lastContent(r.getLastContent())
                        .lastMsgType(r.getLastMsgType())
                        .unreadCount(r.getUnreadCount())
                        .updatedAt(r.getUpdatedAt() != null ? r.getUpdatedAt().format(DT_FMT) : null)
                        .build())
                .collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("records", voList);
        result.put("total", pageResult.getTotal());
        result.put("page", page);
        return R.ok(result);
    }

    /**
     * 获取与某人的聊天记录（分页）
     */
    @GetMapping("/messages/{peerId}")
    public R<Map<String, Object>> getMessages(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long peerId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        size = Math.min(size, 100);

        Page<ChatMessage> pageResult = chatService.getMessageHistory(userId, peerId, page, size);

        List<ImMessageVO> records = pageResult.getRecords().stream()
                .map(m -> ImMessageVO.builder()
                        .id(m.getId())
                        .senderId(m.getSenderId())
                        .receiverId(m.getReceiverId())
                        .content(m.getContent())
                        .msgType(m.getMsgType())
                        .createdAt(m.getCreatedAt() != null ? m.getCreatedAt().format(DT_FMT) : null)
                        .build())
                .collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("records", records);
        result.put("total", pageResult.getTotal());
        result.put("page", page);
        return R.ok(result);
    }

    /**
     * 标记与某人的消息全部已读
     */
    @PostMapping("/read/{peerId}")
    @RateLimit(prefix = "im:read", maxRequests = 20, windowSeconds = 60, perUser = true)
    public R<Void> markAllRead(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long peerId) {
        chatService.markAllRead(userId, peerId);
        return R.ok();
    }

    /**
     * 获取总未读消息数
     */
    @GetMapping("/unread-count")
    public R<Map<String, Integer>> getUnreadCount(@RequestHeader("X-User-Id") Long userId) {
        int total = chatService.getTotalUnreadCount(userId);
        return R.ok(Map.of("total", total));
    }

    /**
     * 获取在线状态
     */
    @GetMapping("/online-count")
    public R<Map<String, Object>> getOnlineCount(
            @RequestHeader(value = "X-Admin-Call", required = false) String adminCall) {
        if (!isAdminCall(adminCall)) return R.fail(403, "无权访问管理接口");
        return R.ok(Map.of(
                "localOnline", webSocketHandler.getOnlineCount(),
                "serverId", onlineRouteService.getServerId()));
    }

    /** 管理接口令牌（配置化管理，不再硬编码） */
    @org.springframework.beans.factory.annotation.Value("${myxhs.admin.token}")
    private String adminToken;

    private boolean isAdminCall(String v) {
        return adminToken != null && !adminToken.isEmpty() && adminToken.equals(v);
    }
}
