package com.myxhs.user.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户公开信息响应（精简版）
 * <p>
 * 用于公开接口（如查看他人主页），只暴露非敏感字段。
 * 不包含 phone、email、birthday 等隐私信息。
 * </p>
 */
@Data
@Builder
public class UserPublicInfoResponse {

    /** 用户 ID */
    private Long id;

    /** 用户名 */
    private String username;

    /** 昵称 */
    private String nickname;

    /** 头像 URL */
    private String avatar;

    /** 性别（0-未知 1-男 2-女） */
    private Integer gender;

    /** 个性签名 */
    private String signature;

    /** 注册时间 */
    private LocalDateTime createdAt;
}
