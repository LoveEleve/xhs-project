package com.myxhs.im.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话列表 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationVO {

    private Long peerId;
    private String peerName;
    private String peerAvatar;
    private String lastContent;
    private Integer lastMsgType;
    private Integer unreadCount;
    private String updatedAt;
}
